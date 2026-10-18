"""What the network sees and how it controls the player.

Observation: raw numbers from the mod (bridge.RAW per player) turned into features from the bot's point of view
(opponent "2 blocks ahead of me, a bit to the right" rather than world coordinates), stacked over the last 4 ticks.

Action: 9 choice heads — movement (9 combinations of forward/back × left/right), jump, sprint, attack, sneak,
horizontal turn (15 steps) and vertical turn (9 steps), hotbar slot (0 = no change, 1–9), right button.

Stage 6: 1v1 arenas (players 2a, 2a+1) and 1v3 arenas (player 0 of the arena vs 1–3) — layout in class Uklad. A player's
"opponent" is its current target (Sedzia.cel): in 1v1 the other player, in 1v3 the network targets the nearest living
enemy and the enemies target the network.
"""
import numpy as np
from bridge import (ABSORPCJA, BLOKI, BLOKUJE, BRON, CELUJE, EFEKT, HAK, HP, HURT, JABLKA, JEDZENIE, KUCA, LECZENIE, MAPA_H,
                  MAPA_L, MIKSTURA, MIKSTURA_ILE, N_MAPA, NASYCENIE, NIETYK, OGIEN_T, PALI_SIE, PASEK, PITCH, REGEN, SLOT,
                  SPLAWIK_X, SPLAWIK_Y, SPLAWIK_Z, SPRINT, STEKI, STRZALA_JEST, STRZALA_VX, STRZALA_VY, STRZALA_VZ,
                  STRZALA_X, STRZALA_Y, STRZALA_Z, STRZALY, TARCZA_CD, TRZYMA, UZYCIE, UZYWA, VOKSELE, VX, VY, VZ, W_LAWIE,
                  WIADRO, WIDZI, WYPADL, X, Y, YAW, Z, ZESTAW, ZIEMIA, ZGINAL)

STOS = 4                    # how many recent ticks the network sees
MAX_EPIZOD = 1200           # ticks per 1v1 fight (60 s of game time), then decision on points and reset
MAX_EPIZOD_MULTI = 1800     # 1v3: 90 s

RUCH = np.array([(p, b) for p in (-1, 0, 1) for b in (-1, 0, 1)], dtype=np.float32)
YAW_PROGI = np.array([-45, -30, -20, -12, -6, -3, -1, 0, 1, 3, 6, 12, 20, 30, 45], dtype=np.float32)
PITCH_PROGI = np.array([-20, -10, -5, -2, 0, 2, 5, 10, 20], dtype=np.float32)
# movement, jump, sprint, attack, sneak, yaw, pitch, slot (0 = no change, 1–9 = slot 0–8), right button (0 keep as is,
# 1 press, 2 release — holding happens by itself, so random exploration can discover drawing a bow and eating for 32 ticks)
GLOWY = [len(RUCH), 2, 2, 2, 2, len(YAW_PROGI), len(PITCH_PROGI), 10, 3]
GLOWY_ETAP5 = [len(RUCH), 2, 2, 2, 2, len(YAW_PROGI), len(PITCH_PROGI), 7, 3]   # slots 0–5 (for loading older checkpoints)
# older versions (pool, exams) without these heads NEVER change the slot or press the right button — they play as before the stage
# (with a "sometimes switch" bias old versions randomly pulled out an apple / fishing rod and exams reported a false 100%)
BIAS_NOWYCH_GLOW = {7: [0.0] + [-30.0] * 9, 8: [0.0, -30.0, -30.0]}
BIAS_NOWYCH_SLOTOW = -30.0  # stage-5 versions do not know slots 6–8 (lava, healing, potion) — they never pick them


def _obroc(dx, dz, s, c):
    """World vector (dx, dz) -> (forward, right) for a player with yaw (sin s, cos c).
    In Minecraft forward = (-sin, cos), right = (-cos, -sin)."""
    return -dx * s + dz * c, -dx * c - dz * s


class Uklad:
    """Arenas and players: first n2 1v1 arenas (2 players each), then n4 1v3 arenas (4 players: 0 = network, 1–3 enemies)."""

    def __init__(self, n_aren, n4=0):
        self.nA, self.n4, self.n2 = n_aren, n4, n_aren - n4
        self.ile = np.array([2] * self.n2 + [4] * n4, np.int64)
        self.pocz = np.concatenate([[0], np.cumsum(self.ile)[:-1]]).astype(np.int64)
        self.n = int(self.ile.sum())
        self.klatka = np.repeat(np.arange(n_aren), self.ile)
        self.k = np.arange(self.n) - self.pocz[self.klatka]
        self.multi = self.ile == 4
        self.multi_g = self.multi[self.klatka]
        self.siec_multi = self.multi_g & (self.k == 0)          # player 0 of a 1v3 arena (the network)
        self.wrog_multi = self.multi_g & (self.k > 0)
        p = self.pocz[self.klatka]
        # default target: in 1v1 the other player (i ^ 1), in 1v3 network -> first enemy, enemies -> network
        self.cel0 = np.where(self.multi_g, np.where(self.k == 0, p + 1, p), p + 1 - self.k)
        self.pierwszy = self.pocz                                 # player 0 of every arena ("ours" in statistics)


