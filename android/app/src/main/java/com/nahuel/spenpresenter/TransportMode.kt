package com.nahuel.spenpresenter

/** Por donde viajan los comandos hasta la PC. */
enum class TransportMode {

    /** WebSocket por la red local. Necesita el agente corriendo en la PC. */
    LAN,

    /**
     * El telefono se presenta como teclado Bluetooth. No necesita agente ni
     * red, pero depende de que el fabricante exponga el perfil HID Device.
     */
    BLUETOOTH,
}

val TransportMode.label: String
    get() = when (this) {
        TransportMode.LAN -> "WiFi"
        TransportMode.BLUETOOTH -> "Bluetooth"
    }
