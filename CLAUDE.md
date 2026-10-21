# S Pen Presenter — Plan de trabajo para Claude Code

> Objetivo: usar el botón del **S Pen del Galaxy S22 Ultra** como control remoto para pasar diapositivas en la PC (Prezi, PowerPoint, Google Slides, PDF).
> Este archivo es la guía del proyecto. Claude Code debe leerlo al empezar cada sesión y trabajar **fase por fase**, deteniéndose al final de cada una para que Nahuel pruebe en el dispositivo real.

---

## 1. Idea en una línea

S Pen (botón / gestos) → Air Actions de Samsung → **MediaSession** de nuestra app Android → señal a la PC (red local o Bluetooth HID) → la PC presiona **→ / ←**.

```
┌──────────┐  BT (Samsung)  ┌──────────────────────────┐   WebSocket (LAN)   ┌──────────────────┐
│  S Pen   │ ─────────────▶ │ App Android (S22 Ultra)  │ ──────────────────▶ │ Agente PC (Python)│ ──▶ tecla → / ←
└──────────┘  Air Actions   │ MediaSession + Service   │   o Bluetooth HID   └──────────────────┘
                            └──────────────────────────┘ ─────(Fase 4)─────▶ PC lo ve como teclado
```

---

## 2. Reglas de trabajo (para Claude Code)

- Trabajar **una fase a la vez**, con una lista de tareas acordada al inicio de cada sesión.
- **Al terminar cada fase: frenar** y pedirle a Nahuel que ejecute las pruebas de la sección "Prueba" antes de seguir.
- Mostrar los cambios para revisión; **no hacer commit ni push**: Nahuel commitea y pushea manualmente.
- Proponer **commits de un solo propósito** (sugerir el mensaje, no ejecutarlo).
- Nada de credenciales, tokens o claves en el código ni en el chat. Si algo lo requiere, dejar un placeholder y avisar.
- Si una prueba falla, registrar el resultado en la **Bitácora de pruebas** (sección 8) antes de cambiar de enfoque.

---

## 2.1 Dispositivo de prueba

Galaxy S22 Ultra **SM-S908E** — One UI 8.0, **Android 16** (API 36).
Implica: `targetSdk 36`, tipos de foreground service obligatorios (`mediaPlayback`)
y permiso `POST_NOTIFICATIONS` en runtime.

---

## 3. Stack

| Parte | Tecnología |
|---|---|
| App Android | Kotlin, minSdk 28 (Android 9, necesario para `BluetoothHidDevice`), targetSdk actual, Jetpack Compose para la UI mínima |
| Captura S Pen | `MediaSessionCompat` (androidx.media) + `ForegroundService` + audio silencioso en loop (`ExoPlayer`/`MediaPlayer`) |
| Transporte LAN | WebSocket (OkHttp en Android) |
| Agente PC | Python 3.11+, `websockets`, `pynput` (simular teclas), `qrcode` (mostrar IP) |
| Transporte BT (Fase 4) | `BluetoothHidDevice` con descriptor HID de teclado |

---

## 4. Estructura del repo

```
spen-presenter/
├── README.md            ← puerta de entrada para cualquiera
├── CLAUDE.md            ← este archivo: plan de trabajo y bitácora
├── android/             ← proyecto Android Studio (Kotlin)
│   └── app/src/main/java/.../
│       ├── MainActivity.kt         (UI Compose)
│       ├── PresenterService.kt     (foreground service + MediaSession)
│       ├── SilentPlayer.kt         (mantiene la sesión como "activa")
│       ├── Discovery.kt            (mDNS con NsdManager)
│       ├── Settings.kt             (IP, código y mapeo persistidos)
│       ├── EventLog.kt             (puente servicio ↔ UI)
│       ├── transport/
│       │   ├── Transport.kt        (interfaz: send(cmd))
│       │   ├── WebSocketTransport.kt
│       │   └── HidTransport.kt     (Fase 4, todavía no existe)
│       └── Mapping.kt              (evento S Pen → comando)
└── pc-agent/
    ├── agent.py                    (WebSocket + mDNS + emparejamiento)
    ├── run_agent.pyw               (arranque sin consola)
    ├── install_autostart.ps1       (y uninstall_autostart.ps1)
    ├── show_token.ps1              (muestra el código de emparejamiento)
    ├── requirements.txt
    └── test_client.py              (simula el teléfono)
```

---

## 5. Protocolo (LAN)

Mensajes JSON por WebSocket, puerto por defecto **8765**:

```json
{ "cmd": "next" }      // → flecha derecha
{ "cmd": "prev" }      // → flecha izquierda
{ "cmd": "black" }     // → tecla B (pantalla negra)
{ "cmd": "exit" }      // → Esc
{ "cmd": "ping" }      // ← el agente responde { "pong": <timestamp> }
```

Mapeo inicial sugerido (ajustable en `Mapping.kt`):

| Evento S Pen (vía Air Actions → media) | Comando |
|---|---|
| Click simple (play/pause o next) | `next` |
| Doble click / gesto izquierda (previous) | `prev` |
| Gesto derecha (next) | `next` |
| Otros (si se pueden capturar) | `black` / `exit` |

---

## 6. Fases

### Fase 0 — Validación manual (sin código)
**Objetivo:** confirmar que el enfoque es viable en este teléfono antes de programar.

- [ ] Abrir Spotify/YouTube Music, reproducir algo y verificar que **el botón del S Pen** hace play/pausa y que **los gestos** pasan de pista.
- [ ] En *Ajustes → Funciones avanzadas → S Pen → Air Actions*, anotar qué acciones de media hay y si se pueden reconfigurar.
- [ ] Instalar una app de "Bluetooth Keyboard" (perfil HID) y comprobar si la PC reconoce al S22 como teclado.
- [ ] Investigar el estado actual del **S Pen Remote SDK** de Samsung (¿sigue disponible y funciona en One UI actual?).

**Prueba / criterio de salida:** sabemos qué eventos llegan (play/pause, next, previous) y si HID es posible. Registrar en la bitácora.

---

### Fase 1 — Agente PC
**Objetivo:** la PC recibe comandos y presiona teclas.

- [ ] `agent.py`: servidor WebSocket en `0.0.0.0:8765`, traduce `cmd` → tecla con `pynput`.
- [ ] Al iniciar, mostrar en consola la IP local + un **QR** con `ws://IP:8765`.
- [ ] Log de cada comando con timestamp.
- [ ] `test_client.py`: envía `next`/`prev` desde la misma PC o desde otra máquina.

**Prueba:** abrir una presentación en Prezi y en PowerPoint, ejecutar `test_client.py` y verificar que avanza y retrocede. Probar también desde otro dispositivo en la misma red (firewall de Windows).

---

### Fase 2 — App Android: captura del S Pen (solo logs)
**Objetivo:** demostrar que nuestra app recibe los eventos del S Pen.

- [ ] `PresenterService` como foreground service con notificación persistente.
- [ ] `MediaSessionCompat` activa con callbacks `onPlay`, `onPause`, `onSkipToNext`, `onSkipToPrevious`.
- [ ] `SilentPlayer` reproduce audio silencioso en loop para que Android enrute los controles de media a nuestra sesión.
- [ ] UI mínima: botón Iniciar/Detener y una lista con los últimos eventos recibidos.
- [ ] Vibración corta al recibir cada evento (feedback).

**Prueba:** con la app activa y la pantalla apagada o en otra app, tocar el S Pen y hacer gestos. Verificar que los eventos aparecen en la lista. Probar durante 15 minutos seguidos (que el sistema no mate el servicio). Revisar las restricciones de batería de Samsung.

---

### Fase 3 — Conexión Android → PC (LAN)
**Objetivo:** el S Pen pasa diapositivas de verdad.

- [ ] `WebSocketTransport` con reconexión automática.
- [ ] Ingreso de la IP: escaneo del QR del agente (CameraX + ML Kit) o carga manual.
- [ ] Indicador de estado de conexión en la UI y en la notificación.
- [ ] Medir latencia con `ping`/`pong` y mostrarla.

**Prueba:** presentación real de 10 diapositivas en Prezi, avanzando y retrocediendo solo con el S Pen. Objetivo: latencia < 150 ms y cero eventos perdidos o duplicados.

---

### Fase 4 — Modo Bluetooth HID (opcional, si la Fase 0 lo validó)
**Objetivo:** que funcione sin instalar nada en la PC.

- [ ] `HidTransport` con `BluetoothHidDevice` y un descriptor de teclado.
- [ ] Flujo de emparejamiento desde la app.
- [ ] Selector de modo en la UI: LAN / Bluetooth.

**Prueba:** emparejar con la PC, abrir Prezi y pasar diapositivas sin el agente corriendo. Probar también en una segunda PC (por ejemplo, la de un aula o evento).

---