def cechy_ticku(obs, t_epizodu, on, celuje=None, hp_on=None):
    """obs [n, RAW] -> [n, N_CECH]; on: [n, RAW] — how player i sees its target (delayed by ping).
    celuje: [n] — whether the crosshair is on the opponent (with lag: on the one that is seen); default: the mod's flag.
    hp_on: [n] — opponent HP as the player knows it (20 minus its losses this round); default: the true value."""
    ja = obs
    yaw = np.radians(ja[:, YAW])
    s, c = np.sin(yaw), np.cos(yaw)
    dx, dy, dz = on[:, X] - ja[:, X], on[:, Y] - ja[:, Y], on[:, Z] - ja[:, Z]
    przod, prawo = _obroc(dx, dz, s, c)
    poziom = np.hypot(dx, dz) + 1e-6
    blad_yaw = np.arctan2(prawo, przod)                                   # 0 = opponent straight ahead
    chce_pitch = -np.degrees(np.arctan2(dy, poziom))
    v_przod, v_prawo = _obroc(ja[:, VX], ja[:, VZ], s, c)
    vo_przod, vo_prawo = _obroc(on[:, VX], on[:, VZ], s, c)
    yaw_on = np.radians(on[:, YAW])
    so, co = np.sin(yaw_on), np.cos(yaw_on)
    o_przod, o_prawo = _obroc(-dx, -dz, so, co)                           # where I am, from the opponent's point of view
    o_blad = np.arctan2(o_prawo, o_przod)
    k = [
        # me
        ja[:, HP] / 20, ja[:, JEDZENIE] / 20, ja[:, NASYCENIE] / 5, ja[:, PASEK], ja[:, HURT] / 10,
        ja[:, NIETYK] / 20, ja[:, ZIEMIA], ja[:, SPRINT], ja[:, KUCA],
        ja[:, X] / 7, ja[:, Y] / 3, ja[:, Z] / 7, s, c, ja[:, PITCH] / 90,
        v_przod * 3, v_prawo * 3, ja[:, VY] * 3,
        # opponent relative to me
        przod / 7, prawo / 7, dy / 3, np.sqrt(dx * dx + dy * dy + dz * dz) / 10,   # = ODL from the mod, but from the seen position
        np.sin(blad_yaw), np.cos(blad_yaw), np.degrees(blad_yaw) / 180, (chce_pitch - ja[:, PITCH]) / 90,
        ja[:, CELUJE] if celuje is None else celuje,
        # opponent
        (on[:, HP] if hp_on is None else hp_on) / 20, on[:, PASEK], on[:, HURT] / 10, on[:, NIETYK] / 20, on[:, ZIEMIA], on[:, SPRINT], on[:, KUCA],
        on[:, CELUJE], vo_przod * 3, vo_prawo * 3, on[:, VY] * 3, np.sin(o_blad), np.cos(o_blad),
        # fight time
        t_epizodu / MAX_EPIZOD,
    ]
    return np.stack(k, 1).astype(np.float32)


N_CECH = 41
DODATKI_BRONI = 7           # outside the stack: round balance (/20) + my weapon (3) + opponent weapon (3), one-hot
MAX_LAG_AKCJI = 2           # ticks: an action reaches the server after 0–2 ticks (ping up to ~200 ms)
MAX_LAG_WIDOKU = 3          # ticks: opponent seen as it was 0–3 ticks ago (ping + client-side movement smoothing)
DODATKI_LAGU = MAX_LAG_AKCJI * 8 + 2   # actions sent but not yet executed (up to 2 × 8) + both delays
N_VOX = 75                  # blocks around the player (3 levels × 5 × 5, in the player's frame)
DODATKI_PRZEDMIOTOW = 34 + 25 + N_VOX + 4   # my items, opponent's visible items, blocks, slot/right button in flight (lag)
N_EFEKTOW = 7
N_WROGA = 15                # features of one extra enemy in 1v3
N_ETAP6 = (3 + 1 + 11 + 1 + 2 * N_EFEKTOW + 3 + 2 * N_MAPA + 4) + (4 + 2 + N_EFEKTOW + 1) + (2 + 2 * N_WROGA)
DODATKI = DODATKI_BRONI + DODATKI_LAGU + DODATKI_PRZEDMIOTOW + N_ETAP6
WEJSCIE = N_CECH * STOS + DODATKI
WEJSCIE_ETAP5 = 327
# input columns "in hand: lava bucket / empty bucket / splash potion / drinkable potion" (cechy_etapu6 -> _nowe_kategorie)
KOL_W_RECE = WEJSCIE_ETAP5 + 3 + 1 + 11 + 1 + 2 * N_EFEKTOW + 3 + 2 * N_MAPA
BRONIE = ["fists", "sword", "axe"]
# stage 2: loadouts (weapon of player 2a, weapon of player 2a+1) and their per-round probabilities
ZESTAWY_ETAP2 = [((1, 2), 0.2), ((2, 1), 0.2), ((1, 1), 0.25), ((2, 2), 0.25), ((0, 0), 0.1)]
# stage 3: same weapons, but with a weapon the mod adds full diamond armor (fists without armor); more fists against forgetting
ZESTAWY_ETAP3 = [((1, 2), 0.2), ((2, 1), 0.2), ((1, 1), 0.225), ((2, 2), 0.225), ((0, 0), 0.15)]
# stage 5: items (mask as in the mod: 1 bow, 2 food, 4 shield, 8 fishing rod, 16 blocks), symmetric for both players;
# armored weapons drawn uniformly; fists and weapons-only rounds stay to prevent forgetting
_BRONIE4 = [(1, 2), (2, 1), (1, 1), (2, 2)]
# + fists with a bow / fishing rod: there the item is the only sensible weapon, so its value is unambiguous
# (with armored weapons, randomly pulling out a bow in melee only hurts, and item exploration died out)
ZESTAWY_ETAP5 = ([((0, 0), 0, 0.08), ((0, 0), 1, 0.10), ((0, 0), 8, 0.03)] + [(b, 0, 0.16 / 4) for b in _BRONIE4] +
                 [(b, m, w / 4) for m, w in ((4, 0.10), (1, 0.10), (2, 0.10), (8, 0.06), (16, 0.07), (31, 0.20)) for b in _BRONIE4])
