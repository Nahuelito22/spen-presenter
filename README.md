# S Pen Presenter

Usá el botón del **S Pen** de tu Galaxy como control remoto para pasar
diapositivas en la PC. Funciona con Canva, PowerPoint, Prezi, Google Slides,
PDF o cualquier cosa que responda a las flechas del teclado.

```
┌──────────┐   Air Actions   ┌──────────────────────┐   WebSocket   ┌──────────────────┐
│  S Pen   │ ──────────────▶ │  App Android         │ ────────────▶ │  Agente Python   │ ──▶ → / ←
└──────────┘   (Bluetooth)   │  MediaSession        │     (LAN)     │  en la PC        │
                             └──────────────────────┘               └──────────────────┘
```

Probado en un **Galaxy S22 Ultra (SM-S908E)** con One UI 8 / Android 16 contra
Windows 11. Latencia medida: **14 ms** sobre WiFi.

---

## El truco: cómo captura los gestos del S Pen

Samsung no expone una API pública para los gestos del S Pen. El *S Pen Remote
SDK* está deprecado hace años, y las "Acciones de aplicaciones" solo funcionan
para apps que Samsung registró explícitamente.

Pero las **acciones multimedia** de Air Actions andan con *cualquier* app que
esté reproduciendo audio. Así que la app:

1. Reproduce **dos segundos de silencio en loop** y pide el foco de audio.
2. Publica una `MediaSession` en estado `PLAYING`.
3. Android entonces le enruta a ella los botones de media del sistema. Se
   puede verificar con `adb shell dumpsys media_session`:

   ```
   Media button session is com.nahuel.spenpresenter/PresenterService
   ```

4. Cada gesto del S Pen llega como un callback (`onSkipToNext` y compañía) y
   se traduce a un comando que viaja por WebSocket a la PC, donde `pynput`
   presiona la tecla.

**De yapa**: declarando la sesión como *remote playback* con un
`VolumeProvider`, los gestos ↑/↓ llegan como `onAdjustVolume` en lugar de
mover el volumen del teléfono. Eso da **seis gestos mapeables en vez de
cuatro**.

---

## Requisitos

| | |
|---|---|
| Teléfono | Galaxy con S Pen y Air Actions (Note 9+, S21 Ultra+, Tab S) — Android 9+ |
| PC | Windows, macOS o Linux con Python 3.11+ |
| Red | Ambos en la misma WiFi |

Para compilar la app: JDK 21 y Android SDK 36.

---

## Instalación

### 1. Agente en la PC

```bash
cd pc-agent
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt   # Linux/macOS: .venv/bin/python
.venv/Scripts/python.exe agent.py
```

Al arrancar muestra un QR, la IP y el **código de emparejamiento**. Anotá el
código: lo cargás una vez en la app.

En Windows hay que abrir el puerto, en PowerShell **como administrador**:

```powershell
New-NetFirewallRule -DisplayName "S Pen Presenter" -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow -Profile Any
```

