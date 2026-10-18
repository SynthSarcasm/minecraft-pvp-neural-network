"""Speed test: how many steps per second a real server with fighting bots delivers.

Bots are driven by a simple script (chase, aim, attack with a full attack bar) to
check along the way that combat works. Optionally a large network is also evaluated on the
whole observation batch every step — to measure the cost of the network in the loop.

  python test_speed.py --areny 16 --tempo 10000 --sekundy 30 [--siec 2048x4]
"""
import argparse
import time
import numpy as np
from bridge import ACT, CELUJE, HP, Most, PASEK, RAW, X, YAW, Z, ZGINAL


def bot_skryptowy(obs, los):
    n = len(obs)
    akc = np.zeros((n, ACT), dtype=np.float32)
    ja, on = obs, obs[np.arange(n) ^ 1]
    dx, dz = on[:, X] - ja[:, X], on[:, Z] - ja[:, Z]
    # yaw in Minecraft: direction (-sin, cos)
    chce_yaw = np.degrees(np.arctan2(-dx, dz))
    dyaw = (chce_yaw - ja[:, YAW] + 180) % 360 - 180
    akc[:, 0] = 1.0                                     # forward
    akc[:, 1] = los.choice([-1.0, 0.0, 1.0], n, p=[0.15, 0.7, 0.15])
    akc[:, 2] = los.random(n) < 0.05                    # occasional jump
    akc[:, 3] = 1.0                                     # sprint
    akc[:, 4] = (ja[:, CELUJE] > 0.5) & (ja[:, PASEK] > 0.95)
    akc[:, 5] = np.clip(dyaw + los.normal(0, 4, n), -45, 45)
    akc[:, 6] = -ja[:, 7] * 0.5                         # look horizontally
    return akc


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--areny", type=int, default=16)
    a.add_argument("--tempo", type=float, default=10000)
    a.add_argument("--sekundy", type=float, default=30)
    a.add_argument("--siec", default="", help="e.g. 2048x4 — evaluates such a network every step")
    a.add_argument("--urzadzenie", default="cpu")
    a.add_argument("--pokaz", type=int, default=0, help="print the state of arena 0 every N steps")
    args = a.parse_args()

    siec = None
    if args.siec:
        import torch
        szer, gleb = map(int, args.siec.split("x"))
        warstwy, wej = [], RAW * 2
        for _ in range(gleb):
            warstwy += [torch.nn.Linear(wej, szer), torch.nn.LayerNorm(szer), torch.nn.ReLU()]
            wej = szer
        warstwy.append(torch.nn.Linear(szer, 40))
        siec = torch.nn.Sequential(*warstwy).to(args.urzadzenie).eval()
        print(f"network {args.siec}: {sum(p.numel() for p in siec.parameters()):,} parameters on {args.urzadzenie}")

    los = np.random.default_rng(0)
    m = Most(args.areny, args.tempo)
    _, obs = m.obserwacje()
    print(f"connected: {args.areny} arenas, {m.n} bots")
    hp_przed = obs[:, HP].copy()
    kroki = trafienia = smierci = 0
    t0 = time.perf_counter()
    t_siec = 0.0
    while time.perf_counter() - t0 < args.sekundy:
        akc = bot_skryptowy(obs, los)
        if siec is not None:
            import torch
            ts = time.perf_counter()
            with torch.no_grad():
                wej = np.concatenate([obs, obs[np.arange(m.n) ^ 1]], 1)
                siec(torch.as_tensor(wej, device=args.urzadzenie)).cpu()
            t_siec += time.perf_counter() - ts
        m.akcje(akc)
        _, obs = m.obserwacje()
        kroki += 1
        if args.pokaz and kroki % args.pokaz == 0:
            for i in (0, 1):
                o = obs[i]
                print(f"  t{kroki:6d} bot{i}: pos ({o[X]:5.1f},{o[Z]:5.1f}) yaw {o[YAW]:6.1f} hp {o[HP]:4.1f} "
                      f"bar {o[PASEK]:.2f} aiming {o[CELUJE]:.0f} sprint {o[15]:.0f} dist {o[18]:.1f}")
        smierci += int(obs[:, ZGINAL].sum())
        spadek = (hp_przed - obs[:, HP]) > 0.01
        trafienia += int(spadek[obs[:, ZGINAL] < 0.5].sum())
        hp_przed = obs[:, HP].copy()
    dt = time.perf_counter() - t0
    m.zamknij()
    print(f"ticks/s: {kroki / dt:.0f}   bot steps/s: {kroki * m.n / dt:.0f}   "
          f"hits: {trafienia}   deaths: {smierci}   (network time: {100 * t_siec / dt:.0f}% of the loop)")


if __name__ == "__main__":
    main()