# stage 6: + 32 lava bucket, 64 healing potions, 128 potion in slot 8 (kind drawn every round, same for both)
PELNY = 255
OFENSYWNE = [7, 8, 9, 10]   # potions thrown at the opponent (harming II, poison II, slowness, weakness)
# entry: ((weapon A, weapon B), mask, probability[, allowed potion kinds in slot 8])
ZESTAWY_ETAP6 = ([((0, 0), 0, 0.03), ((0, 0), 1, 0.02), ((0, 0), 8, 0.02), ((0, 0), 32, 0.06), ((0, 0), 128, 0.06, OFENSYWNE),
                  ((0, 0), 64, 0.04), ((0, 0), 64 | 128, 0.03)] +
                 [(b, 0, 0.06 / 4) for b in _BRONIE4] +
                 [(b, m, w / 4) for m, w in ((4, 0.04), (1, 0.03), (2, 0.02), (8, 0.04), (16, 0.02), (32, 0.07),
                                             (64, 0.06), (128, 0.09), (4 | 64, 0.04), (PELNY, 0.24)) for b in _BRONIE4])
# more "fists + lava / healing / offensive splash potion" rounds: without armor the item is the main weapon
# (harming II = -12 HP, lava kills, healing +8 HP), so its value is unambiguous — without them the network almost
# stopped using the new items (like the bow in stage 5, fixed with fists + bow rounds)
# 1v3: (network weapon, enemy weapon, network mask, enemy mask, probability); weapon 1/2 drawn per player (0 = random)
ZESTAWY_MULTI = [(0, 0, PELNY, PELNY, 0.35), (0, 0, PELNY, 4 | 2 | 64, 0.4), (0, 0, PELNY, 0, 0.25)]
# more enemies with weapon+shield+food+healing: these are the hardest 1v3 opponents
ROZKLAD_BOTOW = (0.25, 0.35, 0.40)      # probability of 1, 2, 3 active enemies in a 1v3 arena
N_MIKSTUR = 10


def cechy_przedmiotow(obs, on, prawy, w_drodze=None):
    """Items: mine (everything I see in my own inventory) + the opponent's (only what is visible: what it holds, whether
    it is drawing / eating / blocking, its arrow in flight, its bobber) + blocks around me + slot and right button "in flight" (lag)."""
    n = len(obs)
    ja = obs
    oko = lambda v, k: np.eye(k, dtype=np.float32)[np.clip(v.astype(np.int64), 0, k - 1)]
    yaw = np.radians(ja[:, YAW])
    s, c = np.sin(yaw), np.cos(yaw)
    zestaw = ja[:, ZESTAW].astype(np.int64)
    moje = [oko(ja[:, SLOT], 6), oko(ja[:, TRZYMA], 9),
            np.stack([ja[:, UZYWA], np.minimum(ja[:, UZYCIE] / 32, 1.5), ja[:, BLOKUJE], ja[:, TARCZA_CD],
                      ja[:, STRZALY] / 16, ja[:, JABLKA] / 2, ja[:, STEKI] / 8, ja[:, BLOKI] / 32], 1),
            np.stack([(zestaw >> b) & 1 for b in range(5)], 1).astype(np.float32),
            np.stack([ja[:, ABSORPCJA] / 4, ja[:, REGEN]], 1), oko(ja[:, HAK], 3), prawy[:, None].astype(np.float32)]
    jest_spl = on[:, HAK] > 0.5
    sp, sb = _obroc(on[:, SPLAWIK_X] - ja[:, X], on[:, SPLAWIK_Z] - ja[:, Z], s, c)
    spl = np.stack([sp / 7, sb / 7, (on[:, SPLAWIK_Y] - ja[:, Y]) / 3], 1) * jest_spl[:, None]
    jest_s = on[:, STRZALA_JEST] > 0.5
    ap, ab = _obroc(on[:, STRZALA_X] - ja[:, X], on[:, STRZALA_Z] - ja[:, Z], s, c)
    vp, vb = _obroc(on[:, STRZALA_VX], on[:, STRZALA_VZ], s, c)
    strz = np.stack([ap / 10, ab / 10, (on[:, STRZALA_Y] - ja[:, Y]) / 3, vp, vb, on[:, STRZALA_VY]], 1) * jest_s[:, None]
    jego = [oko(on[:, TRZYMA], 9), np.stack([on[:, UZYWA], np.minimum(on[:, UZYCIE] / 32, 1.5), on[:, BLOKUJE]], 1),
            oko(on[:, HAK], 3), spl, strz, jest_s[:, None].astype(np.float32)]
    vox = ja[:, VOKSELE:VOKSELE + N_VOX]
    if w_drodze is None:
        w_drodze = np.zeros((n, 4), np.float32)
    return np.concatenate(moje + jego + [vox, w_drodze], 1).astype(np.float32)


def _nowe_kategorie(v):
    """Stage-6 item categories (9 lava bucket, 10 empty bucket, 11 splash potion, 12 drinkable potion), one-hot."""
    return np.stack([v == 9, v == 10, v == 11, v == 12], 1).astype(np.float32)


def _efekty(o):
    return o[:, EFEKT:EFEKT + 2 * N_EFEKTOW:2]


