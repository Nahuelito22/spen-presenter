package com.nahuel.spenpresenter.transport

import android.util.Log
import com.nahuel.spenpresenter.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Transporte por WebSocket contra el agente de la PC.
 *
 * Se reconecta solo con backoff exponencial y mide la latencia con el
 * ping/pong del protocolo. La idea es que Nahuel pueda caminar por el aula,
 * perder el WiFi un segundo y que esto se reenganche sin tocar nada.
 */
class WebSocketTransport(
    private val url: String,
    private val token: String,
    private val scope: CoroutineScope,
) : Transport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // El ping del propio protocolo WebSocket detecta cortes de red que
        // de otro modo nos dejarian "conectados" contra la nada.
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var latencyJob: Job? = null

    private var attempt = 0
    private var wantConnection = false
    private var authenticated = false
    private var lastPingSentAt = 0L

    override fun connect() {
        wantConnection = true
        openSocket()
    }

    override fun disconnect() {
        wantConnection = false
        authenticated = false
        reconnectJob?.cancel()
        latencyJob?.cancel()
        socket?.close(NORMAL_CLOSURE, "el usuario detuvo el servicio")
        socket = null
        _state.value = ConnectionState.Idle
    }

    override fun send(command: Command) {
        val active = socket
        if (active == null || !authenticated) {
            Log.w(TAG, "comando ${command.wire} descartado: no hay conexion lista")
            return
        }
        active.send("""{"cmd":"${command.wire}"}""")
    }

    private fun openSocket() {
        attempt++
        _state.value = ConnectionState.Connecting(attempt)
        Log.i(TAG, "conectando a $url (intento $attempt)")

        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Todavia no estamos "conectados" de verdad: falta que el agente
            // acepte el codigo. Hasta entonces no mandamos comandos.
            Log.i(TAG, "socket abierto, autenticando")
            authenticated = false
            webSocket.send("""{"cmd":"auth","token":"$token"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return

            if (json.has("auth")) {
                if (json.optString("auth") == "ok") {
                    Log.i(TAG, "autenticado contra $url")
                    authenticated = true
                    attempt = 0
                    _state.value = ConnectionState.Connected(latencyMs = null)
                    startLatencyProbe(webSocket)
                } else {
                    // Reintentar con el mismo codigo incorrecto no va a servir:
                    // paramos y que Nahuel lo corrija en la UI.
                    Log.w(TAG, "el agente rechazo el codigo")
                    wantConnection = false
                    _state.value = ConnectionState.Failed("codigo de emparejamiento incorrecto")
                }
                return
            }

            if (json.has("pong") && lastPingSentAt != 0L) {
                val rtt = System.currentTimeMillis() - lastPingSentAt
                _state.value = ConnectionState.Connected(latencyMs = rtt)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "conexion caida: ${t.message}")
            _state.value = ConnectionState.Failed(t.message ?: "error desconocido")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "conexion cerrada ($code)")
            if (wantConnection) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnection) return
        latencyJob?.cancel()
        socket = null
        authenticated = false

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // 1, 2, 4, 8... hasta un techo, para no quemar bateria golpeando
            // una IP que quizas ni existe.
            val seconds = min(
                MAX_BACKOFF_SECONDS,
                2.0.pow(attempt.coerceAtMost(MAX_BACKOFF_EXPONENT)).toLong(),
            )
            Log.i(TAG, "reintento en $seconds s")
            delay(seconds * 1000)
            if (wantConnection) openSocket()
        }
    }

    private fun startLatencyProbe(webSocket: WebSocket) {
        latencyJob?.cancel()
        latencyJob = scope.launch {
            while (isActive) {
                lastPingSentAt = System.currentTimeMillis()
                webSocket.send("""{"cmd":"ping"}""")
                delay(LATENCY_PROBE_MILLIS)
            }
        }
    }

    private companion object {
        const val TAG = "WebSocketTransport"
        const val NORMAL_CLOSURE = 1000
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val PING_INTERVAL_SECONDS = 10L
        const val LATENCY_PROBE_MILLIS = 5_000L
        const val MAX_BACKOFF_SECONDS = 15L
        const val MAX_BACKOFF_EXPONENT = 4
    }
}
