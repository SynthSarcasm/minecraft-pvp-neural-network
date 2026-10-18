"""Live WebSocket feed of the show (port 8771) used by the dashboard, plus an optional (experimental) browser gamepad mode.

HTTP  :8770  -> files from pad_web/ (gamepad page; not shipped in this repository — the show runs with port_http=None)
WS    :8771  -> the show broadcasts the fight state every tick; with control enabled a browser can send gamepad input.

Browser input is turned into EXACTLY the same actions as the network's (3x3 movement, jump, sprint,
attack, sneak, turns from the YAW_PROGI/PITCH_PROGI steps) — so a recording of human play is a ready
imitation lesson, no guessing.
"""
import asyncio
import json
import threading
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
from websockets.asyncio.server import broadcast, serve

from features import PITCH_PROGI, YAW_PROGI
from bridge import PITCH, YAW


class _Strona(SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


class Pad:
    def __init__(self, port_http=8770, port_ws=8771, sterowanie=True):
        """port_http=None: no gamepad page; sterowanie=False: the WS only broadcasts state (e.g. for the panel)."""
        self.sterowanie = sterowanie
        self.zamek = threading.Lock()
        self.ster = {}
        self.kontakt = 0.0
        self.ciosy_zuzyte = 0
        self.klienci = set()
        self.petla = None
        katalog = Path(__file__).parent / "pad_web"
        if port_http is not None:
            http = ThreadingHTTPServer(("0.0.0.0", port_http), partial(_Strona, directory=str(katalog)))
            threading.Thread(target=http.serve_forever, daemon=True, name="pad-http").start()
        threading.Thread(target=self._ws, args=(port_ws,), daemon=True, name="pad-ws").start()

    def _ws(self, port):
        async def glowna():
            self.petla = asyncio.get_running_loop()
            async with serve(self._klient, "0.0.0.0", port, max_size=2 ** 16):
                await asyncio.Future()
        asyncio.run(glowna())

    async def _klient(self, ws):
        self.klienci.add(ws)
        try:
            async for wiad in ws:
                try:
                    d = json.loads(wiad)
                except ValueError:
                    continue
                if d.get("typ") == "sterowanie" and self.sterowanie:
                    with self.zamek:
                        self.ster = d
                        self.kontakt = time.time()
        finally:
            self.klienci.discard(ws)

    def aktywny(self):
        """Someone plays with a gamepad: clicked "join" and sent input within the last 2 s."""
        with self.zamek:
            return bool(self.ster.get("gra")) and time.time() - self.kontakt < 2.0

    def akcja(self, o):
        """Browser input -> action indices [7] for the player with observation o (as for the network)."""
        with self.zamek:
            s = dict(self.ster)
            licznik = int(s.get("cios_licznik", 0))
            if licznik < self.ciosy_zuzyte:          # browser reloaded: the counter starts from zero
                self.ciosy_zuzyte = licznik
            cios = licznik > self.ciosy_zuzyte
            if cios:
                self.ciosy_zuzyte += 1                 # at most one attack per tick, the rest waits in a queue
        p = int(np.clip(round(float(s.get("ruch_p", 0))), -1, 1))
        b = int(np.clip(round(float(s.get("ruch_b", 0))), -1, 1))   # +1 = left (as in Minecraft)
        dyaw = (float(s.get("yaw", o[YAW])) - o[YAW] + 180) % 360 - 180
        dpitch = float(np.clip(float(s.get("pitch", o[PITCH])), -90, 90)) - o[PITCH]
        idx = np.zeros(7, np.int64)
        idx[0] = (p + 1) * 3 + (b + 1)
        idx[1] = bool(s.get("skok"))
        idx[2] = bool(s.get("sprint")) and p > 0
        idx[3] = cios
        idx[4] = bool(s.get("kucanie"))
        idx[5] = int(np.abs(YAW_PROGI - np.clip(dyaw, -45, 45)).argmin())
        idx[6] = int(np.abs(PITCH_PROGI - np.clip(dpitch, -20, 20)).argmin())
        return idx

    def rozeslij(self, dane):
        if self.petla is None or not self.klienci:
            return
        wiad = json.dumps(dane, separators=(",", ":"))
        self.petla.call_soon_threadsafe(broadcast, set(self.klienci), wiad)