def cechy_etapu6(obs, on, sedzia, widok):
    """Stage 6: my bucket / potions / effects / fire, terrain and lava map around me; for the target what is visible (what
    it holds, whether it burns, effects from particles, whether terrain hides it); in 1v3 the two other enemies (position,
    movement, HP from my hits, weapon, shield, whether it aims at me, whether I see it). widok(j) -> [n, RAW]: player j as seen by i."""
    u = sedzia.u
    n = len(obs)
    ja = obs
    oko = lambda v, k: np.eye(k, dtype=np.float32)[np.clip(v.astype(np.int64), 0, k - 1)]
    ef = _efekty(ja)
    czasy = ja[:, EFEKT + 1:EFEKT + 2 * N_EFEKTOW:2]
    moje = [oko(ja[:, WIADRO], 3), (ja[:, LECZENIE] / 3)[:, None], oko(ja[:, MIKSTURA], N_MIKSTUR + 1),
            (ja[:, MIKSTURA_ILE] / 2)[:, None], ef / 2, np.minimum(czasy / 60, 3),
            np.stack([np.minimum(ja[:, OGIEN_T] / 8, 2), ja[:, W_LAWIE], ja[:, PALI_SIE]], 1),
            np.clip(ja[:, MAPA_H:MAPA_H + N_MAPA], -6, 3) / 3, ja[:, MAPA_L:MAPA_L + N_MAPA], _nowe_kategorie(ja[:, TRZYMA])]
    k_cel = u.k[sedzia.cel]
    widze_cel = ja[np.arange(n), WIDZI + k_cel]
    jego = [_nowe_kategorie(on[:, TRZYMA]), np.stack([on[:, PALI_SIE], on[:, W_LAWIE]], 1), (_efekty(on) > 0).astype(np.float32),
            widze_cel[:, None]]
    multi = np.zeros((n, 2 + 2 * N_WROGA), np.float32)
    if u.n4:
        S = np.where(u.siec_multi)[0]
        multi[S, 0] = 1
        zywi = sedzia.zywi
        E = u.pocz[u.klatka[S]][:, None] + np.arange(1, 4)[None, :]      # enemies of every 1v3 network player
        zyw = zywi[E]
        multi[S, 1] = zyw.sum(1) / 3
        d = np.linalg.norm(obs[E][:, :, [X, Y, Z]] - obs[S][:, None, [X, Y, Z]], axis=2)
        d[~zyw] = np.inf
        d[E == sedzia.cel[S][:, None]] = np.inf                          # the current target is already in the main features
        kolej = np.argsort(d, 1)[:, :2]
        yaw = np.radians(ja[S, YAW])
        s, c = np.sin(yaw), np.cos(yaw)
        for m in range(2):
            j = E[np.arange(len(S)), kolej[:, m]]
            jest = np.isfinite(d[np.arange(len(S)), kolej[:, m]])
            if not jest.any():
                continue
            jj = np.where(jest, j, S)
            w = widok(_rozszerz(n, S, jj))[S]
            dx, dy, dz = w[:, X] - ja[S, X], w[:, Y] - ja[S, Y], w[:, Z] - ja[S, Z]
            p, b = _obroc(dx, dz, s, c)
            vp, vb = _obroc(w[:, VX], w[:, VZ], s, c)
            so, co = np.sin(np.radians(w[:, YAW])), np.cos(np.radians(w[:, YAW]))
            op, ob = _obroc(-dx, -dz, so, co)
            o_blad = np.arctan2(ob, op)
            hp = np.clip(20 - sedzia.straty[jj], 0, 20)
            cecha = np.stack([np.ones(len(S)), p / 10, b / 10, dy / 3, np.sqrt(dx * dx + dy * dy + dz * dz) / 10,
                              vp * 3, vb * 3, hp / 20, ((w[:, TRZYMA] == 1) | (w[:, TRZYMA] == 2)).astype(np.float32),
                              w[:, UZYWA], w[:, BLOKUJE], w[:, CELUJE] * (sedzia.cel[jj] == S),
                              ja[S, WIDZI + u.k[jj]], np.sin(o_blad), np.cos(o_blad)], 1) * jest[:, None]
            multi[S, 2 + m * N_WROGA:2 + (m + 1) * N_WROGA] = cecha
    return np.concatenate(moje + jego + [multi], 1).astype(np.float32)


def _rozszerz(n, S, jj):
    """Array [n] of player indices: jj for players S, themselves for everyone else."""
    j = np.arange(n)
    j[S] = jj
    return j


def wejscie(stos_wyj, bilans, obs, on, lag=None, przedmioty=None, etap6=None):
    """Full network input: tick stack + round balance + my and the opponent's weapon (from the player's view)
    + lag info (actions in flight to the server and delays; zeros without lag) + items + stage 6."""
    n = len(obs)
    oko = np.eye(3, dtype=np.float32)
    bron = np.clip(obs[:, BRON].astype(np.int64), 0, 2)
    bron_on = np.clip(on[:, BRON].astype(np.int64), 0, 2)
    if lag is None:
        lag = np.zeros((n, DODATKI_LAGU), np.float32)
    if przedmioty is None:
        przedmioty = np.zeros((n, DODATKI_PRZEDMIOTOW), np.float32)
    if etap6 is None:
        etap6 = np.zeros((n, N_ETAP6), np.float32)
    return np.concatenate([stos_wyj, (bilans / 20)[:, None].astype(np.float32), oko[bron], oko[bron_on],
                           lag, przedmioty, etap6], 1)


SKALA_AKCJI = np.array([1, 1, 1, 1, 1, 1 / 45, 1 / 20, 1], np.float32)   # action for the mod -> roughly -1..1
ZASIEG = 3.0                # player's entityInteractionRange


