# Agente PC — S Pen Presenter (Fase 1)

Recibe comandos JSON por WebSocket y los convierte en pulsaciones de teclas.

## Instalacion (una sola vez)

```powershell
cd pc-agent
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

## Correr el agente

```powershell
.\.venv\Scripts\python.exe agent.py
```

Muestra un QR con `ws://<IP-local>:8765` y loguea cada comando recibido.
Opciones: `--host`, `--port`.

## Probar sin el telefono

```powershell
# en otra terminal, misma PC
.\.venv\Scripts\python.exe test_client.py ping

# 3 segundos para que pongas el foco en la presentacion, y despues avanza
.\.venv\Scripts\python.exe test_client.py --delay 3 next next prev

# desde otra maquina de la red
.\.venv\Scripts\python.exe test_client.py --host 192.168.1.171 next
```

## Arranque automático con Windows

```powershell
cd pc-agent
powershell -ExecutionPolicy Bypass -File .\install_autostart.ps1
```

Crea un acceso directo en la carpeta de Inicio del usuario — **no hace falta
ser administrador**. A partir del próximo inicio de sesión el agente arranca
solo, sin ventana de consola, y escribe su log en:

```
%LOCALAPPDATA%\SPenPresenter\agent.log
```

Para arrancarlo ya mismo sin cerrar sesión, el script imprime el comando al
terminar. Para desactivarlo:

```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall_autostart.ps1
```

> El agente corre con `pythonw.exe`, que no abre consola. Si querés verlo en
> una ventana (para depurar), usá `agent.py` directamente como antes.

## Descubrimiento automático (mDNS)

El agente se anuncia en la red local como `_spenpresenter._tcp`, así que la
app lo encuentra sola con el botón **Buscar PC** y no hace falta tipear la IP.

Si el router filtra multicast — pasa en redes de hoteles, aulas y oficinas —
el descubrimiento no va a encontrar nada y hay que cargar la IP a mano. La app
guarda la última que funcionó, así que en una red conocida arranca sola igual.

## Código de emparejamiento

El agente escucha en todas las interfaces de red, así que sin autenticación
**cualquiera en la misma WiFi podría apretar teclas en esta PC**. Por eso la
primera vez genera un código de 8 caracteres y lo guarda en:

```
%LOCALAPPDATA%\SPenPresenter\token.txt
```

El teléfono tiene que mandarlo al conectarse; si no, el agente lo echa sin
procesar un solo comando. Para verlo (hace falta porque en modo automático no
hay consola):

```powershell
powershell -ExecutionPolicy Bypass -File .\show_token.ps1
```

Se carga **una sola vez** en la app, en el campo *Código de emparejamiento*, y
queda guardado. Para invalidarlo y desconectar todo lo emparejado, borrá
`token.txt` y reiniciá el agente.

> Esto protege contra el vecino curioso en la misma red, no contra alguien
> decidido: el tráfico sigue viajando sin cifrar. Para lo que hace —mandar
> `next` y `prev`— es la protección proporcionada al riesgo.

## Comandos

| cmd | Tecla |
|---|---|
| `next` | Flecha derecha |
| `prev` | Flecha izquierda |
| `black` | `B` (pantalla negra) |
| `exit` | `Esc` |
| `ping` | responde `{"pong": <timestamp>}` |

## Firewall de Windows

Para que entren conexiones desde el telefono hay que permitir el puerto.
En PowerShell **como administrador**:

```powershell
New-NetFirewallRule -DisplayName "S Pen Presenter" -Direction Inbound `
  -Protocol TCP -LocalPort 8765 -Action Allow -Profile Private
```

Para quitarla despues: `Remove-NetFirewallRule -DisplayName "S Pen Presenter"`.

> Nota: `-Profile Private` limita la regla a redes marcadas como privadas.
> Si la red del evento figura como publica, hay que usar `-Profile Any`.
