"""Rule-based bots. BotV2 is the imitation teacher and the fixed yardstick; it picks actions from the same
discrete steps as the network (features.RUCH / YAW_PROGI / PITCH_PROGI), so it can be imitated 1:1.

BotV2 aims at the torso, keeps attack distance, strafes, attacks with a full bar, W-taps, and in half of the rounds
goes for crits. Its style is drawn every round.
"""
import numpy as np
from features import PITCH_PROGI, YAW_PROGI
from bridge import CELUJE, ODL, PASEK, PITCH, VY, X, Y, YAW, Z, ZIEMIA


def _najblizszy(progi, wartosc):
    return np.abs(progi[None, :] - wartosc[:, None]).argmin(1)


class BotV2:
    def __init__(self, n, rng=None):
        self.n = n
        self.rng = rng or np.random.default_rng()
        self.krytyki = np.zeros(n, bool)
        self.strafe_wl = np.ones(n, bool)
        self.szum = np.ones(n, np.float32)
        self.prog_paska = np.ones(n, np.float32)
        self.dystans = np.full(n, 2.8, np.float32)
        self.kierunek = np.ones(n, np.float32)
        self.zmiana_za = np.zeros(n, np.int64)
        self.wtap = np.zeros(n, np.int64)
        self.nowa_walka(np.ones(n, bool))

    def nowa_walka(self, maska):
        k = int(maska.sum())
        if k == 0:
            return
        r = self.rng
        self.krytyki[maska] = r.random(k) < 0.5
        self.strafe_wl[maska] = r.random(k) < 0.8
        self.szum[maska] = r.uniform(0.5, 4.0, k)
        self.prog_paska[maska] = r.uniform(0.9, 1.0, k)
        self.dystans[maska] = r.uniform(2.4, 3.0, k)
        self.kierunek[maska] = r.choice([-1.0, 1.0], k)
        self.zmiana_za[maska] = r.integers(15, 40, k)
        self.wtap[maska] = 0

    def __call__(self, obs, cel=None):
        """cel: [n] opponent index of every player (Sedzia.cel); default: pair partner i ^ 1."""
        n, r = self.n, self.rng
        ja, on = obs, obs[np.arange(n) ^ 1 if cel is None else cel]
        dx, dz = on[:, X] - ja[:, X], on[:, Z] - ja[:, Z]
        poziom = np.hypot(dx, dz) + 1e-6
        odl = ja[:, ODL]
        # aiming: yaw at the opponent, pitch at the torso (feet + 1.0) from eye height (feet + 1.62)
        chce_yaw = np.degrees(np.arctan2(-dx, dz))
        blad_yaw = (chce_yaw - ja[:, YAW] + 180) % 360 - 180 + r.normal(0, 1, n) * self.szum
        dy = (on[:, Y] + 1.0) - (ja[:, Y] + 1.62)
        chce_pitch = -np.degrees(np.arctan2(dy, poziom))
        blad_pitch = chce_pitch - ja[:, PITCH] + r.normal(0, 1, n) * self.szum * 0.5

        blisko = odl < 4.0
        przod = np.where(odl > self.dystans + 0.4, 1.0, np.where(odl < self.dystans - 0.8, -1.0, 1.0))
        sprint = (przod > 0) & ~(self.krytyki & blisko)               # a crit does not work while sprinting
        self.zmiana_za -= 1
        zmien = self.zmiana_za <= 0
        self.kierunek[zmien] *= -1
        self.zmiana_za[zmien] = r.integers(15, 40, int(zmien.sum()))
        bok = np.where(blisko & self.strafe_wl, self.kierunek, 0.0)
        # lava right ahead: sidestep
        from bridge import MAPA_L
        lawa_przed = ja[:, MAPA_L + 4 * 7 + 3] + ja[:, MAPA_L + 5 * 7 + 3] + ja[:, MAPA_L + 4 * 7 + 2] + ja[:, MAPA_L + 4 * 7 + 4] > 0.5
        przod = np.where(lawa_przed & (przod > 0), 0.0, przod)
        bok = np.where(lawa_przed, self.kierunek, bok)
        # W-tap: after a hit 2 ticks without forward and sprint
        w_trakcie = self.wtap > 0
        przod = np.where(w_trakcie, 0.0, przod)
        sprint = sprint & ~w_trakcie
        self.wtap = np.maximum(0, self.wtap - 1)

        celuje = ja[:, CELUJE] > 0.5
        naladowany = ja[:, PASEK] >= self.prog_paska
        na_ziemi = ja[:, ZIEMIA] > 0.5
        spada = (~na_ziemi) & (ja[:, VY] < -0.05)
        # crits: jump when the opponent is in range and the bar is almost full; hit only while falling
        skok = self.krytyki & na_ziemi & (odl < 3.6) & (ja[:, PASEK] > 0.75)
        cios = celuje & naladowany & np.where(self.krytyki, spada | (odl < 2.0), True)
        self.wtap[cios & ~self.krytyki] = 2

        idx = np.zeros((n, 9), np.int64)
        idx[:, 0] = (przod.astype(np.int64) + 1) * 3 + (bok.astype(np.int64) + 1)
        idx[:, 1] = skok
        idx[:, 2] = sprint
        idx[:, 3] = cios
        idx[:, 4] = 0
        idx[:, 5] = _najblizszy(YAW_PROGI, np.clip(blad_yaw, -45, 45))
        idx[:, 6] = _najblizszy(PITCH_PROGI, np.clip(blad_pitch, -20, 20))
        return idx


