"""Arranca el agente sin ventana de consola, con el log en un archivo.

Es el punto de entrada del arranque automatico de Windows: pythonw.exe no abre
consola, asi que la salida tiene que ir a algun lado. Toda la logica vive en
agent.py; esto solo cambia a donde va el log.
"""

from __future__ import annotations

import asyncio
import logging
import os
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import agent  # noqa: E402


def log_path() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "SPenPresenter"
    base.mkdir(parents=True, exist_ok=True)
    return base / "agent.log"


def main() -> None:
    handler = RotatingFileHandler(
        log_path(),
        maxBytes=512 * 1024,
        backupCount=2,
        encoding="utf-8",
    )
    handler.setFormatter(
        logging.Formatter("%(asctime)s  %(message)s", "%Y-%m-%d %H:%M:%S")
    )
    # Configuramos el root ANTES de llamar a agent.main(): su basicConfig no
    # pisa nada si ya hay handlers.
    logging.basicConfig(level=logging.INFO, handlers=[handler])

    sys.argv = ["agent.py"]
    try:
        asyncio.run(agent.main())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
