package com.nahuel.spenpresenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import androidx.media.session.MediaButtonReceiver
import com.nahuel.spenpresenter.transport.ConnectionState
import com.nahuel.spenpresenter.transport.HidTransport
import com.nahuel.spenpresenter.transport.Transport
import com.nahuel.spenpresenter.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service que mantiene viva la MediaSession por la que entran los
 * eventos del S Pen.
 *
 * En la Fase 2 solo registra lo que llega (lista en la UI + vibracion). El
 * envio a la PC se engancha en la Fase 3, en [dispatch].
 */
class PresenterService : Service() {

    private lateinit var session: MediaSessionCompat
    private lateinit var silentPlayer: SilentPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transport: Transport? = null
    private var discoveryJob: Job? = null

    /**
     * Declarar la sesion como "remota" hace que los gestos de volumen del
     * S Pen lleguen aca en vez de mover el volumen del telefono.
     *
     * Es experimental: si Air Actions llama al AudioManager directo en lugar de
     * mandar la tecla de volumen, estos callbacks no se van a disparar nunca.
     * En ese caso alcanza con borrar el setPlaybackToRemote de abajo.
     */
    private val volumeProvider = object : VolumeProviderCompat(
        VOLUME_CONTROL_RELATIVE,
        /* maxVolume = */ 2,
        /* currentVolume = */ 1,
    ) {
        override fun onAdjustVolume(direction: Int) {
            when {
                direction > 0 -> onPenEvent(PenEvent.VOLUME_UP)
                direction < 0 -> onPenEvent(PenEvent.VOLUME_DOWN)
            }
            // Volvemos siempre al centro para que nunca toque los extremos y
            // deje de mandarnos eventos.
            currentVolume = 1
        }
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = onPenEvent(PenEvent.PLAY_PAUSE)
        override fun onPause() = onPenEvent(PenEvent.PLAY_PAUSE)
        override fun onSkipToNext() = onPenEvent(PenEvent.NEXT)
        override fun onSkipToPrevious() = onPenEvent(PenEvent.PREVIOUS)
    }

    override fun onCreate() {
        super.onCreate()

        Settings.load(this)
        silentPlayer = SilentPlayer(this)

        session = MediaSessionCompat(this, TAG).apply {
            setCallback(callback)
            setPlaybackState(playingState())
            setPlaybackToRemote(volumeProvider)
            isActive = true
        }

        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                openTransport()
                return START_STICKY
            }
            ACTION_DISCOVER -> {
                startDiscovery()
                return START_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                silentPlayer.start()
                // Reafirmamos el estado: si otra app nos robo la sesion
                // mientras estabamos parados, esto nos vuelve a poner al frente.
                session.setPlaybackState(playingState())
                session.isActive = true
                EventLog.setRunning(true)
                openTransport()
                Log.i(TAG, "servicio iniciado")
            }
        }
        // Que el sistema nos reviva si nos mata: es justo lo que queremos medir.
        MediaButtonReceiver.handleIntent(session, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        discoveryJob?.cancel()
        transport?.disconnect()
        transport = null
        scope.cancel()
        EventLog.setConnection(ConnectionState.Idle)
        silentPlayer.stop()
        session.isActive = false
        session.release()
        EventLog.setRunning(false)
        Log.i(TAG, "servicio detenido")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun onPenEvent(event: PenEvent) {
        val command = Mapping.toCommand(event, Settings.mapping.value)
        EventLog.add(event, command)
        vibrate()
        Log.i(TAG, "evento $event -> comando ${command.wire}")
        dispatch(command)
    }

    private fun dispatch(command: Command) {
        transport?.send(command)
    }

    /**
     * Abre (o reabre) la conexion con la PC segun lo que haya guardado en
     * [Settings]. Se llama al iniciar y cada vez que Nahuel cambia la IP.
     */
    private fun openTransport() {
        transport?.disconnect()

        if (Settings.mode.value == TransportMode.BLUETOOTH) {
            openTransport(HidTransport(this, scope))
            return
        }

        val url = Settings.url()
        if (url == null) {
            // Sin IP guardada probamos mDNS antes de darnos por vencidos.
            startDiscovery()
            return
        }

        openTransport(WebSocketTransport(url, Settings.token.value, scope))
    }

    private fun openTransport(next: Transport) {
        transport = next
        scope.launch {
            next.state.collect { state ->
                EventLog.setConnection(state)
                refreshNotification()
            }
        }
        next.connect()
    }

    /**
     * Busca el agente por mDNS y, si lo encuentra, guarda su IP y se conecta.
     * La IP queda persistida para la proxima vez: si el router no deja pasar
     * multicast en otra red, igual arrancamos con la ultima que funciono.
     */
    private fun startDiscovery() {
        discoveryJob?.cancel()
        EventLog.setConnection(ConnectionState.Searching)
        refreshNotification()

        discoveryJob = scope.launch {
            val agent = Discovery(this@PresenterService).find()
            if (agent == null) {
                Log.w(TAG, "no se encontro ningun agente por mDNS")
                EventLog.setConnection(
                    ConnectionState.Failed("no se encontro la PC en esta red"),
                )
                refreshNotification()
                return@launch
            }
            Log.i(TAG, "agente encontrado en ${agent.host}:${agent.port}")
            Settings.setHost(this@PresenterService, agent.host)
            openTransport()
        }
    }

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun playingState(): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
            )
            // El estado PLAYING es lo que nos hace candidatos a recibir los
            // botones de media, aunque lo que suene sea silencio.
            .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
            .build()

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PresenterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(connectionText())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken),
            )
            .build()
    }

    private fun connectionText(): String = when (val state = EventLog.connection.value) {
        is ConnectionState.Idle -> "Escuchando el S Pen - PC sin configurar"
        is ConnectionState.Searching ->
            if (Settings.mode.value == TransportMode.BLUETOOTH) {
                "Esperando que la PC se conecte por Bluetooth"
            } else {
                "Buscando la PC en la red..."
            }
        is ConnectionState.Connecting -> "Conectando con la PC (intento ${state.attempt})"
        is ConnectionState.Connected ->
            state.latencyMs?.let { "Conectado - ${it} ms" } ?: "Conectado a la PC"
        is ConnectionState.Failed -> "Sin conexion con la PC - reintentando"
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        private const val TAG = "PresenterService"
        private const val CHANNEL_ID = "presenter"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.nahuel.spenpresenter.STOP"
        const val ACTION_RECONNECT = "com.nahuel.spenpresenter.RECONNECT"
        const val ACTION_DISCOVER = "com.nahuel.spenpresenter.DISCOVER"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PresenterService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PresenterService::class.java).setAction(ACTION_STOP),
            )
        }

        /** Dispara una busqueda por mDNS, arrancando el servicio si hace falta. */
        fun discover(context: Context) {
            val intent = Intent(context, PresenterService::class.java)
                .setAction(ACTION_DISCOVER)
            if (EventLog.running.value) {
                context.startService(intent)
            } else {
                context.startForegroundService(Intent(context, PresenterService::class.java))
            }
        }

        /** Le avisa al servicio que la IP cambio, si es que esta corriendo. */
        fun reconnect(context: Context) {
            if (!EventLog.running.value) return
            context.startService(
                Intent(context, PresenterService::class.java).setAction(ACTION_RECONNECT),
            )
        }
    }
}
