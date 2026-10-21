package com.nahuel.spenpresenter

/** Por donde viajan los comandos hasta la PC. */
enum class TransportMode {

    /**
     * Prueba Bluetooth primero y, si no engancha, cae a WiFi.
     *
     * Es el default: evita tener que acordarse de cambiar el modo antes de
     * presentar, que es justo cuando uno no quiere estar tocando ajustes.
     */
    AUTO,

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
        TransportMode.AUTO -> "Automatico"
        TransportMode.LAN -> "WiFi"
        TransportMode.BLUETOOTH -> "Bluetooth"
    }
