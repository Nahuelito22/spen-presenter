package com.nahuel.spenpresenter

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/**
 * Reproduce un audio silencioso en loop.
 *
 * Es el truco central de la Fase 2: Android enruta los botones de media a la
 * sesion que esta reproduciendo algo. Sin audio sonando, nuestra MediaSession
 * no gana la prioridad y el S Pen le sigue hablando a Spotify.
 *
 * Tambien pedimos el foco de audio, que es lo que nos pone al frente de la
 * cola. Como efecto esperado, al arrancar se pausa la musica que estuviera
 * sonando.
 */
class SilentPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    fun start() {
        if (player != null) return

        requestFocus()

        player = MediaPlayer.create(context, R.raw.silence)?.apply {
            setAudioAttributes(attributes)
            isLooping = true
            setVolume(0f, 0f)
            start()
        } ?: run {
            Log.e(TAG, "No se pudo crear el MediaPlayer con res/raw/silence")
            null
        }
    }

    fun stop() {
        player?.run {
            if (isPlaying) stop()
            release()
        }
        player = null
        abandonFocus()
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // No queremos que nos bajen el volumen ni que nos interrumpan:
            // si perdemos el foco, lo registramos y seguimos.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                Log.i(TAG, "cambio de foco de audio: $change")
            }
            .build()
        focusRequest = request

        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "el sistema no nos dio el foco de audio (resultado $result)")
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        const val TAG = "SilentPlayer"
    }
}
