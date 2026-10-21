"""Simula el telefono para probar el agente sin la app Android.

Ejemplos:
    python test_client.py                        # ping + next/prev de ida y vuelta
    python test_client.py next next prev         # secuencia a mano
    python test_client.py --host 192.168.0.12 next
    python test_client.py --delay 3 next        # 3 s para cambiar de ventana
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import time
from pathlib import Path

import websockets

DEFAULT_SEQUENCE = ["ping", "next", "next", "prev", "ping"]


def local_token() -> str:
    """El codigo que genero el agente en esta misma PC, si existe."""
    path = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "SPenPresenter" / "token.txt"
    return path.read_text(encoding="utf-8").strip() if path.exists() else ""


async def run(
    host: str,
    port: int,
    commands: list[str],
    delay: float,
    gap: float,
    token: str,
) -> None:
    url = f"ws://{host}:{port}"
    print(f"conectando a {url} ...")
    async with websockets.connect(url) as ws:
        await ws.send(json.dumps({"cmd": "auth", "token": token}))
        reply = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
        if reply.get("auth") != "ok":
            print("el agente rechazo el codigo de emparejamiento")
            return
        print("conectado y autenticado")
        if delay:
            print(f"esperando {delay} s (pone el foco en la presentacion)")
            await asyncio.sleep(delay)

        for cmd in commands:
            sent = time.perf_counter()
            await ws.send(json.dumps({"cmd": cmd}))
            if cmd == "ping":
                reply = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
                rtt = (time.perf_counter() - sent) * 1000
                print(f"  ping -> pong {reply.get('pong')}  ({rtt:.0f} ms)")
            else:
                print(f"  {cmd} enviado")
            await asyncio.sleep(gap)
    print("listo")


def main() -> None:
    parser = argparse.ArgumentParser(description="Cliente de prueba del agente")
    parser.add_argument("commands", nargs="*", default=None,
                        help="next / prev / black / exit / ping")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--delay", type=float, default=0,
                        help="segundos de espera antes del primer comando")
    parser.add_argument("--gap", type=float, default=0.8,
                        help="segundos entre comandos")
    parser.add_argument("--token", default=None,
                        help="codigo de emparejamiento (por defecto, el de esta PC)")
    args = parser.parse_args()

    commands = args.commands or DEFAULT_SEQUENCE
    try:
        token = args.token if args.token is not None else local_token()
        asyncio.run(run(args.host, args.port, commands, args.delay, args.gap, token))
    except OSError as exc:
        print(f"no se pudo conectar: {exc}")
    except KeyboardInterrupt:
        print("\ncancelado")


if __name__ == "__main__":
    main()
