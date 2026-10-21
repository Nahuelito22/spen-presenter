package com.nahuel.spenpresenter

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * La direccion del agente de la PC, persistida entre arranques.
 *
 * SharedPreferences y no DataStore: es un solo string y no justifica sumar
 * una dependencia ni corrutinas para leerlo.
 */
object Settings {

    private const val PREFS = "spen_presenter"
    private const val KEY_HOST = "agent_host"
    private const val KEY_TOKEN = "agent_token"
    private const val KEY_MAPPING_PREFIX = "map_"
    private const val KEY_MODE = "transport_mode"
    const val DEFAULT_PORT = 8765

    private val _host = MutableStateFlow("")

    /** Solo el host o IP, sin esquema ni puerto. Vacio = sin configurar. */
    val host: StateFlow<String> = _host

    private val _token = MutableStateFlow("")

    /** Codigo de emparejamiento que muestra el agente. Vacio = sin configurar. */
    val token: StateFlow<String> = _token

    private val _mode = MutableStateFlow(TransportMode.AUTO)

    /** Por donde le hablamos a la PC. */
    val mode: StateFlow<TransportMode> = _mode

    private val _mapping = MutableStateFlow(Mapping.DEFAULTS)

    /** Que comando dispara cada evento del S Pen. */
    val mapping: StateFlow<Map<PenEvent, Command>> = _mapping

    fun load(context: Context) {
        val prefs = prefs(context)
        _host.value = prefs.getString(KEY_HOST, "").orEmpty()
        _token.value = prefs.getString(KEY_TOKEN, "").orEmpty()
        _mode.value = TransportMode.entries
            .firstOrNull { it.name == prefs.getString(KEY_MODE, null) }
            ?: TransportMode.AUTO
        _mapping.value = PenEvent.entries.associateWith { event ->
            val stored = prefs.getString(KEY_MAPPING_PREFIX + event.name, null)
            Command.entries.firstOrNull { it.name == stored }
                ?: Mapping.DEFAULTS.getValue(event)
        }
    }

    fun setMode(context: Context, mode: TransportMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    fun setMapping(context: Context, event: PenEvent, command: Command) {
        prefs(context).edit()
            .putString(KEY_MAPPING_PREFIX + event.name, command.name)
            .apply()
        _mapping.value = _mapping.value + (event to command)
    }

    fun resetMapping(context: Context) {
        val editor = prefs(context).edit()
        PenEvent.entries.forEach { editor.remove(KEY_MAPPING_PREFIX + it.name) }
        editor.apply()
        _mapping.value = Mapping.DEFAULTS
    }

    fun setHost(context: Context, host: String) {
        val clean = host.trim()
        prefs(context).edit().putString(KEY_HOST, clean).apply()
        _host.value = clean
    }

    fun setToken(context: Context, token: String) {
        // El agente lo genera en mayusculas; normalizamos para que no moleste
        // si Nahuel lo tipea en minuscula.
        val clean = token.trim().uppercase()
        prefs(context).edit().putString(KEY_TOKEN, clean).apply()
        _token.value = clean
    }

    /** `ws://192.168.1.171:8765`, o null si todavia no hay nada configurado. */
    fun url(): String? =
        _host.value.takeIf { it.isNotBlank() }?.let { "ws://$it:$DEFAULT_PORT" }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