### Fase 5 — Pulido
- [x] Mapeo configurable desde la UI. *(2026-10-21)*
- [x] Descubrimiento automático del agente vía mDNS/NSD (sin QR). *(2026-10-21)*
- [x] Inicio automático del agente con Windows. *(2026-10-21)*
- [x] Token compartido para que no cualquiera en la red pueda apretar teclas. *(2026-10-21)*
- [ ] Escaneo del QR como alternativa a mDNS.
- [x] README de uso para cualquier persona. *(2026-10-21)*
- [x] Ícono propio de la app y de la notificación. *(2026-10-21)*
- [x] Empaquetado del agente como `.exe` con PyInstaller. *(2026-10-21)*

---

## 7. Riesgos conocidos

| Riesgo | Mitigación |
|---|---|
| ~~Air Actions no envía eventos de media a apps "no reproductoras"~~ | **Descartado 2026-10-20**: con audio silencioso y estado `PLAYING` ganamos la sesión de botones de media |
| One UI mata el foreground service | Excluir la app de la optimización de batería y documentarlo en el README |
| Otra app de música roba la MediaSession | Avisar en la UI si perdemos el foco de audio |
| El firewall de Windows bloquea el puerto 8765 | El agente explica cómo permitirlo, o usar el modo HID |
| Samsung limita el perfil HID | Quedarse con el modo LAN |
| El click simple solo emite play/pause | Mapear play **y** pause a `next` |
| El JBR de Android Studio (JDK 25) rompe Gradle | Compilar con Temurin 21, ya instalado como `JAVA_HOME` |
| Con el servicio activo las teclas de volumen no funcionan | Es consecuencia del `VolumeProvider`; se puede quitar y perder 2 gestos |
| Android bloquea el tráfico en texto plano (API 28+) | `network_security_config.xml` con `cleartextTrafficPermitted` |
| ~~Cualquiera en la misma red puede apretar teclas en la PC~~ | **Mitigado 2026-10-21**: código de emparejamiento de 8 caracteres; sin él el agente cierra la conexión. El tráfico sigue sin cifrar |

---

## 8. Bitácora de pruebas