class BotPrzedmioty:
    """Item teacher: fights like BotV2 and uses items like a sensible player (eat when low and far, bow beyond
    5.5 blocks, shield against a charged attack, lava at the opponent's feet from 1.8-4 blocks, healing below 9 HP,
    offensive potions at the opponent's feet from 4+ blocks, buff potions away from the opponent).

    Returns indices [n, 9] like the network. `prawy` is the current right-button state (Sedzia.prawy); `tryb` is the
    current mode, for statistics.
    """

    UMIEJETNOSCI = ("jedzenie", "luk", "wedka", "tarcza", "bloki", "lawa", "leczenie", "mikstura")

    def __init__(self, n, rng=None, umie=UMIEJETNOSCI, tylko_rzucane=False):
        self.n = n
        self.umie = set(umie)
        self.tylko_rzucane = tylko_rzucane            # drinking gets interrupted under ping and made bad lessons
        self.rng = rng or np.random.default_rng()
        self.v2 = BotV2(n, self.rng)
        self.tryb = np.zeros(n, np.int64)
        self.licz = np.zeros(n, np.int64)
        self.przerwa = np.zeros(n, np.int64)

    def nowa_walka(self, maska):
        self.v2.nowa_walka(maska)
        self.tryb[maska] = 0
        self.licz[maska] = 0
        self.przerwa[maska] = 0

    def __call__(self, obs, prawy, cel=None):
        from bridge import (BLOKI, BRON, HAK, HP, JABLKA, JEDZENIE, LECZENIE, MIKSTURA, MIKSTURA_ILE, PALI_SIE, PITCH as PI,
                          SLOT, STEKI, STRZALY, UZYCIE, UZYWA, WIADRO, X as MX, Y as MY, Z as MZ, ZESTAW, ZIEMIA as ZI)
        idx = self.v2(obs, cel)
        n = self.n
        cel = np.arange(n) ^ 1 if cel is None else cel
        for i in range(n):
            ja, on = obs[i], obs[cel[i]]
            m, odl, slot = int(ja[ZESTAW]), float(ja[ODL]), int(ja[SLOT])
            wcisniety = prawy[i] > 0.5
            self.przerwa[i] = max(0, self.przerwa[i] - 1)

            def wybierz(s):
                if slot != s:
                    idx[i, 7] = s + 1

            def wcisnij():
                if not wcisniety:
                    idx[i, 8] = 1

            def pusc():
                if wcisniety:
                    idx[i, 8] = 2

            if "jedzenie" not in self.umie: m &= ~2
            if "luk" not in self.umie: m &= ~1
            if "wedka" not in self.umie: m &= ~8
            if "tarcza" not in self.umie: m &= ~4
            if "bloki" not in self.umie: m &= ~16
            if "lawa" not in self.umie: m &= ~32
            if "leczenie" not in self.umie: m &= ~64
            if "mikstura" not in self.umie: m &= ~128
            rodzaj = int(ja[MIKSTURA]) if (m & 128) and ja[MIKSTURA_ILE] > 0 else 0
            if self.tylko_rzucane and rodzaj < 5:
                rodzaj = 0
            poziom = np.hypot(on[MX] - ja[MX], on[MZ] - ja[MZ]) + 1e-6
            pitch_stopy = -np.degrees(np.arctan2(on[MY] - (ja[MY] + 1.62), poziom))

            def patrz(cel_pitch):
                idx[i, 6] = _najblizszy(PITCH_PROGI, np.clip([cel_pitch - ja[PI]], -20, 20))[0]
                return abs(cel_pitch - ja[PI]) < 6
            ma_jedzenie = (m & 2) and (ja[JABLKA] > 0 or (ja[STEKI] > 0 and ja[JEDZENIE] < 20))
            t = self.tryb[i]
            if t == 0:
                if (m & 64) and ja[LECZENIE] > 0 and (ja[HP] < 5 or ja[HP] < 10 and odl > 3.5) and self.przerwa[i] == 0:
                    t = 7
                elif (m & 32) and ja[WIADRO] == 1 and 3.3 < odl < 4.4 and on[ZI] > 0.5:   # lava spreads ~3 blocks
                    t = 6
                elif rodzaj in (1, 2, 5, 6) and odl > 5.0 or rodzaj == 3 and ja[PALI_SIE] > 0.5 or \
                        rodzaj == 4 and ja[HP] < 14 and odl > 4.0:
                    t = 8
                elif rodzaj >= 7 and 4.0 < odl < 5.8:          # farther than 4 blocks: the potion does not splash the thrower
                    t = 9
                elif ma_jedzenie and ja[HP] < 10 and odl > 4.0:
                    t = 5 if (m & 16) and ja[BLOKI] > 0 and odl < 6.5 else 2
                elif (m & 1) and ja[STRZALY] > 0 and odl > (5.5 if ja[BRON] > 0 else 2.0):   # with fists the bow is almost always better
                    t = 1
                elif (m & 8) and 3.5 < odl < 7.0 and self.przerwa[i] == 0:
                    t = 3
                if t != self.tryb[i]:
                    self.licz[i] = 0
            self.licz[i] += 1
            k = self.licz[i]
            if t == 6:
                wybierz(6)
                idx[i, 3] = 0
                gotowy = patrz(pitch_stopy + 4)
                if slot == 6 and gotowy and k > 1:
                    wcisnij()
                if ja[WIADRO] != 1 or k > 12 or odl > 4.8:
                    pusc()
                    t = 10 if ja[WIADRO] != 1 else 0     # step away so we do not walk into our own lava
            elif t == 10:
                wybierz(0)
                idx[i, 0] = 0 * 3 + 1
                idx[i, 2] = 0
                if k > 12:
                    t = 0
            elif t in (7, 8) and (t == 7 or rodzaj in (5, 6)):   # throw at own feet (healing, strength/speed)
                s_ = 7 if t == 7 else 8
                wybierz(s_)
                idx[i, 3] = 0
                gotowy = patrz(88.0)
                if slot == s_ and gotowy and k > 1:
                    if not wcisniety:
                        wcisnij()
                    else:
                        pusc()
                        t = 0
                        self.przerwa[i] = 12
                if k > 14:
                    pusc()
                    t = 0
            elif t == 8:
                wybierz(8)
                idx[i, 3] = 0
                idx[i, 0] = 0 * 3 + 1
                idx[i, 2] = 0
                if slot == 8:
                    wcisnij()
                if k > 40 or ja[MIKSTURA_ILE] <= 0 or (k > 6 and ja[UZYWA] < 0.5 and wcisniety) or odl < 2.0:
                    pusc()
                    t = 0
            elif t == 9:
                wybierz(8)
                idx[i, 3] = 0
                gotowy = patrz(pitch_stopy)
                if slot == 8 and gotowy and k > 1:
                    if not wcisniety:
                        wcisnij()
                    else:
                        pusc()
                        t = 0
                if k > 12 or ja[MIKSTURA_ILE] <= 0:
                    pusc()
                    t = 0
            elif t == 5:
                wybierz(5)
                idx[i, 0] = 1 * 3 + 1
                idx[i, 3] = 0
                idx[i, 6] = _najblizszy(PITCH_PROGI, np.clip([55.0 - ja[PITCH]], -20, 20))[0]
                if slot == 5 and abs(55.0 - ja[PITCH]) < 8 and k > 3:
                    if k % 2 == 0:
                        wcisnij()
                    else:
                        pusc()
                if k > 14:
                    pusc()
                    t, self.licz[i] = 2, 0
            elif t == 2:
                s = 2 if ja[JABLKA] > 0 else 3
                wybierz(s)
                idx[i, 0] = 0 * 3 + 1
                idx[i, 2] = 0
                idx[i, 3] = 0
                if slot == s:
                    wcisnij()
                if k > 45 or (k > 6 and ja[UZYWA] < 0.5 and wcisniety) or odl < 2.5:
                    pusc()
                    t = 0
            elif t == 1:
                wybierz(1)
                idx[i, 0] = 1 * 3 + 1 + (1 if self.v2.kierunek[i] > 0 else -1)
                idx[i, 2] = 0
                idx[i, 3] = 0
                if slot == 1:
                    if not wcisniety:
                        wcisnij()
                    elif ja[UZYWA] > 0.5 and ja[UZYCIE] >= 20:
                        pusc()
                if (ja[BRON] > 0 and odl < 4.0) or k > 80 or ja[STRZALY] <= 0:
                    pusc()
                    t = 0
            elif t == 3:
                wybierz(4)
                idx[i, 3] = 0
                if slot == 4:
                    if k == 3 or (ja[HAK] > 1.5 and k > 4) or k == 18:
                        wcisnij()
                    else:
                        pusc()
                if k > 19 or (ja[HAK] > 1.5 and k > 6):
                    pusc()
                    t = 0
                    self.przerwa[i] = 40
            else:
                wybierz(0)
                if "tarcza" not in self.umie:            # without a shield nothing is suggested about the right button
                    self.tryb[i] = 0
                    continue
                if (m & 4) and odl < 3.6 and on[PASEK] > 0.85 and on[CELUJE] > 0.5 and ja[PASEK] < 0.85:
                    wcisnij()
                    t = 4 if slot == 0 else 0
                else:
                    pusc()
                t = 0 if t == 4 else t
            self.tryb[i] = t
        return idx
