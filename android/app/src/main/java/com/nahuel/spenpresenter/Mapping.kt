package com.nahuel.spenpresenter

/**
 * Los eventos que el S Pen nos puede entregar a traves de Air Actions.
 *
 * Samsung no nos habla directo: manda acciones de media y nosotros las
 * recibimos como callbacks de la MediaSession. Con la configuracion de Air
 * Actions del S22 de Nahuel (2026-10-20) la correspondencia es:
 *
 *   Click simple   -> Reproducir siguiente -> [NEXT]
 *   Doble click    -> Reproducir anterior  -> [PREVIOUS]
 *   Gesto <-       -> Reproducir anterior  -> [PREVIOUS]
 *   Gesto ->       -> Reproducir siguiente -> [NEXT]
 *   Gesto arriba   -> Subir volumen        -> [VOLUME_UP]
 *   Gesto abajo    -> Bajar volumen        -> [VOLUME_DOWN]
 *
 * Esa configuracion vive del lado de Samsung, no nuestro:
 * Ajustes -> Funciones avanzadas -> S Pen -> Acciones aereas -> Multimedia.
 * Si alguna vez vuelve al default, el click simple pasa a emitir
 * [PLAY_PAUSE] y hay que acordarse de que Android alterna play y pause en
 * clicks sucesivos: por eso ese evento tambien avanza.
 */
enum class PenEvent {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    VOLUME_UP,
    VOLUME_DOWN,
}

/** Comandos del protocolo con el agente de la PC (CLAUDE.md, seccion 5). */
enum class Command(val wire: String) {
    NEXT("next"),
    PREV("prev"),
    BLACK("black"),
    EXIT("exit"),
}

/**
 * Traduce un evento del S Pen al comando que se le manda a la PC.
 *
 * [DEFAULTS] es el mapeo de fabrica; Nahuel puede cambiarlo desde la UI y lo
 * que elija se guarda en [Settings].
 *
 * El click simple y el doble click dependen de como este configurado Air
 * Actions del lado de Samsung (ver arriba): nosotros solo vemos el evento de
 * media que llega, no que gesto lo produjo.
 */
object Mapping {

    val DEFAULTS: Map<PenEvent, Command> = mapOf(
        // Android alterna onPlay y onPause en clicks sucesivos, asi que los
        // dos tienen que hacer lo mismo o se perderia uno de cada dos.
        PenEvent.PLAY_PAUSE to Command.NEXT,
        PenEvent.NEXT to Command.NEXT,
        PenEvent.PREVIOUS to Command.PREV,
        PenEvent.VOLUME_UP to Command.BLACK,
        PenEvent.VOLUME_DOWN to Command.EXIT,
    )

    fun toCommand(
        event: PenEvent,
        mapping: Map<PenEvent, Command> = DEFAULTS,
    ): Command = mapping[event] ?: DEFAULTS.getValue(event)
}

/** Como mostrar cada evento y cada comando en la UI. */
val PenEvent.label: String
    get() = when (this) {
        PenEvent.PLAY_PAUSE -> "Click (play/pausa)"
        PenEvent.NEXT -> "Click simple / gesto →"
        PenEvent.PREVIOUS -> "Doble click / gesto ←"
        PenEvent.VOLUME_UP -> "Gesto ↑"
        PenEvent.VOLUME_DOWN -> "Gesto ↓"
    }

val Command.label: String
    get() = when (this) {
        Command.NEXT -> "Siguiente"
        Command.PREV -> "Anterior"
        Command.BLACK -> "Pantalla negra"
        Command.EXIT -> "Salir"
    }
