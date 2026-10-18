# Setup and usage, step by step

Tested on **macOS on Apple Silicon** (MacBook Pro M4 Pro, 24 GB RAM). Other platforms should work with small changes
(see "Other platforms" at the end). All commands are run from the repository root unless stated otherwise.

Contents: [1. Requirements](#1-requirements) · [2. Python](#2-python) · [3. Build the mods](#3-build-the-mods) ·
[4. Get the trained network](#4-get-the-trained-network) · [5. Minecraft servers](#5-minecraft-servers) ·
[6. Watch the network and fight it](#6-watch-the-network-and-fight-it) ·
[7. The assistant in your own Minecraft client](#7-the-assistant-in-your-own-minecraft-client) ·
[8. Auto-start of the assistant](#8-auto-start-of-the-assistant) · [9. Training](#9-training) ·
[10. Stopping everything](#10-stopping-everything) · [11. Troubleshooting](#11-troubleshooting)

## 1. Requirements

- **Java 25** (e.g. [Eclipse Temurin 25](https://adoptium.net/)). The scripts find it with `/usr/libexec/java_home -v 25`.
- **Python 3.12**.
- **Minecraft Java Edition 26.3** to watch or play (the servers run in offline mode, so any 26.3 client can join).
- Optional: `ffmpeg` (recording fights from the client, expected at `/opt/homebrew/bin/ffmpeg`),
  [`bore`](https://github.com/ekzhang/bore) (`brew install bore-cli`, to show the network to a friend over the internet).
- Memory: training on two servers plus exams uses about 10 GB of RAM; the show alone about 2 GB.

## 2. Python

```bash
cd training
python3.12 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

All Python scripts are run from `training/` with `.venv/bin/python`. Every script has `--help`.

## 3. Build the mods

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
(cd mod && ./gradlew build)            # server mod  -> mod/build/libs/pvpmost-0.1.0.jar
(cd pilot && ./gradlew build)          # client mod  -> pilot/build/libs/pvppilot-0.1.0.jar
(cd third_party/pvpbot26 && ./gradlew build)  # optional: pvp-bot-fabric port -> third_party/pvpbot26/build/libs/pvpbot26-0.0.15-pvp26.jar
```

The first build downloads Minecraft and the Fabric toolchain into the Gradle cache (a few minutes).

## 4. Get the trained network

Download `champion_stage6.pt` (the stage-6 champion, ~53 MB) from the repository's **Releases** page and put it in two places:

```bash
mkdir -p training/runs/pvp7
cp ~/Downloads/champion_stage6.pt training/runs/pvp7/champion_stage6.pt   # used by the assistant (assistant.py)
cp ~/Downloads/champion_stage6.pt training/runs/pvp7/siec_pokaz.pt      # used by the show (show.py) and the panel
```

## 5. Minecraft servers

Nothing from Minecraft is in this repository; each server directory only has `start.sh` and a `server.properties.template`
template. The directories and their roles:

| Directory | Role | Game port | Python bridge | RCON |
|---|---|---|---|---|
| `server1/` | training server 1 | 25565 | 25599 | 25578 |
| `server2/` | training server 2 | 25567 | 25602 | 25579 |
| `server3/` | exams and tests | 25568 | 25603 | 25577 |
| `server-show/` | the show: normal speed, humans can join | 25566 | 25600 | — |

You only need `server-show/` to watch and fight the network; training needs `server1/` and `server2/`, exams `server3/`.

For each server directory you want (example for `server-show`):

1. Install the Fabric server for **26.3** with the installer from <https://fabricmc.net/use/server/>
   (Loader **0.19.5** or newer):
   ```bash
   java -jar fabric-installer.jar server -mcversion 26.3 -loader 0.19.5 -downloadMinecraft -dir server-show
   ```
   Several servers can share `libraries/`, `versions/` and `fabric-server-launch.jar` through symlinks to one install.
2. Put the mods into `server-show/mods/`:
   - **Fabric API** `0.163.0+26.3` — <https://modrinth.com/mod/fabric-api>
   - **Carpet** for 26.3 — <https://modrinth.com/mod/carpet>
   - our `mod/build/libs/pvpmost-0.1.0.jar`
   - training servers and `server3/` for stage-6 work also: `third_party/pvpbot26/build/libs/pvpbot26-0.0.15-pvp26.jar`
3. `cp server-show/server.properties.template server-show/server.properties`. The template already contains what the mod
   needs: an empty flat void world, `max-tick-time=-1` (the watchdog would kill a server running at 10,000 tick/s),
   `pause-when-empty-seconds=-1` (26.3 pauses empty servers), `online-mode=false`, `white-list=false`, bound to 127.0.0.1.
   For the servers with RCON (`server1`, `server2`, `server3`): replace `<your_rcon_password>` with a password of your choice
   **and** write the same password into `rcon_haslo.txt` in that directory (the Python side reads it from there).
   RCON is only used to command pvp-bot in stage 6; otherwise you can set `enable-rcon=false`.
4. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA); if you agree: `echo eula=true > server-show/eula.txt`.
5. Optional, show server only: a join password. `echo "your-password" > server-show/pvpmost_haslo.txt` — players then
   have 30 s to type `/haslo your-password`, or they are disconnected. No file = no password.
6. Start: `server-show/start.sh`. The server is ready when its log shows
   `pvpmost: waiting for Python on port 25600`. **Always start a server before the Python script that uses it.**

## 6. Watch the network and fight it

```bash
server-show/start.sh > server-show/server.log 2>&1 &
cd training
.venv/bin/python -u show.py --nazwa pvp7 --z_losowaniem --lag --przedmioty --etap6
```

- Join `127.0.0.1:25566` with a Minecraft 26.3 client. You start as a spectator above arena 0.
  Arena 0: network vs network; arena 1: network vs bot v2; arena 2: network vs bot v2, or vs **you**.
- **`/walcz siekiera siekiera`** — you and the network both get an axe and the full loadout (armor, bow, shield, food,
  lava, potions). Weapons: `piesci` = fists (no armor, no items), `miecz` = sword, `siekiera` = axe;
  `/walcz <yours> <network's>` gives different weapons; plain **`/walcz`** leaves the arena. Rounds last up to 60 s;
  the chat shows the result and your score. In the show the network plays with the delay of a local player.
- `--z_losowaniem` samples actions as in training (the network plays better that way than with its single most likely
  action). `show.py` reloads `siec_pokaz.pt` every 30 s if the file changes (e.g. during training).
- **Dashboard**: in another terminal `cd training && .venv/bin/python -u panel.py --nazwa pvp7`, then open
  <http://127.0.0.1:8780>. The live arena view works with just the show; the training charts need a training log.
- **Show for a friend over the internet**: `./show_for_friend.sh` starts the show server, `show.py` and a `bore`
  tunnel, and prints an address like `bore.pub:12345`. The friend adds it as a server (Minecraft 26.3) and uses `/walcz`.
  Expect ~250–300 ms of extra latency through bore. Stop with `./stop_show.sh`.

## 7. The assistant in your own Minecraft client

The network can play through your own client on a server where the mod is not installed. Most public servers ban
automated play, so a server you run yourself (e.g. `pilot/test_server`, below) is the simplest place to try it.

**One-time setup (≈10 minutes):**

1. Install the Fabric **client** for 26.3 (installer from <https://fabricmc.net/use/installer/>, game version 26.3,
   loader 0.19.5 or newer).
2. In the Minecraft launcher create a **new installation** with that Fabric version and a **separate game directory**,
   e.g. `~/Library/Application Support/minecraft-siec` (the auto-start in section 8 recognizes the game by this
   directory name). A separate directory keeps mods for other versions away from it.
3. Start that installation once and close it (this creates the `mods` folder). Copy into `minecraft-siec/mods/`:
   `pilot/build/libs/pvppilot-0.1.0.jar` and Fabric API `0.163.0+26.3`.

**Every time:**

1. Start the network: double-click `assistant.command` (or `cd training && .venv/bin/python -u assistant.py`).
   Keep the terminal window open. It waits for the game and reconnects by itself.
2. Start Minecraft with that installation and join a server. The overlay in the top-left corner shows
   **"PvP network: OFF [R]"**.
3. Keys (change them in Options → Controls → Key Binds → "PvP network"):
   - **R** — network on/off. Off releases all keys immediately; you are in control.
   - **V** — fight the suggested target (the nearest player within 12 m, shown as "Suggestion: Name 4.2 m") / stop the fight.
   - **B** — next suggestion.
   - While the network is on and not fighting, a player who damages you automatically becomes the target.
   - During a fight the mouse does not turn the camera (the network aims). Opening chat or the inventory makes the
     network release the keys.
4. The terminal prints every fight (time, damage dealt/taken); a line per fight is appended to `training/assistant_fights.csv`.

Options of `assistant.py` (also accepted by `./assistant.command <options>`):
`--surowo` (no camera smoothing and no calm mode — stronger, but the camera jumps), `--bez_spokoju` (smoothing without
calm mode), `--najpewniejsze` (no sampling), `--nagrywaj DIR` (record every fight to mp4 through ffmpeg),
`--zrzut` (save observations/actions per fight), `--siec PATH` (another checkpoint).

**Test server for the assistant** (`pilot/test_server/`): a normal world without `pvpmost`, only Fabric API + Carpet,
port 25570. Set it up like section 5 (template `server.properties.template`; copy `pilot/test_server/datapack/assistant_test`
into `pilot/test_server/swiat/datapacks/` for the `test:respawn` / `test:teleport_both` functions). Spawn opponents with
Carpet, e.g. `/player Alfa spawn` (give yourself op first in the server console: `op <your nick>`).

**Developer client** (no launcher needed, offline nick `SiecPilot`): `cd pilot && ./gradlew runClient` joins the show
(127.0.0.1:25566); `./gradlew runClient -Pserwer=127.0.0.1:25570` joins the test server. If the file
`pilot/run/pilot_haslo.txt` exists, the dev client types `/haslo <its content>` after joining.

**Measuring the assistant**: `training/client_arena.py` turns server 3 into an arena (bot v2 vs your client in the
`/walcz` arena, results to `client_arena.csv`); `training/delay_proxy.py --z 25590 --do 25568 --ms 100` adds ~200 ms of ping
(join `127.0.0.1:25590`).

## 8. Auto-start of the assistant

`training/game_watcher.py` runs in the background and starts `assistant.py` whenever a Minecraft process with
`--gameDir …minecraft-siec` is running (and stops it when the game closes). To install it as a macOS LaunchAgent:

```bash
P="$(pwd)"
sed -e "s|__PROJECT__|$P|g" -e "s|__HOME__|$HOME|g" pilot/autostart/pl.pvp.assistant.plist > ~/Library/LaunchAgents/pl.pvp.assistant.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/pl.pvp.assistant.plist
```

Logs: `~/Library/Logs/pvp-assistant.log` (watcher) and `training/assistant_auto.log` (network).
Turn it off: `launchctl bootout gui/$(id -u)/pl.pvp.assistant`; remove it for good: also delete the plist.
While the service is installed, `assistant.command` only says so and exits (two network clients would conflict).

## 9. Training

Training writes to `training/runs/<run>/`: `log.csv`, `siec.pt` (full checkpoint with optimizer, every 10 updates),
`siec_pokaz.pt` (light fp16 copy, every 5), `pula/` (opponent pool), `archiwum/` (permanent copy every 200 updates).
A checkpoint for the next stage is created by copying `siec.pt` into the new run's directory: `train.py --wznow`
appends new inputs/heads automatically.

Honest note: the current code always builds the stage-6-sized network (508 inputs, 54 logits); earlier stages are
reproduced with flags. The released champion was trained incrementally with older versions of the code, so a rerun
will not be identical. With the current mod, weapons always come with armor, so stages 2 and 3 are one stage.

**Start servers 1 and 2** (`server1/start.sh`, `server2/start.sh`, each in its own terminal or in the background) and wait
for `waiting for Python`. Then, from `training/`:

**Stage 1 — fists.** Imitation of bot v2, then PPO self-play:
```bash
.venv/bin/python -u imitate.py --nazwa pvp2          # ~25 min, uses server 1
.venv/bin/python -u train.py --nazwa pvp2 --wznow --lr 5e-5 --entropia 0.001 --epoki 2 --porty 25599,25602 >> train_pvp2.log 2>&1
```

**Stage 2–3 — sword and axe (with armor).** Copy the checkpoint, add weapon loadouts, help exploration of jump/sprint:
```bash
mkdir -p runs/pvp3 && cp runs/pvp2/siec.pt runs/pvp3/ && cp runs/pvp2/siec_pokaz.pt runs/pvp3/przed_etapem.pt
.venv/bin/python -u train.py --nazwa pvp3 --wznow --bronie --kara_remis 0.8 --ozyw_na_start --entropia_skok 0.01 --wygaszanie 1500 \
    --lr 5e-5 --entropia 0.001 --epoki 2 --porty 25599,25602 >> train_pvp3.log 2>&1
```
(Originally the jump and sprint biases were also shifted so that each was picked ~5% of the time; without it they start
near 0.001% and the entropy bonus alone barely moves them.)

**Stage 4 — ping and the fair input:**
```bash
mkdir -p runs/pvp5 && cp runs/pvp3/siec.pt runs/pvp5/ && cp runs/pvp3/siec_pokaz.pt runs/pvp5/przed_etapem.pt
.venv/bin/python -u train.py --nazwa pvp5 --wznow --bronie --lag --kara_remis 0.8 --lr 5e-5 --entropia 0.001 --epoki 2 --porty 25599,25602 >> train_pvp5.log 2>&1
```

**Stage 5 — bow, shield, food, fishing rod, blocks.** Teacher lessons for the slot and right-button heads (server 3 free):
```bash
.venv/bin/python -u collect_lessons.py --port 25603 --umie luk --bronie 0,0 --maska 1 --przedrostek lekcje_przedm --zwykle 0.05 --sekundy 300
mkdir -p runs/pvp6 && cp runs/pvp5/siec.pt runs/pvp6/ && cp runs/pvp5/siec_pokaz.pt runs/pvp6/przed_etapem.pt
.venv/bin/python -u train.py --nazwa pvp6 --wznow --bronie --przedmioty --lag --kara_remis 0.8 --lr 3e-5 --entropia 0.001 --epoki 2 \
    --porty 25599,25602 --lekcje "lekcje_przedm_*.npz" --glowy_lekcji 7,8 --waga_czlowieka 0.1 >> train_pvp6.log 2>&1
```
(New heads are appended with a "never" bias; to let PPO discover them, their bias has to be raised to ~1% per slot,
as `new_stage6.py` does for stage 6.)

**Stage 6 — terrain, lava, potions, 1v3 vs pvp-bot.** Servers 1–2 need the pvp-bot jar and RCON (section 5).
`new_stage6.py` builds the run from the end of stage 5 (it expects `pvp6/siec.pt`, `pvp6/mistrz_przedmiotow.pt` and
`pvp6/pula_stala/s012150_ping.pt` for the fixed pool — edit those lines if your files differ):
```bash
.venv/bin/python -u new_stage6.py --z pvp6 --do pvp7
.venv/bin/python -u collect_lessons.py --port 25603 --sekundy 600           # teacher lessons with lava/healing/potions -> lekcje6_*.npz
.venv/bin/python -u train.py --nazwa pvp7 --wznow --bronie --przedmioty --etap6 --lag --kara_remis 0.8 --lr 3e-5 --entropia 0.001 \
    --epoki 2 --porty 25599,25602 --rcony 25578,25579 --multi 12 --multi_pvpbot 8 --rozklad_botow 0.1,0.25,0.65 \
    --udzial_pula 0.25 --lekcje "lekcje6_*.npz,lekcje_przedm_*.npz" --glowy_lekcji 7,8 --waga_czlowieka 0.1 \
    --waga_rzut 1 --start_przedmiot 0.25 >> train_pvp7.log 2>&1
```

**Continue training from the released champion** (no optimizer state is published, so the optimizer starts fresh):
```bash
mkdir -p runs/pvp8
.venv/bin/python -c "
import torch; from egzamin import wczytaj
s, akt = wczytaj('runs/pvp7/champion_stage6.pt')
torch.save({'siec': s.state_dict(), 'opt': None, 'aktualizacja': akt, 'kroki': 0}, 'runs/pvp8/siec.pt')"
cp runs/pvp7/champion_stage6.pt runs/pvp8/przed_etapem.pt
# then the stage-6 train.py command with --nazwa pvp8
```

**Exams, watchdog, monitoring** (exams use server 3; only one script may use a server at a time):
```bash
.venv/bin/python -u exam6.py --nazwa pvp7 --sekundy 60 --co_ile 30       # stage-6 exams -> runs/pvp7/egzamin6.json
.venv/bin/python -u exam.py --nazwa pvp6 --lag --przedmioty --wzor runs/pvp6/przed_etapem.pt \
    --wzor_etapu runs/pvp6/przed_etapem.pt --port 25603 --sekundy 240 --co_ile 900   # stage 1–5 style exams
.venv/bin/python -u watchdog.py --nazwa pvp6 --start <first update of the stage> --prog_zysku 60 --prog_bota 65 --prog_przedm 60
.venv/bin/python -u panel.py --nazwa pvp7 --start <first update of the stage>         # http://127.0.0.1:8780
.venv/bin/python health_check.py --nazwa pvp6 --poprzedni pvp5                            # one-off health report
.venv/bin/python watch_report.py --nazwa pvp7                                                 # one-off stage-6 report
.venv/bin/python -u eval_final.py --port 25603 --rcon 25577 --serwer server3 runs/pvp7/champion_stage6.pt  # long final evaluation
```

`exam6.py` and `eval_final.py` compare against `runs/pvp7/przed_etapem.pt` (the pre-stage network) and the
fists champion `runs/pvp2/siec.pt`; with only the released champion, point them at files you have (`--wzor`, or
edit the two paths in `main()` of `eval_final.py`).

Useful `train.py` options (all have `--help`): `--areny` (arenas per server, 32), `--udzial_pula` / `--udzial_bot`
(share of pool / bot arenas), `--co_ile_ozywiac` (dormant-neuron revival interval), `--godziny` (stop after N hours),
`--urzadzenie` (`mps`, `cuda`, `cpu`). Keep the Mac awake during long runs:
`caffeinate -i -w $(pgrep -f "train.py --nazwa pvp7")`.

**Tournament against third-party opponents** (server 3): `tournament.py --przeciwnik pvpbot --bronie 2,2 --maska 31`
(needs the pvp-bot jar in `server3/mods` and RCON), `--przeciwnik fly` needs FlyPvP cloned into `third_party/flypvp`
(`git clone https://github.com/NuelNexus/flypvp third_party/flypvp`), `--przeciwnik v2` uses our bot.

## 10. Stopping everything

Stop Python first (the servers then return to waiting), then the servers:
```bash
pkill -f "train.py --nazwa"; pkill -f "egzamin"; pkill -f "watchdog.py --nazwa"; pkill -f "show.py --nazwa"; pkill -f "panel.py --nazwa"
pkill -f "bore local"; pkill -f "tournament.py"; pkill -f KnotClient
sleep 3; pkill -f "fabric-server-launch.jar nogui"
rm -f training/PAUZA
```
A server stuck in lockstep (Python killed at a bad moment) may hang while closing; `kill -9` it — the arenas are rebuilt
on every start anyway.

## 11. Troubleshooting

- **Python hangs on connect / "server closed the connection"**: the mod accepts one connection at a time — check that no
  other script uses the same server (`lsof -iTCP:25599`).
- **"still missing N bots"** in the server log: Carpet refused to spawn bots with names that were just removed; restart the server.
- **Stage-6 servers hang at spawn after changing the arena layout**: pvp-bot restores its old bots from
  `server*/config/pvpbot/worlds/*/bots.json` — delete that file and restart.
- **Training is paused**: the file `training/PAUZA` exists (created while a human fights in the show). Delete it if nobody is fighting.
- **Cannot join the show**: 26.3 creates new servers with `white-list=true`; the template disables it.
- **The project folder name contains "ć"**: quote paths in the shell.
- **pkill with "+" in the pattern** does not match (regex); kill by PID instead.

## Other platforms

The Python code is platform-independent; use `--urzadzenie cuda` (or `cpu`) for training. Replace
`$(/usr/libexec/java_home -v 25)` in the `start.sh` scripts and build commands with your Java 25 path, the ffmpeg path in
`pilot/src/main/java/pl/pvp/pilot/Pilot.java` if you record videos, and use another service manager instead of
`launchctl` for the auto-start.