def celownik(ja, on, yaw, pitch):
    """Whether a ray from the player's eye (position from `ja`, yaw/pitch in degrees) hits the hitbox of `on` within 3 blocks
    — like the client raycast in MC. Player hitbox 0.6 × 1.8 (sneaking 1.5), eye 1.62 (sneaking 1.27). Blocks are not checked
    (the server checks terrain occlusion for attack code 2)."""
    oko = np.stack([ja[:, X], ja[:, Y] + np.where(ja[:, KUCA] > 0.5, 1.27, 1.62), ja[:, Z]], 1)
    y, p = np.radians(yaw), np.radians(pitch)
    kier = np.stack([-np.sin(y) * np.cos(p), -np.sin(p), np.cos(y) * np.cos(p)], 1)
    lo = np.stack([on[:, X] - 0.3, on[:, Y], on[:, Z] - 0.3], 1)
    hi = np.stack([on[:, X] + 0.3, on[:, Y] + np.where(on[:, KUCA] > 0.5, 1.5, 1.8), on[:, Z] + 0.3], 1)
    with np.errstate(divide="ignore", invalid="ignore"):
        t1, t2 = (lo - oko) / kier, (hi - oko) / kier
    tmin = np.nanmax(np.where(np.isnan(t1), -np.inf, np.minimum(t1, t2)), 1)
    tmax = np.nanmin(np.where(np.isnan(t1), np.inf, np.maximum(t1, t2)), 1)
    w_srodku = np.all((oko >= lo) & (oko <= hi), 1)
    return w_srodku | ((tmax >= np.maximum(tmin, 0)) & (tmin <= ZASIEG))


class Lag:
    """Ping like a human playing over the internet (the "fair network" stage).

    Every round each player draws a ping. An action chosen now reaches the server after `akc` ticks,
    and the opponent is seen as it was `widok` ticks ago (the player sees itself up to date — the client predicts
    its own movement). The network gets the actions "in flight" (already chosen, not yet executed) and both delays as
    input — just as a human knows what they already pressed and knows their ping.
    Distribution: 15% of rounds without lag (local play), the rest ping 0–200 ms: akc = round(ping/100) in {0,1,2},
    widok = akc + 1 (the client smooths other players' movement by ~1 tick).
    Players in the `wylacz` mask (e.g. bot v2) play without lag; `staly` = {player: (akc, widok)} forces values.
    """

    def __init__(self, n, rng, bez_lagu=0.15):
        self.n, self.rng, self.bez_lagu = n, rng, bez_lagu
        self.akc = np.zeros(n, np.int64)
        self.widok = np.zeros(n, np.int64)
        self.wylacz = np.zeros(n, bool)
        self.staly = {}
        self.hist_obs = None                                   # [MAX_LAG_WIDOKU+1, n, RAW], [0] = current
        self.hist_akc = np.zeros((MAX_LAG_AKCJI + 1, n, 10), np.float32)  # [0] = most recently chosen
        self.widziany = None                                   # [n, RAW] target as the player sees it

    def losuj(self, gracze):
        idx = np.where(gracze)[0]
        if len(idx) == 0:
            return
        ping = self.rng.uniform(0, 200, len(idx))
        akc = np.clip(np.rint(ping / 100).astype(np.int64), 0, MAX_LAG_AKCJI)
        widok = np.minimum(akc + 1, MAX_LAG_WIDOKU)
        zero = self.rng.random(len(idx)) < self.bez_lagu
        akc[zero], widok[zero] = 0, 0
        self.akc[idx], self.widok[idx] = akc, widok
        for g, (a, w) in self.staly.items():
            if gracze[g]:
                self.akc[g], self.widok[g] = a, w
        self.akc[self.wylacz], self.widok[self.wylacz] = 0, 0

    def widok_przeciwnika(self, obs, swiezi, cel):
        """Store the current observation; return [n, RAW] — the target as each player sees it."""
        if self.hist_obs is None:
            self.hist_obs = np.repeat(obs[None], MAX_LAG_WIDOKU + 1, 0)
        else:
            self.hist_obs = np.roll(self.hist_obs, 1, 0)
            self.hist_obs[0] = obs
            if swiezi.any():                                   # new round: no older frames from this round
                self.hist_obs[:, swiezi] = obs[swiezi]
        if swiezi.any():
            self.hist_akc[:, swiezi] = 0
            self.losuj(swiezi)
        self.akc[self.wylacz], self.widok[self.wylacz] = 0, 0
        self.widziany = self.hist_obs[self.widok, cel]
        return self.widziany

    def widok_gracza(self, j):
        """[n, RAW]: player j[i] as seen by player i (with i's delay)."""
        return self.hist_obs[self.widok, j]

    def wejscie(self):
        """[n, DODATKI_LAGU]: actions in flight (the one executed soonest first) + delays."""
        w = np.zeros((self.n, DODATKI_LAGU), np.float32)
        for j in range(MAX_LAG_AKCJI):                          # in flight are decisions [akc-1 .. 0] (0 = latest)
            k = self.akc - 1 - j                                # j-th in execution order
            ok = k >= 0
            a = self.hist_akc[k[ok], np.where(ok)[0]][:, :8]
            a[:, 4] = a[:, 4] > 0.5                             # attack: clicked / not (without the hit code)
            w[ok, 8 * j:8 * j + 8] = a * SKALA_AKCJI
        w[:, -2] = self.akc / MAX_LAG_AKCJI
        w[:, -1] = self.widok / MAX_LAG_WIDOKU
        return w

    def w_drodze_przedmioty(self):
        """[n, 4]: slot (/6) and right button of the two decisions in flight to the server."""
        w = np.zeros((self.n, 4), np.float32)
        for j in range(MAX_LAG_AKCJI):
            k = self.akc - 1 - j
            ok = k >= 0
            a = self.hist_akc[k[ok], np.where(ok)[0]]
            w[ok, 2 * j] = a[:, 8] / 6
            w[ok, 2 * j + 1] = a[:, 9]
        return w

    def akcje(self, akc):
        """Store this tick's decisions; return the actions that are arriving at the server right now.

        Attack of a lagged player: the hit is decided by the "client" at the moment of the click — crosshair (current angles
        plus turns still in flight and this tick's turn) on the opponent as the player sees it. Code for the mod: 2 = hit,
        3 = miss (the server then only checks range and terrain occlusion, like an anticheat with ping tolerance). Without lag it stays 1."""
        akc = akc.copy()
        z_lagiem = (self.akc > 0) | (self.widok > 0)
        klik = (akc[:, 4] > 0.5) & z_lagiem
        if klik.any():
            ja = self.hist_obs[0]
            w_drodze = np.arange(MAX_LAG_AKCJI + 1)[:, None] < self.akc[None, :]   # decisions not yet executed
            yaw = ja[:, YAW] + (self.hist_akc[:, :, 5] * w_drodze).sum(0) + akc[:, 5]
            pitch = np.clip(ja[:, PITCH] + (self.hist_akc[:, :, 6] * w_drodze).sum(0) + akc[:, 6], -90, 90)
            traf = celownik(ja, self.widziany, yaw, pitch)
            akc[klik, 4] = np.where(traf[klik], 2.0, 3.0)
        self.hist_akc = np.roll(self.hist_akc, 1, 0)
        self.hist_akc[0] = akc
        return self.hist_akc[self.akc, np.arange(self.n)]


