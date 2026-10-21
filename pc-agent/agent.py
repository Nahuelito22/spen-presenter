"""Agente PC de S Pen Presenter.

Escucha comandos JSON por WebSocket y los traduce a pulsaciones de teclas,
de modo que el telefono pueda pasar diapositivas en Prezi, PowerPoint,
Google Slides o un PDF.

Uso:
    python agent.py [--host 0.0.0.0] [--port 8765]
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import secrets
import socket
import sys
import time
from pathlib import Path

import qrcode
from pynput.keyboard import Controller, Key
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8765

# Tipo de servicio mDNS con el que nos anunciamos en la red local.
SERVICE_TYPE = "_spenpresenter._tcp.local."
SERVICE_NAME = "S Pen Presenter"

# Alfabeto sin caracteres que se confunden al tipear: 0/O, 1/I/l.
TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
TOKEN_LENGTH = 8

# Protocolo definido en CLAUDE.md, seccion 5.
COMMANDS: dict[str, object] = {
    "next": Key.right,
    "prev": Key.left,
    "black": "b",
    "exit": Key.esc,
}

log = logging.getLogger("agent")
keyboard = Controller()

# Se completa en main(): necesita que el logging ya este configurado.
TOKEN = ""


def token_path() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "SPenPresenter"
    base.mkdir(parents=True, exist_ok=True)
    return base / "token.txt"


def load_or_create_token() -> str:
    """El codigo de emparejamiento, generado una sola vez y reutilizado.

    Sin esto cualquiera en la misma red puede apretar teclas en esta PC: el
    agente escucha en todas las interfaces y no sabria distinguir al telefono
    de Nahuel de cualquier otro.
    """
    path = token_path()
    if path.exists():
        token = path.read_text(encoding="utf-8").strip()
        if token:
            return token
    token = "".join(secrets.choice(TOKEN_ALPHABET) for _ in range(TOKEN_LENGTH))
    path.write_text(token, encoding="utf-8")
    log.info("codigo de emparejamiento nuevo generado en %s", path)
    return token


def local_ip() -> str:
    """IP de esta PC en la red local (la que usaria para salir a internet)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def print_banner(port: int) -> None:
    if sys.stdout is None:
        return  # corriendo bajo pythonw.exe, sin consola a donde escribir
    url = f"ws://{local_ip()}:{port}"
    print()
    try:
        # La consola de Windows arranca en cp1252 y no puede con los bloques
        # del QR; si tampoco se puede pasar a UTF-8, mostramos solo la URL.
        if getattr(sys.stdout, "encoding", "").lower() not in ("utf-8", "utf8"):
            sys.stdout.reconfigure(encoding="utf-8")
        qr = qrcode.QRCode(border=1)
        qr.add_data(url)
        qr.print_ascii(invert=True)
    except (UnicodeError, AttributeError, OSError):
        print("  (la consola no puede dibujar el QR; usa la direccion de abajo)")
    print(f"  S Pen Presenter - agente escuchando en {url}")
    print(f"  Codigo de emparejamiento: {TOKEN}")
    print("  Cargalo una vez en la app (la IP la encuentra sola por mDNS).")
    print("  Ctrl+C para salir.\n", flush=True)


async def advertise(port: int) -> tuple[AsyncZeroconf, ServiceInfo] | None:
    """Se anuncia por mDNS para que el telefono nos encuentre sin tipear la IP.

    Usa la API asincronica de zeroconf a proposito: la sincronica levanta su
    propio event loop en otro hilo y, llamada desde aca adentro, explota con
    EventLoopBlocked.

    Si falla no es fatal (hay redes y antivirus que bloquean multicast): el
    agente sigue andando y siempre queda la carga manual de la IP.
    """
    ip = local_ip()
    info = ServiceInfo(
        SERVICE_TYPE,
        f"{SERVICE_NAME}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=port,
        properties={"version": "1"},
        server=f"{socket.gethostname()}.local.",
    )
    try:
        azc = AsyncZeroconf()
        await azc.async_register_service(info)
    except (OSError, RuntimeError) as exc:
        log.warning("no se pudo publicar el servicio mDNS: %s", exc)
        return None
    log.info("publicado por mDNS como %s en %s", SERVICE_TYPE, ip)
    return azc, info


def press(cmd: str) -> None:
    key = COMMANDS[cmd]
    keyboard.press(key)
    keyboard.release(key)


async def handle(websocket) -> None:
    """Atiende a un telefono.

    El primer mensaje tiene que ser el de autenticacion; hasta que no llegue y
    sea correcto, no tocamos el teclado. Al que falla lo echamos sin reintentos
    para no volvernos un oraculo de fuerza bruta.
    """
    peer = websocket.remote_address[0] if websocket.remote_address else "?"
    log.info("conectado: %s", peer)
    authenticated = False

    try:
        async for raw in websocket:
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                log.warning("%s envio algo que no es JSON: %r", peer, raw[:80])
                continue

            cmd = msg.get("cmd")

            if cmd == "auth":
                if secrets.compare_digest(str(msg.get("token", "")), TOKEN):
                    authenticated = True
                    await websocket.send(json.dumps({"auth": "ok"}))
                    log.info("%s autenticado", peer)
                else:
                    log.warning("%s mando un codigo incorrecto: lo cierro", peer)
                    await websocket.send(json.dumps({"auth": "error"}))
                    await websocket.close(4401, "codigo invalido")
                    return
                continue

            if not authenticated:
                log.warning("%s mando %r sin autenticarse: lo cierro", peer, cmd)
                await websocket.close(4401, "falta autenticarse")
                return

            if cmd == "ping":
                await websocket.send(json.dumps({"pong": time.time()}))
                continue
            if cmd in COMMANDS:
                press(cmd)
                log.info("%s -> %s", peer, cmd)
            else:
                log.warning("%s envio un comando desconocido: %r", peer, cmd)
    except ConnectionClosed:
        pass
    finally:
        log.info("desconectado: %s", peer)


async def main() -> None:
    parser = argparse.ArgumentParser(description="Agente PC de S Pen Presenter")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s  %(message)s",
        datefmt="%H:%M:%S",
    )
    # websockets loguea cada conexion a nivel INFO; nos alcanza nuestro log.
    logging.getLogger("websockets").setLevel(logging.WARNING)

    global TOKEN
    TOKEN = load_or_create_token()
    log.info("codigo de emparejamiento: %s", TOKEN)

    advertised = await advertise(args.port)
    try:
        async with serve(handle, args.host, args.port):
            print_banner(args.port)
            await asyncio.get_running_loop().create_future()  # correr para siempre
    finally:
        if advertised is not None:
            azc, info = advertised
            await azc.async_unregister_service(info)
            await azc.async_close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nAgente detenido.")
