package com.nahuel.spenpresenter.transport

import com.nahuel.spenpresenter.Command
import kotlinx.coroutines.flow.StateFlow

/** Estado de la conexion con la PC, para la UI y la notificacion. */
sealed interface ConnectionState {

    /** Sin configurar o apagado a mano. */
    data object Idle : ConnectionState

    /** Buscando el agente por mDNS. */
    data object Searching : ConnectionState

    /** Intentando conectar; [attempt] empieza en 1. */
    data class Connecting(val attempt: Int) : ConnectionState

    /**
     * Conectado. [latencyMs] es null hasta el primer pong (y siempre en
     * Bluetooth, que es unidireccional). [via] nombra el transporte activo,
     * que en modo automatico no se sabe de antemano.
     */
    data class Connected(val latencyMs: Long?, val via: String? = null) : ConnectionState

    /** Caido. [reason] es para mostrarle algo util a Nahuel, no para parsear. */
    data class Failed(val reason: String) : ConnectionState
}

/**
 * Como le hablamos a la PC.
 *
 * La Fase 3 implementa [WebSocketTransport] sobre la LAN; la Fase 4 agregaria
 * un HidTransport por Bluetooth que no necesita agente del otro lado.
 */
interface Transport {

    val state: StateFlow<ConnectionState>

    fun connect()

    fun disconnect()

    /**
     * Manda un comando. Es fire-and-forget a proposito: si estamos
     * desconectados se pierde, y esta bien que asi sea. Reenviar una flecha
     * medio segundo tarde es peor que no mandarla.
     */
    fun send(command: Command)
}
