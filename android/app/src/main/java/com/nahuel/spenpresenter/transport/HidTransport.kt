package com.nahuel.spenpresenter.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.nahuel.spenpresenter.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Transporte por Bluetooth: el telefono se presenta ante la PC como un
 * teclado HID.
 *
 * La ventaja sobre [WebSocketTransport] es que del otro lado no hace falta
 * instalar nada: sirve en la PC de un aula o de un evento. La desventaja es
 * que depende de que el fabricante exponga el perfil HID Device, cosa que no
 * todos hacen.
 *
 * Emparejamiento: una vez que la app registra el perfil, la PC ve al telefono
 * como un teclado en su pantalla de Bluetooth y se empareja desde ahi.
 */
@SuppressLint("MissingPermission") // El permiso se pide en MainActivity.
class HidTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onNeedsPermission: () -> Unit = {},
) : Transport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state

    private val executor = Executors.newSingleThreadExecutor()

    private var hidDevice: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var retryJob: Job? = null

    private val sdp = BluetoothHidDeviceAppSdpSettings(
        "S Pen Presenter",
        "Control remoto de presentaciones",
        "S Pen Presenter",
        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
        KEYBOARD_DESCRIPTOR,
    )

    private val callback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidTransport.registered = registered
            Log.i(TAG, "perfil HID registrado=$registered")
            if (registered) {
                // Windows empareja el telefono por sus perfiles de audio y no
                // inicia la conexion HID por su cuenta: la tenemos que pedir
                // nosotros contra la PC ya emparejada.
                connectToPairedComputer()
            } else {
                _state.value = ConnectionState.Failed(
                    "este telefono no permite actuar como teclado Bluetooth",
                )
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.i(TAG, "estado de conexion con ${device?.address}: $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    // No hay ping/pong por HID: el host no nos contesta nada.
                    _state.value = ConnectionState.Connected(latencyMs = null, via = VIA)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    host = null
                    if (this@HidTransport.registered) {
                        // La PC puede estar dormida o haber rechazado el
                        // intento: insistimos sin prisa en vez de rendirnos.
                        scheduleRetry()
                    }
                }
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val device = proxy as? BluetoothHidDevice ?: return
            hidDevice = device
            Log.i(TAG, "proxy HID obtenido, registrando la app")
            val ok = device.registerApp(sdp, null, null, executor, callback)
            if (!ok) {
                Log.w(TAG, "registerApp devolvio false")
                _state.value = ConnectionState.Failed(
                    "el sistema rechazo registrar el teclado Bluetooth",
                )
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            host = null
            registered = false
            _state.value = ConnectionState.Idle
        }
    }

    /**
     * Pide conexion a las computadoras ya emparejadas.
     *
     * Si no hay ninguna, quedamos a la espera: cuando Nahuel empareje una, el
     * host va a aparecer y el callback de conexion nos va a avisar.
     */
    private fun connectToPairedComputer() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val device = hidDevice ?: return

        val computers = manager?.adapter?.bondedDevices.orEmpty().filter {
            it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
        }

        if (computers.isEmpty()) {
            Log.i(TAG, "no hay ninguna PC emparejada todavia")
            _state.value = ConnectionState.Searching
            return
        }

        _state.value = ConnectionState.Connecting(attempt = 1)
        computers.forEach { computer ->
            val asked = device.connect(computer)
            Log.i(TAG, "pedido de conexion a ${computer.name}: $asked")
        }
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            _state.value = ConnectionState.Searching
            delay(RETRY_DELAY_MILLIS)
            if (registered && host == null) connectToPairedComputer()
        }
    }

    override fun connect() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter

        if (adapter == null) {
            _state.value = ConnectionState.Failed("este dispositivo no tiene Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = ConnectionState.Failed("el Bluetooth esta apagado")
            return
        }

        _state.value = ConnectionState.Connecting(attempt = 1)

        val ok = try {
            adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (exc: SecurityException) {
            Log.w(TAG, "falta el permiso BLUETOOTH_CONNECT: ${exc.message}")
            onNeedsPermission()
            _state.value = ConnectionState.Failed("falta el permiso de Bluetooth")
            return
        }

        if (!ok) {
            _state.value = ConnectionState.Failed(
                "este telefono no expone el perfil de teclado Bluetooth",
            )
        }
    }

    override fun disconnect() {
        retryJob?.cancel()
        hidDevice?.let { device ->
            runCatching { device.unregisterApp() }
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            runCatching {
                manager?.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
            }
        }
        hidDevice = null
        host = null
        registered = false
        _state.value = ConnectionState.Idle
    }

    override fun send(command: Command) {
        val device = hidDevice
        val target = host
        if (device == null || target == null) {
            Log.w(TAG, "comando ${command.wire} descartado: la PC no esta conectada")
            return
        }

        val usage = command.hidUsage
        // Un teclado manda dos reportes: la tecla apretada y la tecla suelta.
        // Sin el segundo, el host la toma como mantenida y auto-repite.
        device.sendReport(target, REPORT_ID, keyReport(usage))
        device.sendReport(target, REPORT_ID, keyReport(0))
    }

    /** Reporte de teclado: modificadores, reservado y hasta 6 teclas. */
    private fun keyReport(usage: Int): ByteArray =
        byteArrayOf(0, 0, usage.toByte(), 0, 0, 0, 0, 0)

    private val Command.hidUsage: Int
        get() = when (this) {
            Command.NEXT -> USAGE_ARROW_RIGHT
            Command.PREV -> USAGE_ARROW_LEFT
            Command.BLACK -> USAGE_B
            Command.EXIT -> USAGE_ESCAPE
        }

    private companion object {
        const val TAG = "HidTransport"
        const val VIA = "Bluetooth"
        const val REPORT_ID = 1
        const val RETRY_DELAY_MILLIS = 5_000L

        // Usages del HID Usage Table, pagina 0x07 (teclado).
        const val USAGE_B = 0x05
        const val USAGE_ESCAPE = 0x29
        const val USAGE_ARROW_RIGHT = 0x4F
        const val USAGE_ARROW_LEFT = 0x50

        /**
         * Descriptor de un teclado "boot protocol" estandar: 8 bits de
         * modificadores, un byte reservado y seis slots de teclas.
         *
         * Es el mismo que usa cualquier teclado USB o Bluetooth, asi que
         * Windows, macOS y Linux lo reconocen sin drivers.
         */
        val KEYBOARD_DESCRIPTOR = byteArrayOf(
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            0xA1.toByte(), 0x01,    // Collection (Application)
            0x85.toByte(), 0x01,    //   Report ID (1)
            0x05, 0x07,             //   Usage Page (Keyboard)
            0x19, 0xE0.toByte(),    //   Usage Minimum (Left Control)
            0x29, 0xE7.toByte(),    //   Usage Maximum (Right GUI)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x01,             //   Logical Maximum (1)
            0x75, 0x01,             //   Report Size (1)
            0x95.toByte(), 0x08,    //   Report Count (8)
            0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01,    //   Report Count (1)
            0x75, 0x08,             //   Report Size (8)
            0x81.toByte(), 0x01,    //   Input (Constant) - byte reservado
            0x95.toByte(), 0x06,    //   Report Count (6)
            0x75, 0x08,             //   Report Size (8)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x65,             //   Logical Maximum (101)
            0x05, 0x07,             //   Usage Page (Keyboard)
            0x19, 0x00,             //   Usage Minimum (0)
            0x29, 0x65,             //   Usage Maximum (101)
            0x81.toByte(), 0x00,    //   Input (Data, Array)
            0xC0.toByte(),          // End Collection
        )
    }
}
