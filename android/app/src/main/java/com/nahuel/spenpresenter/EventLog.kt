package com.nahuel.spenpresenter

import com.nahuel.spenpresenter.transport.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Una linea de la lista de eventos que muestra la UI. */
data class LoggedEvent(
    val event: PenEvent,
    val command: Command,
    val atMillis: Long,
)

/**
 * Puente entre el servicio y la UI para la Fase 2.
 *
 * Es un singleton en memoria a proposito: en esta fase solo queremos *ver* que
 * los eventos llegan. No sobrevive a que el sistema mate el proceso, y no hace
 * falta que lo haga.
 */
object EventLog {

    private const val MAX_ENTRIES = 50

    private val _events = MutableStateFlow<List<LoggedEvent>>(emptyList())
    val events: StateFlow<List<LoggedEvent>> = _events

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection

    fun add(event: PenEvent, command: Command) {
        _events.update { current ->
            (listOf(LoggedEvent(event, command, System.currentTimeMillis())) + current)
                .take(MAX_ENTRIES)
        }
    }

    fun clear() = _events.update { emptyList() }

    fun setRunning(running: Boolean) {
        _running.value = running
    }

    fun setConnection(state: ConnectionState) {
        _connection.value = state
    }
}