class Stos:
    """Last STOS ticks of features for every player; a new fight fills the stack with the current frame."""

    def __init__(self, n):
        self.bufor = np.zeros((n, STOS, N_CECH), dtype=np.float32)
        self.swiezy = np.ones(n, dtype=bool)

    def dodaj(self, c):
        self.bufor[:, :-1] = self.bufor[:, 1:]
        self.bufor[:, -1] = c
        if self.swiezy.any():
            self.bufor[self.swiezy] = c[self.swiezy, None, :]
            self.swiezy[:] = False
        return self.bufor.reshape(len(c), N_CECH * STOS).copy()

    def nowa_walka(self, maska):
        self.swiezy |= maska


def akcje_z_indeksow(idx):
    """idx [n, 7 or 9] int -> actions for the mod [n, 10]: forward, strafe, jump, sprint, attack, yaw, pitch, sneak, slot, right."""
    n = len(idx)
    a = np.zeros((n, 10), dtype=np.float32)
    a[:, 0:2] = RUCH[idx[:, 0]]
    if idx.shape[1] > 7:
        a[:, 8] = idx[:, 7]                       # slot: 0 no change, 1–9
        a[:, 9] = idx[:, 8]                       # right: 0 keep as is, 1 press, 2 release (Sedzia.akcje -> 0/1)
    a[:, 2] = idx[:, 1]
    a[:, 3] = idx[:, 2]
    a[:, 4] = idx[:, 3]
    a[:, 7] = idx[:, 4]
    a[:, 5] = YAW_PROGI[idx[:, 5]]
    a[:, 6] = PITCH_PROGI[idx[:, 6]]
    return a