| Fecha | Fase | Qué se probó | Resultado | Notas |
|---|---|---|---|---|
| 2026-10-20 | 0 | Botón S Pen controla Spotify | ✅ OK | Click simple = Reprod./Pausa, doble click = Reproducir siguiente |
| 2026-10-20 | 0 | Gestos S Pen pasan pista | ✅ OK | ← anterior, → siguiente, ↑ subir volumen, ↓ bajar volumen |
| 2026-10-20 | 0 | Air Actions reconfigurable | ✅ OK | Sección "Acciones generales en aplicaciones → Multimedia", todas las acciones son editables. Funciona en cualquier app multimedia sin necesidad de "acciones de aplicaciones" |
|  | 0 | S22 como teclado BT (HID) | ⏳ pendiente | Decide si la Fase 4 existe |
|  | 0 | Estado del S Pen Remote SDK | ⏳ pendiente | No bloquea: la vía MediaSession ya quedó validada |
| 2026-10-20 | 1 | Agente PC: arranque, QR, ping/pong | ✅ OK | RTT 1 ms en localhost. Bug corregido: consola cp1252 no podía dibujar el QR |
| 2026-10-20 | 2 | Toolchain Android + build del APK | ✅ OK | Android Studio + Temurin 21 + SDK 36. `assembleDebug` compila (23,5 MB). Ojo: el JBR de Studio es JDK 25 y Gradle 8.13 no lo soporta |
| 2026-10-20 | 2 | APK instalado en el S22 (SM-S908E) | ✅ OK | Vía adb. Android 16 / API 36 |
| 2026-10-20 | 2 | Foreground service + MediaSession | ✅ OK | `isForeground=true`, `types=mediaPlayback`, `state=PLAYING` |
| 2026-10-20 | 2 | Ganamos los botones de media del sistema | ✅ OK | `Media button session is com.nahuel.spenpresenter` |
| 2026-10-20 | 2 | Los 5 eventos simulados por adb | ✅ OK | PLAY_PAUSE→next, NEXT→next, PREVIOUS→prev, VOLUME_UP→black, VOLUME_DOWN→exit |
| 2026-10-20 | 2 | Eventos del **S Pen físico** | ✅ OK | Los 6 eventos, uno por gesto, sin duplicados ni pérdidas. Click y doble click salen naturales; los gestos requieren práctica |
|  | 2 | Servicio sobrevive 15 min | ⏳ pendiente | Revisar restricciones de batería de Samsung |
| 2026-10-20 | 2 | Gestos ↑/↓ vía VolumeProvider | ✅ OK | Confirmado con el S Pen real: Air Actions manda la tecla y la intercepta nuestra sesión. Tenemos 6 gestos, no 4 |
|  | 1 | Conexión desde otra PC de la red | ⏳ pendiente | Requiere la regla de firewall del README |
| 2026-10-21 | 5 | Agente se anuncia por mDNS | ✅ OK | `_spenpresenter._tcp` descubierto desde otro proceso. Bug corregido: la API sincrónica de zeroconf explota dentro de un loop asyncio (`EventLoopBlocked`) |
| 2026-10-21 | 5 | Arranque automático sin consola | ✅ OK | Acceso directo en Inicio + `run_agent.pyw` con pythonw. Escucha, publica mDNS y responde ping en 1 ms. Log en `%LOCALAPPDATA%\SPenPresenter\agent.log` |
| 2026-10-21 | 5 | **Descubrimiento mDNS desde el teléfono** | ✅ OK | Sin IP guardada: encontrado en **60 ms**, conectado 120 ms después de tocar Iniciar, sin tipear nada |
| 2026-10-21 | 5 | Comandos con el agente en modo automático | ✅ OK | `next` y `prev` llegaron al agente silencioso |
| 2026-10-21 | 5 | Layout de los 4 botones | ✅ OK | Bug corregido: "Limpiar" quedaba fuera de pantalla. Ahora usa `FlowRow` y envuelve |
| 2026-10-21 | 5 | **Arranque automático tras reiniciar** | ✅ OK | Post-reinicio: agente escuchando en 8765 y conexión ESTABLISHED con el teléfono sin intervención |
| 2026-10-21 | 5 | Autenticación del agente | ✅ OK | Código correcto autentica; incorrecto y sin autenticar cierran con `4401` sin tocar el teclado |
| 2026-10-21 | 5 | Emparejamiento desde el teléfono | ✅ OK | Código rechazado → la app deja de reintentar y lo dice; código correcto → conectado. Persiste entre reinicios |
| 2026-10-21 | 5 | Mapeo configurable | ✅ OK | Cambiado "click simple" a Pantalla negra: el agente recibió `black`. "Restablecer" vuelve a los valores de fábrica |
| 2026-10-21 | 5 | Ejecutable independiente | ✅ OK | 11 MB con PyInstaller. Publica mDNS, muestra el código y autentica igual que el script |
| 2026-10-21 | 5 | Arranque automático con el `.exe` | ✅ OK | El acceso directo apunta al ejecutable, ventana minimizada. Bug corregido: el script quedó imprimiendo variables vacías tras un refactor |
|  | 5 | Ícono nuevo en el teléfono | ⏳ pendiente | Compila; falta verlo instalado |
| 2026-10-20 | 3 | Regla de firewall para el puerto 8765 | ✅ OK | Creada como "S Pen Presenter". La red de casa figura como **Pública**, así que la regla quedó con perfil `Any` |
| 2026-10-20 | 3 | WebSocket teléfono → PC | ✅ OK | 192.168.1.181 → 192.168.1.171. Bug corregido: Android bloquea cleartext, hizo falta `network_security_config` |
| 2026-10-20 | 3 | Comandos de punta a punta | ✅ OK | `next`, `prev`, `next` simulados llegaron al agente y movieron el foco |
| 2026-10-20 | 3 | Latencia ping/pong | ✅ OK | **14 ms** sobre WiFi, muy por debajo del objetivo de 150 ms |
| 2026-10-20 | 3 | Reconexión automática | ✅ OK | Backoff observado: 2 s, 4 s... mientras el cleartext estaba bloqueado |
| 2026-10-21 | 3 | **Presentación real (Canva)** | ✅ OK | 23 comandos (21 next, 2 prev) en ~2 min, sin desconexiones ni duplicados. "Funciona excelente" |
| 2026-10-21 | 5 | Uso real tras el reinicio | ✅ OK | "Funcionó excelente". Repo publicado en GitHub |
|  | 3 | Gestos ↑/↓ contra una presentación real | ⏳ pendiente | No se probaron: no llegó ningún `black` ni `exit` |
|  | 1 | Prezi / PowerPoint específicamente | ⏳ pendiente | El mecanismo ya quedó probado con Canva; son las mismas teclas |
