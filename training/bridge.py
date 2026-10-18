"""Connection to the pvpmost mod (Minecraft server) — lockstep with the game.

Every step: the server sends observations of all bots, we send back actions.
Bots come in pairs: players 2a and 2a+1 fight in arena a.
"""
import socket
import struct
import numpy as np
RAW = 249  # numbers per player (order as in PvpBridge.zapiszObs)
ACT = 10   # forward, strafe, jump, sprint, attack, yaw turn, pitch turn, sneak, slot (0 = no change, 1–9), right button

# observation indices
X, Y, Z, VX, VY, VZ, YAW, PITCH, HP, JEDZENIE, NASYCENIE, PASEK, HURT, NIETYK, ZIEMIA, SPRINT, CELUJE, ZGINAL, ODL, KUCA, CZLOWIEK, BRON, ZBROJA = range(23)   # ZBROJA = armor points (0 or 20)
# items (PvpBridge.zapiszObs, fields 23–49) and the block grid around the player (50–124, 3 levels × 5 × 5 in the player's frame)
(SLOT, TRZYMA, UZYWA, UZYCIE, BLOKUJE, TARCZA_CD, STRZALY, JABLKA, STEKI, BLOKI, ZESTAW, ABSORPCJA, REGEN, HAK,
 SPLAWIK_X, SPLAWIK_Y, SPLAWIK_Z, STRZALA_X, STRZALA_Y, STRZALA_Z, STRZALA_VX, STRZALA_VY, STRZALA_VZ, STRZALA_JEST,
 TRAF_STRZALA, ZABLOKOWANE, POSTAWIONE) = range(23, 50)
VOKSELE = 50                                   # start of the block grid (75 numbers)
# stage 6 (125–248): lava bucket (0 none, 1 with lava, 2 empty), healing potions, potion in slot 8 (kind 1–10, count),
# effects (level 0 = none, seconds) in EFEKTY order, fire, lava, visibility of the arena's players, "out of play",
# terrain map 7x7 (surface height relative to the feet) and lava 7x7 in the player's frame
(WIADRO, LECZENIE, MIKSTURA, MIKSTURA_ILE) = range(125, 129)
EFEKTY = ["sila", "szybkosc", "ogniood", "regen", "trucizna", "spowolnienie", "oslabienie"]
EFEKT = 129                                    # effect k: level EFEKT + 2k, seconds EFEKT + 2k + 1
OGIEN_T, W_LAWIE, PALI_SIE = 143, 144, 145
WIDZI = 146                                    # 146–149: I can see player k of this arena
WYPADL = 150
MAPA_H, MAPA_L, N_MAPA = 151, 200, 49


class Most:
    def __init__(self, areny, tempo=10000.0, port=25599, host="127.0.0.1", n4=0):
        """n4: how many last arenas have 4 players (1v3: player 0 vs 1–3); the rest have 2 players."""
        self.areny, self.n4 = areny, n4
        self.n = 2 * (areny - n4) + 4 * n4
        self.s = socket.create_connection((host, port))
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.s.settimeout(300)   # server silent for 5 min = something hung; replaces the server watchdog (disabled at high tick rates)
        self.s.sendall(struct.pack("<ifi", areny, tempo, n4))
        self._obs_bajty = 4 * RAW * self.n

    def _czytaj(self, ile):
        buf = bytearray(ile)
        widok = memoryview(buf)
        while ile:
            k = self.s.recv_into(widok, ile)
            if k == 0:
                raise ConnectionError("server closed the connection")
            widok = widok[k:]
            ile -= k
        return buf

    def obserwacje(self):
        """-> (tick, array [n, RAW])"""
        tick, n = struct.unpack("<ii", self._czytaj(8))
        assert n == self.n, (n, self.n)
        obs = np.frombuffer(self._czytaj(self._obs_bajty), dtype="<f4").reshape(n, RAW)
        return tick, obs

    def akcje(self, akc, reset=None, bronie=None, przedmioty=None, mikstury=None, cele=None):
        """akc: [n, ACT] float32, reset: [areny] bool (arenas to reset),
        bronie: [n] weapon code for the NEXT round (0 fists, 1 diamond sword, 2 diamond axe;
        with a weapon the mod also puts on full diamond armor, with fists — no armor)
        przedmioty: [n] item-set mask for the next round: 1 bow, 2 food, 4 shield, 8 fishing rod, 16 blocks, 32 lava,
        64 healing potions, 128 potion in slot 8; weapon 255 = player out of play this round
        reset: 0 no, 1 yes, 2 yes + new random terrain, 3 yes + flat
        mikstury: [n] potion kind in slot 8 (1–10), cele: [n] index of the opponent within the arena (0–3)"""
        if reset is None:
            reset = np.zeros(self.areny, dtype=np.uint8)
        if bronie is None:
            bronie = np.zeros(self.n, dtype=np.uint8)
        if przedmioty is None:
            przedmioty = np.zeros(self.n, dtype=np.uint8)
        if mikstury is None:
            mikstury = np.zeros(self.n, dtype=np.uint8)
        if cele is None:
            cele = np.full(self.n, 255, dtype=np.uint8)
        self.s.sendall(np.ascontiguousarray(akc, dtype="<f4").tobytes() + np.asarray(reset, dtype=np.uint8).tobytes()
                       + np.asarray(bronie, dtype=np.uint8).tobytes() + np.asarray(przedmioty, dtype=np.uint8).tobytes()
                       + np.asarray(mikstury, dtype=np.uint8).tobytes() + np.asarray(cele, dtype=np.uint8).tobytes())

    def zamknij(self):
        self.s.close()