class Sedzia:
    """Round rules in one place (training, imitation, the show and exams all compute them the same way).

    1v1: a round ends with a death or after MAX_EPIZOD ticks. Without a kill the damage balance decides
    (dealt minus taken over the whole round), like in boxing:
      kill +1 > win on points +0.5 > draw -kara_remis > loss on points -0.75 > death -1.
    1v3 (player 0 = network): +0.5 for every enemy taken down, +0.5 for taking down all of them, -1 for its own death;
    end: network death, no living enemies or MAX_EPIZOD_MULTI (then the balance decides as in 1v1).
    On top of that, every tick 0.05 per point of damage dealt minus taken.
    The observation in which a round ended is "garbage": its actions are not used, and the mod resets the arena.
    """

    def __init__(self, n_aren, kara_remis=0.5, nagroda_pkt=0.5, kara_pkt=0.75, prog_pkt=2.0, zestawy=None, rng=None,
                 lag=False, n4=0, zestawy_multi=None, rozklad_botow=ROZKLAD_BOTOW, teren=None, aktywne=None,
                 start_przedmiot=0.0):
        """zestawy: list of ((weapon A, weapon B), [mask,] probability) for 1v1 arenas; None = fists only.
        zestawy_multi: list of (network weapon, enemy weapon, network mask, enemy mask, probability) for 1v3 arenas (weapon 0 = random).
        teren: (probability of a flat round, probability of new random terrain) — remaining rounds reuse the terrain; None = flat.
        aktywne: [n_aren] bool — inactive arenas stay empty (all players out of play).
        start_przedmiot: in this fraction of 1v1 rounds with lava / healing / offensive potion both start with that item in hand
        (healing: also with 10 HP) — a single click shows what the item does (instead of a random slot+aim+throw sequence).
        lag: internet-like ping (class Lag); then send actions through sedzia.akcje(...)."""
        self.u = u = Uklad(n_aren, n4)
        self.nA, self.n = n_aren, u.n
        self.zestawy, self.rng = zestawy or [((0, 0), 0, 1.0)], rng or np.random.default_rng()
        self.zestawy_multi = zestawy_multi or ZESTAWY_MULTI
        self.rozklad_botow = np.array(rozklad_botow, np.float64)
        self.teren = teren
        self.start_przedmiot = start_przedmiot
        self.aktywne = np.ones(n_aren, bool) if aktywne is None else np.asarray(aktywne, bool)
        self.lag = Lag(self.n, self.rng) if lag else None
        self.nast_bron = np.zeros(self.n, np.uint8)
        self.nast_maska = np.zeros(self.n, np.uint8)
        self.nast_mikstura = np.zeros(self.n, np.uint8)
        self.kod_resetu = np.full(n_aren, 2 if teren else 3, np.uint8)
        self.ile_botow = np.zeros(n_aren, np.int64)        # enemies in the next 1v3 round
        self.ile_botow_runda = np.zeros(n_aren, np.int64)  # enemies in the current round
        self.straty = np.zeros(self.n, np.float32)         # player's HP losses this round (others estimate its HP from them)
        self.prawy = np.zeros(self.n, np.float32)          # whether I hold the right button (state after decisions)
        self.cel = u.cel0.copy()
        self.zywi = np.ones(self.n, bool)
        self.kara_remis, self.nagroda_pkt, self.kara_pkt, self.prog_pkt = kara_remis, nagroda_pkt, kara_pkt, prog_pkt
        self.t_ep = np.zeros(n_aren, np.int64)
        self.max_ep = np.where(u.multi, MAX_EPIZOD_MULTI, MAX_EPIZOD)
        self.smiec = np.ones(n_aren, bool)            # reset right away: the first round already uses a drawn loadout
        self.bilans = np.zeros(self.n, np.float32)    # dealt minus taken since the start of the round
        self._losuj(np.ones(n_aren, bool))

    # ------------------------------------------------------------ loadouts for the next round

    def _losuj(self, klatki):
        u, r = self.u, self.rng
        for a in np.where(klatki)[0]:
            p0 = u.pocz[a]
            if self.teren:
                x = r.random()
                self.kod_resetu[a] = 3 if x < self.teren[0] else 2 if x < self.teren[0] + self.teren[1] else 1
            else:
                self.kod_resetu[a] = 3
            if not self.aktywne[a]:
                self.nast_bron[p0:p0 + u.ile[a]] = PELNY
                continue
            if not u.multi[a]:
                p = np.array([z[2] if len(z) >= 3 else z[-1] for z in self.zestawy], np.float64)
                z = self.zestawy[r.choice(len(self.zestawy), p=p / p.sum())]
                self.nast_bron[p0], self.nast_bron[p0 + 1] = z[0]
                m = z[1] if len(z) >= 3 else 0
                self.nast_maska[p0:p0 + 2] = m
                rodzaje = z[3] if len(z) == 4 else range(1, N_MIKSTUR + 1)
                rodzaj = int(r.choice(list(rodzaje))) if m & 128 else 0
                bajt = rodzaj                              # bits 0–3 kind, 4–5 starting slot, bit 6 = start with 10 HP
                if self.start_przedmiot and r.random() < self.start_przedmiot:
                    opcje = ([(1, 0)] if m & 32 else []) + ([(2, 64)] if m & 64 else []) + ([(3, 0)] if rodzaj >= 7 else [])
                    if opcje:
                        st, pol = opcje[r.integers(len(opcje))]
                        bajt |= (st << 4) | pol
                self.nast_mikstura[p0:p0 + 2] = bajt
            else:
                p = np.array([z[-1] for z in self.zestawy_multi], np.float64)
                bs, bw, ms, mw, _ = self.zestawy_multi[r.choice(len(self.zestawy_multi), p=p / p.sum())]
                ile = 1 + r.choice(3, p=self.rozklad_botow / self.rozklad_botow.sum())
                self.ile_botow[a] = ile
                for k in range(4):
                    i = p0 + k
                    if k > ile:
                        self.nast_bron[i] = PELNY                      # this enemy sits out the round outside the arena
                        continue
                    b = bs if k == 0 else bw
                    self.nast_bron[i] = b if b else r.integers(1, 3)
                    m = ms if k == 0 else mw
                    self.nast_maska[i] = m
                    self.nast_mikstura[i] = r.integers(1, N_MIKSTUR + 1) if m & 128 else 0

    def przedmioty(self):
        """Item mask for the next round (the mod hands them out when resetting the arena)."""
        return self.nast_maska

    def bronie(self):
        """Weapon for the next round for every player (255 = out of play)."""
        return self.nast_bron

    def mikstury(self):
        return self.nast_mikstura

    def cele(self):
        """Target index within the arena (0–3) for the mod."""
        return (self.cel - self.u.pocz[self.u.klatka]).astype(np.uint8)

    def resety(self):
        """Reset requests sent together with the actions (arenas after a round ended; 2 = new terrain, 3 = flat)."""
        return np.where(self.smiec, self.kod_resetu, 0).astype(np.uint8)

    def wyslij(self, most, akc):
        """Actions + everything the mod expects this tick."""
        most.akcje(akc, self.resety(), self.bronie(), self.przedmioty(), self.mikstury(), self.cele())

    # ------------------------------------------------------------ krok

    def krok(self, obs_przed, obs_po):
        u = self.u
        swieze = self.smiec.copy()                    # these arenas have just started a new round
        sg = swieze[u.klatka]
        self.ile_botow_runda[swieze] = self.ile_botow[swieze]
        self.t_ep[swieze] = 0
        self.t_ep[~swieze] += 1
        poza_przed = obs_przed[:, WYPADL] > 0.5
        poza_po = obs_po[:, WYPADL] > 0.5
        stracone = np.maximum(0, obs_przed[:, HP] - obs_po[:, HP]) * ~sg * ~poza_przed * ~poza_po
        zginal = (obs_po[:, ZGINAL] > 0.5) & ~poza_przed & ~sg
        zadane = stracone[u.cel0].copy()
        r = 0.05 * (0 - stracone) - zginal.astype(np.float32)
        r1 = ~u.multi_g
        r[r1] += 0.05 * zadane[r1] + zginal[u.cel0][r1]
        zab_multi = np.zeros(self.nA, np.int64)
        if u.n4:
            W = u.wrog_multi
            wr_str = np.bincount(u.klatka[W], stracone[W], minlength=self.nA)
            zab_multi = np.bincount(u.klatka[W], zginal[W], minlength=self.nA).astype(np.int64)
            S = u.siec_multi
            zadane[S] = wr_str[u.klatka[S]]
            r[S] += 0.05 * zadane[S] + 0.5 * zab_multi[u.klatka[S]]
            zadane[W] = stracone[u.pocz[u.klatka[W]]]
        self.bilans[sg] = 0
        self.bilans[~sg] += (zadane - stracone)[~sg]
        self.straty[sg] = 0
        self.straty[~sg] += stracone[~sg]
        self.zywi = ~poza_po & ~(obs_po[:, ZGINAL] > 0.5)
        p0 = u.pocz
        b = self.bilans[p0]                           # from the point of view of the arena's player 0
        smierc0 = zginal[p0]
        dod = np.zeros(self.n, np.float32)
        wynik0 = np.zeros(self.nA, np.int64)          # 1 player 0 won, -1 lost, 0 draw (for arenas that ended)
        # 1v1
        smierc1 = zginal[p0] | zginal[np.minimum(p0 + 1, self.n - 1)]
        jeden = ~u.multi & self.aktywne
        koniec = jeden & ~swieze & (smierc1 | (self.t_ep >= self.max_ep))
        # 1v3
        if u.n4:
            wr_zywi = np.bincount(u.klatka[u.wrog_multi], self.zywi[u.wrog_multi], minlength=self.nA)
            wszyscy = u.multi & (wr_zywi == 0)
            koniec |= u.multi & self.aktywne & ~swieze & (smierc0 | wszyscy | (self.t_ep >= self.max_ep))
        else:
            wszyscy = np.zeros(self.nA, bool)
        smierc = np.where(u.multi, smierc0 | wszyscy, smierc1)
        limit = koniec & ~smierc
        remis = limit & (np.abs(b) < self.prog_pkt)
        wygral0 = limit & (b >= self.prog_pkt)
        wygral1 = limit & (b <= -self.prog_pkt)
        dod[p0] = -self.kara_remis * remis + self.nagroda_pkt * wygral0 - self.kara_pkt * wygral1
        drugi = p0[~u.multi] + 1
        a1 = ~u.multi
        dod[drugi] = (-self.kara_remis * remis + self.nagroda_pkt * wygral1 - self.kara_pkt * wygral0)[a1]
        dod[p0] += 0.5 * (koniec & u.multi & wszyscy & ~smierc0)          # 1v3: took down everyone
        wynik0[koniec & (wygral0 | (u.multi & wszyscy & ~smierc0) | (~u.multi & smierc1 & ~zginal[p0]))] = 1
        wynik0[koniec & (wygral1 | smierc0)] = -1
        self.smiec = koniec
        self._losuj(koniec)
        return dict(r=r + dod, koniec=koniec, swieze=swieze, zadane=zadane, stracone=stracone, zginal=zginal,
                    remis=remis, na_punkty=wygral0 | wygral1, wynik0=wynik0, zabici=zab_multi,
                    czyste=koniec & u.multi & wszyscy & ~smierc0)

    # ------------------------------------------------------------ network input

    def aktualizuj_cele(self, obs, swiezi):
        """1v3: the network targets the nearest living enemy (switching only when another one is closer by 1.5 blocks)."""
        u = self.u
        if not u.n4:
            return
        S = np.where(u.siec_multi)[0]
        E = u.pocz[u.klatka[S]][:, None] + np.arange(1, 4)[None, :]
        zyw = ~(obs[E, WYPADL] > 0.5) & ~(obs[E, ZGINAL] > 0.5)
        d = np.linalg.norm(obs[E][:, :, [X, Y, Z]] - obs[S][:, None, [X, Y, Z]], axis=2)
        d[~zyw] = np.inf
        naj = d.argmin(1)
        biez = np.clip(self.cel[S] - u.pocz[u.klatka[S]] - 1, 0, 2)
        db = d[np.arange(len(S)), biez]
        zostan = np.isfinite(db) & (db <= d[np.arange(len(S)), naj] + 1.5) & ~swiezi[S]
        self.cel[S] = u.pocz[u.klatka[S]] + 1 + np.where(zostan, biez, naj)

    def wejscie_sieci(self, stos, obs, swieze=None):
        """Full network input. swieze = arenas that have just started a round."""
        u = self.u
        if swieze is not None:
            stos.nowa_walka(swieze[u.klatka])
        swiezi = np.ones(self.n, bool) if swieze is None else swieze[u.klatka]
        self.prawy[swiezi] = 0
        if swieze is None:
            self.zywi = ~(obs[:, WYPADL] > 0.5)
        self.aktualizuj_cele(obs, swiezi)
        hp_on = np.clip(20 - self.straty[self.cel], 0, 20)     # opponent HP = 20 minus its losses (as a human would count)
        t = self.t_ep[u.klatka]
        if self.lag is None:
            on = obs[self.cel]
            return wejscie(stos.dodaj(cechy_ticku(obs, t, on, hp_on=hp_on)), self.bilans, obs, on,
                           przedmioty=cechy_przedmiotow(obs, on, self.prawy),
                           etap6=cechy_etapu6(obs, on, self, lambda j: obs[j]))
        on = self.lag.widok_przeciwnika(obs, swiezi, self.cel)
        # the mod's "aiming" flag uses the true opponent position — with lag that is knowledge the client does not have;
        # compute it like the client, on the seen opponent (without lag the mod's flag stays; agreement 99.994%)
        celuje = np.where(self.lag.widok > 0, celownik(obs, on, obs[:, YAW], obs[:, PITCH]), obs[:, CELUJE])
        return wejscie(stos.dodaj(cechy_ticku(obs, t, on, celuje, hp_on)), self.bilans, obs, on,
                       self.lag.wejscie(), cechy_przedmiotow(obs, on, self.prawy, self.lag.w_drodze_przedmioty()),
                       cechy_etapu6(obs, on, self, self.lag.widok_gracza))

    def akcje(self, akc):
        """Actions to send this tick (with lag: the ones chosen `akc` ticks ago). Right button: the decision
        "keep as is / press / release" is turned into a pressed state 0/1."""
        akc = akc.copy()
        u = akc[:, 9]
        self.prawy = np.where(u == 1, 1.0, np.where(u == 2, 0.0, self.prawy)).astype(np.float32)
        akc[:, 9] = self.prawy
        return akc if self.lag is None else self.lag.akcje(akc)