Opcional, para que arranque solo con Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\install_autostart.ps1
```

¿No querés instalar Python? Compilá un ejecutable independiente con
`build_exe.ps1` y usá `dist/spen-presenter-agent.exe`.

Más detalle en [`pc-agent/README.md`](pc-agent/README.md).

### 2. App en el teléfono

```bash
cd android
./gradlew installDebug      # con el teléfono conectado y depuración USB activa
```

> **No uses el JDK que trae Android Studio.** Su JBR es un JDK 25 y Gradle no
> lo soporta: falla con un críptico `25.0.3`. Usá Temurin 21 y, si abrís el
> proyecto en Studio, cambiale el *Gradle JDK* en los ajustes.

Más detalle en [`android/README.md`](android/README.md).

### 3. Configurar Air Actions — no te saltees esto

En el teléfono: *Ajustes → Funciones avanzadas → S Pen → Acciones aéreas →
Acciones generales en aplicaciones → **Multimedia***.

La configuración recomendada:

| Gesto del S Pen | Acción de Samsung | Comando resultante |
|---|---|---|
| Presionar una vez | Reproducir siguiente | Siguiente diapositiva |
| Presionar dos veces | Reproducir anterior | Diapositiva anterior |
| Gesto → | Reproducir siguiente | Siguiente diapositiva |
| Gesto ← | Reproducir anterior | Diapositiva anterior |
| Gesto ↑ | Subir volumen | Pantalla negra (`B`) |
| Gesto ↓ | Bajar volumen | Salir (`Esc`) |

**Por qué importa**: la app solo ve la *acción de media* que llega, no qué
gesto la produjo. Con la configuración de fábrica (`Presionar una vez` =
Reprod./Pausa y `Presionar dos veces` = Reproducir siguiente), el doble click
y el gesto → son indistinguibles entre sí.

También conviene poner *Mantener presionado botón lápiz* en **No hacer nada**:
por defecto abre la cámara cuando un gesto no se reconoce.

---

## Modo Bluetooth: sin agente y sin red

El teléfono también puede presentarse ante la PC **como un teclado Bluetooth**.
No hace falta instalar nada del otro lado, ni estar en la misma WiFi, ni tocar
el firewall: sirve en la computadora de un aula o de un evento.

Elegí **Bluetooth** en el selector de arriba de la app y tocá Iniciar.

> ⚠️ **El orden del emparejamiento importa.** Si emparejás el teléfono con la
> PC *antes* de arrancar la app en modo Bluetooth, Windows lo registra por sus
> perfiles de audio, cachea esa lista de servicios y **nunca conecta el
> teclado**. Lo vas a ver como un intento de conexión que muere a los ~17
> segundos.
>
> La solución: desvinculá de ambos lados, arrancá la app en modo Bluetooth
> (que registra y anuncia el perfil HID) y recién entonces emparejá desde la
> PC. Windows ahí sí lo descubre como teclado.

En la lista de Bluetooth aparece con el nombre del teléfono, no como "S Pen
Presenter": Android no deja que una app cambie el nombre Bluetooth del
dispositivo.

Comparado con el modo WiFi: no hay medición de latencia (el host HID no
contesta nada) ni código de emparejamiento, porque la seguridad la da el
emparejamiento Bluetooth del sistema.

## Uso

1. Arrancá el agente en la PC (o dejá que arranque solo).
2. Abrí la app y tocá **Buscar PC**: encuentra el agente por mDNS, sin tipear
   la IP. Cargá el código de emparejamiento la primera vez.
3. Tocá **Iniciar**. El badge tiene que ponerse verde: *Conectado - N ms*.
4. Abrí tu presentación y **hacé clic sobre ella** para que tenga el foco del
   teclado.
5. Guardá el teléfono en el bolsillo y presentá con el S Pen.

Los gestos se remapean desde *Configurar mapeo*, en la app.

---

## Cómo son los gestos

No se toca la pantalla. Con el S Pen en el aire:

1. Mantené apretado el botón del lápiz.
2. Sin soltarlo, hacé un movimiento seco de muñeca en una dirección, como
   sacudir un termómetro.
3. Soltá el botón.

El click simple y el doble click salen naturales desde el primer intento; los
gestos direccionales necesitan algo de práctica.

---

## Solución de problemas

| Síntoma | Causa probable |
|---|---|
| **Buscar PC** no encuentra nada | La red filtra multicast (común en aulas y hoteles). Cargá la IP a mano: la app recuerda la última que funcionó |
| Queda en *Conectando* y reintenta | El firewall bloquea el 8765, o la PC está en otra red. Ojo: una regla con perfil `Private` no aplica si Windows marcó la red como **Pública** |
| *Código de emparejamiento incorrecto* | Corré `show_token.ps1` en la PC para ver el código actual |
| Conecta pero no pasa diapositivas | La presentación no tiene el foco del teclado. Hacé clic sobre ella |
| Las teclas de volumen del teléfono dejaron de andar | Es esperado: el `VolumeProvider` se queda esos eventos mientras el servicio corre. Se recupera al detenerlo |
| La música se pausa al iniciar | Es inevitable: hace falta el foco de audio para ganar la sesión de media |
| El servicio se muere solo | Sacá la app de la optimización de batería: *Ajustes → Batería → Límites de uso en segundo plano → Apps que nunca se suspenden* |
| Otra app de música "roba" los botones | Cerrala, o tocá **Detener** e **Iniciar** para recuperar la sesión |

---

## Seguridad

El agente exige un **código de emparejamiento de 8 caracteres** antes de
aceptar cualquier comando; sin él cierra la conexión sin tocar el teclado.

Dicho con todas las letras: **el tráfico viaja sin cifrar** y el agente
escucha en todas las interfaces. Esto protege del curioso en la misma WiFi, no
de alguien decidido. Es un programa que presiona teclas en tu máquina a pedido
de la red: no lo dejes corriendo en una red pública si no lo estás usando.

---

## Estructura

```
spen-presenter/
├── android/                     app Kotlin + Compose
│   └── app/src/main/java/.../
│       ├── PresenterService.kt  foreground service + MediaSession
│       ├── SilentPlayer.kt      el audio silencioso que nos da prioridad
│       ├── Discovery.kt         mDNS con NsdManager
│       ├── Mapping.kt           gesto → comando
│       └── transport/           WebSocket (y, eventualmente, Bluetooth HID)
├── pc-agent/                    agente Python
│   ├── agent.py                 servidor WebSocket + mDNS + emparejamiento
│   ├── run_agent.pyw            arranque sin consola
│   └── test_client.py           simula el teléfono
└── CLAUDE.md                    plan de trabajo y bitácora de pruebas
```

El protocolo es JSON sobre WebSocket, puerto 8765:

```json
{"cmd": "auth", "token": "ABCD1234"}
{"cmd": "next"}    {"cmd": "prev"}    {"cmd": "black"}    {"cmd": "exit"}
{"cmd": "ping"}
```

El agente responde `{"auth": "ok"}` al emparejar y `{"pong": <timestamp>}` a
cada ping.

---

## Estado

Funcionando y usado en presentaciones reales. Lo que falta:

- [ ] Escaneo del QR como alternativa a mDNS.

El historial de decisiones y el registro de pruebas están en
[`CLAUDE.md`](CLAUDE.md).

## Licencia

MIT — ver [`LICENSE`](LICENSE).
