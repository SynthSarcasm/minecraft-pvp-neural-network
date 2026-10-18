# Minecraft PvP neural network (Java 26.3, self-play PPO)

A **26M-parameter neural network that learned Minecraft Java 26.3 PvP by playing against itself** — on real Minecraft
servers, not in a simulator. A Fabric server mod builds arenas, spawns Carpet fake players and steps the game tick by
tick in lockstep with Python (PyTorch). The network went through six curriculum stages — fists, sword and axe, armor,
ping 0–200 ms with human-visible inputs only, items, and finally terrain, lava, potions and 1v3 fights — for ~510M
steps on a single MacBook Pro. A client mod lets the trained network play through a real Minecraft client.

Everything is here: the training code, both mods, a port of a third-party PvP bot used as an opponent, the dashboard,
and the trained network (as a release download). Take it and build on it.

## What the released network can do

Stage-6 champion **v28785**, measured (details and everything it cannot do: [docs/results.md](docs/results.md)):

| Test | Win rate |
|---|---|
| 1v1 vs [pvp-bot-fabric](https://github.com/Stepan1411/pvp-bot-fabric) (rule-based bot), full loadout, terrain | **93.5%** |
| 1v3 vs pvp-bot-fabric, everyone with the full loadout | **42.5–56.8%** |
| 1v3 vs pvp-bot-fabric with weapon + shield + food + healing | 11–23% |
| vs itself from before stage 6, on terrain (full loadout / weapons only) | 60.9% / 69.3% |
| through a real client, vs our rule-based bot (ping 0 / ~200 ms) | 94% / ~70% |

Learned (measured): melee with sword, axe and fists, the shield, the bow when fighting with fists, fighting on uneven
terrain, 1v1 and 1v3 against a rule-based bot, without forgetting earlier stages. Partly: healing potions. Not learned: offensive potions and
lava (rarely used, no gain), fishing rod (never used — in 26.3 it only pulls). **Never tested against human players.**

## How it works (short)

- **Environment**: real Minecraft 26.3 servers with the `pvpmost` mod (`mod/`): 23×23 glass arenas with random terrain,
  Carpet fake players, 1v1 and 1v3 arenas, ~13k steps/s per server. The server waits for Python every tick, so lag never
  changes the physics.
- **Fair input**: the network sees only what a player could infer from the screen — the opponent delayed by ping,
  its HP estimated from the network's own hits, only visible items — 508 numbers, relative to the player.
- **Network**: 508 → 2048 → 3 residual blocks → 9 action heads (movement, jump, sprint, attack, sneak, yaw, pitch,
  hotbar slot, right button) + value head; 26.3M parameters. New inputs and heads are appended with zero weights between
  stages, so nothing learned is lost.
- **Training**: PPO + self-play with a pool of older versions and a fixed "anchor" pool, a rule-based bot as a yardstick,
  dormant-neuron revival, small imitation lessons from a scripted teacher for item use, and a boxing-style reward
  (knockout > win on points > draw > loss on points > death).
- **Evaluation**: exams on a separate server (forgetting, progress, stage gain, per-item tests, 1v1/1v3 vs pvp-bot) that
  never feed learning, plus a web dashboard.

Full description: [docs/design.md](docs/design.md).

## Quick start

1. Install Java 25 and Python 3.12; `cd training && python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt`.
2. Build the mods: `cd mod && ./gradlew build`, `cd pilot && ./gradlew build` (with `JAVA_HOME` pointing to Java 25).
3. Download `champion_stage6.pt` from **Releases** into `training/runs/pvp7/` (as `champion_stage6.pt` and `siec_pokaz.pt`).
4. Set up a Fabric 26.3 server in `server-show/` with Fabric API, Carpet and `pvpmost`
   (Minecraft and these mods are not redistributed here — download links in the setup guide).
5. `server-show/start.sh`, then `cd training && .venv/bin/python -u show.py --nazwa pvp7 --z_losowaniem --lag --przedmioty --etap6`.
6. Join `127.0.0.1:25566` with Minecraft 26.3 and type `/walcz siekiera siekiera` to fight the network.

Step-by-step guide for everything — servers, watching and fighting, the dashboard, the assistant in your own client
(keys **R** on/off, **V** fight, **B** next target), its auto-start, training every stage, exams:
**[docs/setup.md](docs/setup.md)**.

The assistant plays through your own client on any server. Most public servers ban automated play.

## Repository map

| Path | Content |
|---|---|
| `training/` | Python: training (`train.py`), network (`network.py`), features/rewards (`features.py`), bots, exams, show, dashboard, assistant (`assistant.py`) |
| `mod/` | server mod `pvpmost` (Fabric) |
| `pilot/` | client mod `pvppilot` (assistant, pilot, recording camera), assistant test server, auto-start template |
| `third_party/pvpbot26/` | pvp-bot-fabric ported to 26.3 (opponent in stage 6; original license kept) |
| `server*/` | `start.sh` and `server.properties.template` for each server |
| `docs/` | [design](docs/design.md), [setup](docs/setup.md), [results](docs/results.md), [glossary](docs/glossary.md) |

Code identifiers are Polish (the project started in Polish); [docs/glossary.md](docs/glossary.md) translates them.

## Requirements

macOS on Apple Silicon is the tested platform (PyTorch MPS). Java 25, Python 3.12, `torch`, `numpy`, `websockets`.
Linux/Windows with CUDA should work with small changes (see the end of the setup guide).

## License

MIT ([LICENSE](LICENSE)). `third_party/pvpbot26/` keeps The Unlicense of the original project; other bundled files:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
