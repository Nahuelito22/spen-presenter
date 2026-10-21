package com.nahuel.spenpresenter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nahuel.spenpresenter.transport.ConnectionState
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestBluetooth =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La UI es siempre clara, asi que forzamos iconos oscuros en la barra de
        // estado: con los blancos por defecto no se ve nada.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        Settings.load(this)
        askForNotifications()
        askForBluetooth()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    PresenterScreen(Modifier.padding(padding))
                }
            }
        }
    }

    /**
     * El modo Bluetooth necesita BLUETOOTH_CONNECT desde Android 12. Lo
     * pedimos solo si Nahuel ya eligio ese modo, para no molestar a quien use
     * la app por WiFi.
     */
    private fun askForBluetooth() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (Settings.mode.value != TransportMode.BLUETOOTH) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    /** Sin esto, en Android 13+ el foreground service corre sin notificacion visible. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresenterScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val events by EventLog.events.collectAsStateWithLifecycle()
    val running by EventLog.running.collectAsStateWithLifecycle()
    val connection by EventLog.connection.collectAsStateWithLifecycle()
    val savedHost by Settings.host.collectAsStateWithLifecycle()
    val savedToken by Settings.token.collectAsStateWithLifecycle()
    val mapping by Settings.mapping.collectAsStateWithLifecycle()
    val mode by Settings.mode.collectAsStateWithLifecycle()

    var host by remember(savedHost) { mutableStateOf(savedHost) }
    var token by remember(savedToken) { mutableStateOf(savedToken) }
    var showMapping by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("S Pen Presenter", style = MaterialTheme.typography.headlineSmall)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = {
                        Settings.setMode(context, option)
                        if (running) PresenterService.reconnect(context)
                    },
                    label = { Text(option.label) },
                )
            }
        }

        if (mode == TransportMode.BLUETOOTH) {
            Text(
                "El telefono aparece como un teclado en la pantalla de " +
                    "Bluetooth de la PC. Emparejalo desde ahi; no hace falta " +
                    "el agente.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (mode == TransportMode.LAN) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP del agente (o usa Buscar PC)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Codigo de emparejamiento") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = {
                    if (running) {
                        PresenterService.stop(context)
                    } else {
                        Settings.setHost(context, host)
                        Settings.setToken(context, token)
                        PresenterService.start(context)
                    }
                },
            ) {
                Text(if (running) "Detener" else "Iniciar")
            }
            OutlinedButton(
                onClick = {
                    Settings.setHost(context, host)
                    Settings.setToken(context, token)
                    PresenterService.reconnect(context)
                },
                // En Bluetooth no hay nada que reconectar a mano.
                enabled = mode == TransportMode.LAN &&
                    (host != savedHost ||
                        token != savedToken ||
                        connection is ConnectionState.Failed),
            ) {
                Text("Reconectar")
            }
            OutlinedButton(
                onClick = {
                    Settings.setToken(context, token)
                    PresenterService.discover(context)
                },
                enabled = mode == TransportMode.LAN &&
                    connection !is ConnectionState.Searching,
            ) {
                Text("Buscar PC")
            }
            OutlinedButton(onClick = { EventLog.clear() }) {
                Text("Limpiar")
            }
        }

        ConnectionBadge(running = running, state = connection)

        HorizontalDivider()

        TextButton(onClick = { showMapping = !showMapping }) {
            Text(if (showMapping) "Ocultar mapeo" else "Configurar mapeo")
        }

        if (showMapping) {
            MappingSection(
                mapping = mapping,
                onPick = { event, command -> Settings.setMapping(context, event, command) },
                onReset = { Settings.resetMapping(context) },
            )
            HorizontalDivider()
        }

        if (events.isEmpty()) {
            Text(
                "Sin eventos todavia. Inicia el servicio y usa el boton o los " +
                    "gestos del S Pen.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(events) { entry -> EventRow(entry) }
            }
        }
    }
}

@Composable
private fun MappingSection(
    mapping: Map<PenEvent, Command>,
    onPick: (PenEvent, Command) -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PenEvent.entries.forEach { event ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(event.label, style = MaterialTheme.typography.bodyMedium)
                CommandPicker(
                    current = mapping[event] ?: Mapping.DEFAULTS.getValue(event),
                    onPick = { onPick(event, it) },
                )
            }
        }
        TextButton(onClick = onReset) {
            Text("Restablecer el mapeo por defecto")
        }
    }
}

@Composable
private fun CommandPicker(current: Command, onPick: (Command) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(current.label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Command.entries.forEach { command ->
                DropdownMenuItem(
                    text = { Text(command.label) },
                    onClick = {
                        onPick(command)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectionBadge(running: Boolean, state: ConnectionState) {
    val (text, color) = when {
        !running -> "Detenido" to ComposeColor(0xFF6B6B6B)
        state is ConnectionState.Idle ->
            "Escuchando - falta cargar la IP de la PC" to ComposeColor(0xFFB36B00)
        state is ConnectionState.Searching ->
            "Buscando la PC en la red..." to ComposeColor(0xFFB36B00)
        state is ConnectionState.Connecting ->
            "Conectando (intento ${state.attempt})..." to ComposeColor(0xFFB36B00)
        state is ConnectionState.Connected ->
            (state.latencyMs?.let { "Conectado - $it ms" } ?: "Conectado") to
                ComposeColor(0xFF1B7A3D)
        state is ConnectionState.Failed ->
            "Sin conexion: ${state.reason}" to ComposeColor(0xFFB3261E)
        else -> "Escuchando el S Pen" to ComposeColor(0xFF6B6B6B)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun EventRow(entry: LoggedEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(TIME_FORMAT.format(entry.atMillis), style = MaterialTheme.typography.labelMedium)
        Text(entry.event.name, style = MaterialTheme.typography.bodyMedium)
        Text(entry.command.wire, style = MaterialTheme.typography.labelLarge)
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
