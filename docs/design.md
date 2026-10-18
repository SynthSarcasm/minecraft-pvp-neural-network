# How it is built and trained

This document describes the system as it is now: the environment, the server mod and the Python protocol, what the
network observes and controls, the reward, the network, the training algorithm, the curriculum of stages, and how the
network is evaluated. Setup instructions are in [setup.md](setup.md), current results in [results.md](results.md).
Identifiers in the code are Polish; [glossary.md](glossary.md) translates them.

## 1. Overview

```
 Python (training/)                                     Minecraft 26.3 servers: Fabric + Carpet + pvpmost mod (mod/)
 train.py: PPO learner on GPU (MPS)  ◄── collector threads ──TCP──►  server1/, server2/  (32 arenas each, up to 10,000 tick/s)
 exam6.py / watchdog.py  ──────────────────────────────TCP──►  server3/           (exams; not used for learning)
 show.py ─► panel.py (web dashboard, :8780)  ────────────TCP──►  server-show/      (20 tick/s; humans can join and fight)
 assistant.py  ─────────── TCP 127.0.0.1:8790 ──────────────►  a real Minecraft client with the pvppilot mod (pilot/)
```

- The network plays on **real Minecraft servers**, not in a re-implementation of the game. Each server runs our mod
  `pvpmost`, which builds glass arenas, spawns **Carpet fake players** and steps the game **in lockstep with Python**:
  every tick the mod sends all observations and waits for the actions. Game time stops while Python thinks, so slow
  Python never changes the physics (verified: the same sprint path is identical to 6 decimal places at 20 tick/s,
  at 10,000 tick/s and with an artificial 200 ms delay per tick).
- Training servers run with the tick rate set to 10,000; in practice ~200–400 ticks/s with 32 arenas. Two servers
  give ~9,000 network decisions per second on a MacBook Pro M4 Pro.
- One "step" = one tick of one bot. One "update" = one PPO update on a batch of 256 ticks from one server.

## 2. The arena (mod `pvpmost`, `mod/src/main/java/pl/pvp/bridge/PvpBridge.java`)

- An arena is a **23×23 glass cage** (`POL = 11`), 12 blocks high, with a 4-block deep pit layer below the flat floor;
  arena centers are 48 blocks apart, 8 per row, in an empty void world.
- **Terrain** per arena (`generujTeren`): 2–5 gentle hills/valleys (height −2..+5), in 60% of arenas 1–2 pits (−3),
  half of them with **lava** at the bottom, 0–3 cover pillars. Grass on top, dirt below. Every reset restores the
  terrain (removes placed blocks, fire and spilled lava). Python chooses per round: same terrain, new random terrain,
  or flat.
- **Arena types**: 1v1 arenas (2 players) and, at the end of the list, **1v3 arenas** (player 0 = the network, players
  1–3 = enemies). A player "out of play" (dead in 1v3, or sitting a round out) is parked in a glass box above the
  ceiling, invulnerable, until the next reset.
- **Fake players** are driven only through **Carpet's action pack** (`setForward/setStrafing/setSneaking/setSprinting`);
  writing `zza/xxa` directly is overwritten by Carpet every tick. Turning is clamped to 45°/tick (yaw) and 30°/tick (pitch).
  Bots are always forced to survival mode on reset (a restored bot in creative mode has 5 blocks of reach instead of 3).
- **Attacks behave like a client**: an attack only happens when the player is not using an item (in the real client the
  left button is ignored while the shield is up, while eating or drawing a bow). Attack codes from Python:
  1 = the server checks the crosshair (no lag), 2 = a lagged "client" saw a hit (the server only checks reach 3+1 blocks
  and line of sight, like an anticheat with ping tolerance), 3 = the client saw a miss (swing only). Every attack swings
  and resets the attack-strength bar, like in the game.
- The order in which the players of an arena act is **random every tick** (a fixed order gave one seat an edge).
- **Deaths** are cancelled (HP set to 1) and flagged; an arena is reset only after Python has seen the death flag.
- **Loadout** per round (`uzbroj`): hotbar 0 weapon (fists / diamond sword / diamond axe), 1 bow (+16 arrows),
  2 golden apples ×2, 3 steaks ×8, 4 fishing rod, 5 cobblestone ×32, 6 lava bucket, 7 healing II splash potions ×3,
  8 one potion from a list of 10 (drinkable strength II / speed II / fire resistance / regeneration II; splash strength II,
  speed II, harming II ×2, poison II ×2, slowness ×2, weakness ×2); shield in the off hand. Python sends a bit mask per
  player per round (1 bow, 2 food, 4 shield, 8 rod, 16 blocks, 32 lava, 64 healing, 128 potion). **With a weapon the
  player gets full diamond armor, with fists no armor.**
- **Right button** like a client: a click uses the item in hand (or raises the shield if the item does nothing),
  holding keeps drawing/eating/blocking, releasing shoots/stops; blocks are placed on the face under the crosshair.
- Show server only: a real player can type **`/walcz`** to take the place of a bot in the last 1v1 arena
  (`/walcz siekiera siekiera` = both with an axe and full loadout; `piesci` = fists, `miecz` = sword, `siekiera` = axe;
  plain `/walcz` leaves). An optional password file `pvpmost_haslo.txt` makes joining players type `/haslo <password>`
  within 30 s.
- Game rules set by the mod: no daylight cycle, no weather, no mob spawning, `send_command_feedback=false`,
  difficulty normal. Natural regeneration stays on.

## 3. Protocol (Python ↔ mod, TCP on 127.0.0.1, little-endian)

1. Python connects and sends `int32 arenas, float32 tick_rate, int32 n_1v3`.
2. Every tick the mod sends `int32 tick, int32 n_players`, then `float32[n_players × 249]` (RAW observations).
3. Python answers with `float32[n × 10]` actions, `uint8[arenas]` reset requests (0 none, 1 reset, 2 reset with new
   random terrain, 3 reset on flat ground), and per player `uint8` weapon for the next round (255 = sit out),
   `uint8` item mask, `uint8` potion byte (bits 0–3 potion kind, bits 4–5 starting slot, bit 6 start with 10 HP),
   `uint8` target index within the arena.
4. The mod accepts **one connection at a time**; disconnecting Python makes all fake players invulnerable and returns
   the server to 20 tick/s.

**Action (10 floats)**: forward (−1..1), strafe (+1 = left), jump, sprint, attack (code 1/2/3), yaw turn (degrees),
pitch turn, sneak, hotbar slot (0 = no change, 1–9 = slot 0–8), right button (pressed 0/1).
NaN in the first field = "do not control this player" (used when pvp-bot drives it).

**Observation (RAW, 249 floats per player)** — indices are named in `training/bridge.py`:

| Fields | Content |
|---|---|
| 0–22 | position relative to the arena, velocity, yaw, pitch, HP, food, saturation, attack-strength bar, hurt time, invulnerability, on ground, sprinting, crosshair on target, died, distance to target, sneaking, is human, weapon, armor points |
| 23–49 | selected slot, item category in hand, using an item (+ for how long), shield raised / disabled, counts of arrows, apples, steaks, blocks, round item mask, absorption, regeneration, bobber state and position, own arrow in flight (position, velocity), per-round counters (arrow hits, blocked hits, placed blocks) |
| 50–124 | blocks around the player: 3 levels × 5 × 5, in the player's own frame (forward/right) |
| 125–150 | lava bucket state, healing potions, slot-8 potion kind and count, 7 effects (level, seconds), fire time, in lava, burning, which players of the arena are visible (terrain occlusion), out of play |
| 151–248 | 7×7 surface-height map and 7×7 lava map around the player, in its own frame |

## 4. What the network sees: the "fair" input (`training/features.py`)

The network only gets information a human could infer from the screen:

- **Own state** in full; the **opponent as seen with ping**: its position, velocity and actions come from the
  observation of 1–3 ticks ago (`Lag`), its HP is **20 minus the damage this player has dealt to it** in the round
  (a human cannot see other players' hearts or regeneration), from its inventory only what is visible (item in hand,
  drawing/eating/blocking, arrow in flight, bobber, burning, effect particles — not its stack counts).
- The "crosshair on target" flag is recomputed client-side on the delayed opponent (`celownik`), because the mod's flag
  uses the true position (agreement with the mod's raycast without lag: 99.994%).
- Everything is expressed **relative to the player** ("2 blocks ahead, slightly to the right"), not in world coordinates.

Network input, **508 numbers** (`WEJSCIE`):

| Part | Size | Content |
|---|---|---|
| tick stack | 4 × 41 | 41 features per tick (own state, opponent relative position/angle/velocity, crosshair, opponent HP estimate, round time), last 4 ticks |
| weapons | 7 | round damage balance, own weapon (one-hot), opponent weapon (one-hot) |
| lag | 18 | actions already sent but not yet executed (up to 2 × 8) + both delays |
| items | 138 | own items (34), opponent's visible items (25), blocks around (75), slot/right button in flight (4) |
| stage 6 | 181 | own bucket/potions/effects/fire, terrain and lava maps, opponent's stage-6 visible state, in 1v3: two other enemies (position, motion, HP estimate, weapon, shield, aiming at me, visible) |

**Ping model** (`features.Lag`): every round each player draws a ping of 0–200 ms (15% of rounds have none). An action
arrives after `round(ping/100)` ∈ {0,1,2} ticks; the opponent is seen `actions + 1` ticks old (≤ 3; the client also
smooths other players' movement). A player sees itself up to date. The attack hit is decided by the "client" at click
time on the delayed opponent (attack code 2/3). Rule-based bots play without lag.

## 5. What the network controls: 9 action heads

`GLOWY = [9, 2, 2, 2, 2, 15, 9, 10, 3]` — 54 logits:

| Head | Choices |
|---|---|
| movement | 9 = forward/none/back × left/none/right |
| jump, sprint, attack, sneak | on/off each |
| yaw turn | −45, −30, −20, −12, −6, −3, −1, 0, 1, 3, 6, 12, 20, 30, 45 degrees |
| pitch turn | −20, −10, −5, −2, 0, 2, 5, 10, 20 degrees |
| slot | no change, or hotbar slot 0–8 |
| right button | keep as is / press / release (holding happens by itself, so random exploration can discover a 32-tick eat or a full bow draw) |

## 6. Reward and round rules (`features.Sedzia`, one place for training, exams and the show)

- Every tick: `0.05 × (damage dealt − damage taken)` (only HP drops count, not regeneration).
- **1v1 round** ends with a death or after 1,200 ticks (60 s). Terminal reward like a boxing scorecard:
  **kill +1 > win on points +0.5 > draw −0.8 (both) > loss on points −0.75 > death −1**. "Points" = the round's damage
  balance; a draw is |balance| < 2 HP. (A draw penalty that was too mild, −0.5, let self-play drift into both copies
  circling and waiting — a "dance" that loses to a healthy version; −0.8 removed it. A draw must still cost less than
  death, otherwise dying becomes free.)
- **1v3 round** (90 s): +0.5 per enemy taken down, +0.5 for taking down all, −1 for own death; at time-out the
  balance decides as in 1v1. The network targets the nearest living enemy (switching only if another is 1.5 blocks
  closer); enemies target the network.
- The observation in which a round ended is not used for learning; arenas reset immediately.
- Loadouts are drawn per round from weighted lists (`ZESTAWY_ETAP6`: fists with/without an item, weapons only,
  weapon + one item, weapon + shield + healing, full loadout 24%; 1v3: `ZESTAWY_MULTI` and 1–3 enemies).

## 7. The network (`training/network.py`)

```
input 508 → running mean/variance normalization (clipped ±5) → Linear 508→2048 + ReLU
          → 3 residual blocks: x + W2(ReLU(W1(LayerNorm x)))   (W2 starts at zero)
          → LayerNorm → ReLU → policy head Linear 2048→54 (orthogonal init 0.01)
                             → value head  Linear 2048→1
```

**26,349,623 parameters.** Large on purpose: in the author's earlier project a 12.8M network learned slower at first
but ended clearly ahead of a 154k one; dead-neuron measurements here show spare capacity rather than too little.

**Growing the network between stages without forgetting** (`dopasuj`): new inputs are appended at the end with zero
weights, new heads/slots are appended with zero weights and a bias. The network computes exactly the same as before
(checked: output difference 0.0) and learns to use the new parts. Older checkpoints loaded as opponents get a −30 bias
on heads they never had ("never switch slots / never press right"), so they play exactly like in their own stage.

## 8. Training (`training/train.py`)

- **PPO** with GAE (γ = 0.995, λ = 0.95), clip 0.2, value loss ×0.5, gradient clipping 0.5, advantage normalization,
  minibatch 4,096, 2 epochs, entropy bonus 0.001, Adam (lr 3e-5 to 5e-5 in later stages).
- **Parallel collection**: one collector thread per server runs a CPU copy of the network (weights republished after
  every update) and pushes 256-tick batches into a queue; the main thread learns on the GPU. Version lag ~2 updates,
  which PPO tolerates. (Threads share the GIL, so a third training server adds nothing; processes would.)
- **Arena roles** per server: self-play (both sides = current network, both learn), **pool** (vs one of the last 10
  saved versions, plus a **fixed pool** of known-healthy older versions as an anchor against self-play drift),
  **bot** (vs the rule-based bot v2 — a fixed yardstick), 1v3 arenas vs bot v2 or vs **pvp-bot-fabric** (driven over RCON).
- **Saving**: light fp16 copy for the show every 5 updates, full checkpoint with optimizer every 10, a pool copy every 20,
  a permanent archive copy every 200 (the pool alone once lost the best healthy version). Scalars stay fp32 in fp16 copies
  (the normalization counter overflows fp16).
- **Dormant-neuron revival (ReDo, Sokar et al. 2023)** every 100 updates: neurons with activity < 1% of the layer mean get
  fresh input weights and zero output weights (the decision changes by ~0.04 logits). Motivation: continual learning over
  stages loses plasticity (Dohare et al., Nature 2024); before revival 30–60% of block neurons were dead.
- **Exploration help for new skills**: when a new action is added, its head bias is shifted so that it is picked ~1–5%
  of the time (otherwise PPO never discovers it), optionally with a decaying extra entropy bonus for chosen heads
  (`--entropia_skok`, `--glowy_zachety`).
- **Lessons from a teacher** (`bots.BotPrzedmioty`, `collect_lessons.py`): a rule-based teacher that uses items sensibly
  records (input, its decision) pairs; the trainer adds a small imitation loss **only for chosen heads** (slot, right
  button, pitch) so the teacher suggests *when to take out an item* without imposing its weaker fighting style.
- **Pause**: the file `training/PAUZA` (created by the show while a human fights) makes the trainer wait and release GPU memory.
- **Start of the curriculum**: behaviour cloning + DAgger of bot v2 (`imitate.py`, ~25 min) — the network starts at the
  bot's level (42–54% vs the bot), then PPO.

## 9. Curriculum

Each stage starts from the previous stage's checkpoint; inputs/heads are appended as described in section 7.

| Stage | Run | What is added | Notes |
|---|---|---|---|
| 1 | pvp2 | fists | BC + DAgger start, then PPO; ~99% vs bot v2 |
| 2 | pvp3 | diamond sword and axe | jump/sprint biases unblocked to ~5%; the network chose the axe (87:13 vs the sword) |
| 3 | pvp4 | diamond armor with weapons | draw penalty raised to −0.8 (see section 6) |
| 4 | pvp5 | ping 0–200 ms and the fair input | the pre-stage network lost 100% with just 1 tick of delay, because it relied on the server's true crosshair flag |
| 5 | pvp6 | bow, shield, food, fishing rod, blocks; slot and right-button heads | items tend to "die out" in exploration; fists+bow rounds and teacher lessons for the slot/right-button heads fixed it for bow and shield |
| 6 | pvp7 | terrain, lava, 10 potions, 1v3 vs pvp-bot-fabric; slots 6–8 | `new_stage6.py` prepares the run; ~165M steps |

Total ~510M steps. A stage is considered mastered by `watchdog.py` when, twice in a row: three progress tests show a
plateau (≤ 56% vs itself from ≥ 500 updates earlier), the bot gauge is above threshold, fists are not forgotten,
a minimum number of updates has passed, and the **stage gain** (win rate vs the pre-stage version with the new mechanics)
is above threshold.

## 10. Evaluation

- **Exams** (`exam.py`, `exam6.py`) run on a separate server and never feed learning: forgetting (fists vs the
  fists champion), progress (vs itself 300–500 updates earlier), stage gain (vs the pre-stage version), per-item tests
  (weapon + that single item vs the pre-stage version), 1v1 and 1v3 vs pvp-bot. In 1v1 tests our network alternates
  seats. A 60 s exam has ±8 points of noise; decisions use longer runs (`eval_final.py`, 120 s per test, 150–400 rounds).
- **Dashboard** (`panel.py` + `panel_web/`): win-rate curves, how rounds end, round length, item use, exams, mastery
  conditions, dead neurons, decision entropy, a live view of the arena, the network's "hands" (action probabilities)
  and value estimate.
- **Health check** (`health_check.py`, `watch_report.py`): KL, clip fraction, entropy, draws, distance, item use, exams, processes.

## 11. Opponents

- **bot v2** (`bots.BotV2`): a hand-written bot that aims at the torso with noise, keeps attack distance, strafes,
  attacks only with a (nearly) full bar, W-taps, and in half of the rounds goes for crits. It picks actions from the
  same discrete steps as the network, so it can be imitated 1:1.
- **pvp-bot-fabric** ([Stepan1411/pvp-bot-fabric](https://github.com/Stepan1411/pvp-bot-fabric), Unlicense), ported to
  26.3 in `third_party/pvpbot26` (see its README). A server-side rule bot with crits, strafing, shield, shield breaking with
  the axe, bow, food and potions; it attacks via `attack()` without a crosshair (in range = hit), reach 3.5, no ping.
  Python adopts fake players with `/pvpbot adopt botNNN`, orders `/pvpbot bot-management attack <bot> <target>` after
  every reset, and turns off `ranged-retreat` (otherwise in an arena it keeps backing off with the bow and never fights),
  `auto-target`, `revenge`, `target-bots` and `friendly-fire`. RCON must not be awaited in the lockstep loop (the server
  runs commands between ticks, while the tick waits for Python), so commands go from a separate thread.
- **FlyPvP** ([NuelNexus/flypvp](https://github.com/NuelNexus/flypvp), MIT), a small network from a simulator, via an
  adapter in `tournament.py` (its sense vector is rebuilt with its own simulator from our observations).

## 12. The assistant in a real client (`pilot/`, `training/assistant.py`)

```
 Minecraft client (pvppilot mod, Assistant.java)               Python (assistant.py, CPU, ~2 ms per decision)
 end of tick: RAW 249 for me and the target ──TCP 127.0.0.1:8790──► Sedzia.wejscie_sieci (508) → network
 start of next tick: keys / turn / click     ◄──────── action ────── sampled as in training
```

- The client mod builds the same 249-number observation **from the client's world** for the player and the target and
  executes the action like a human would: movement keys, camera turn, attack click (only goes through if the crosshair is
  on the target — no misses, no bystanders, no block mining), right button, hotbar slots.
- Client-side assumptions (verified field by field against a server recording): positions relative to a **virtual arena
  center** (midpoint at fight start, follows the player beyond 8 blocks); target velocity reconstructed from positions
  with the server's friction/gravity; target attack bar estimated from its last swing and weapon speed; invulnerability
  reported as 0 (as the training server did); target effects unknown; the network's 9-slot "virtual hotbar" is mapped onto
  whatever is on the real hotbar, and missing slots are masked out.
- The client already shows the target delayed, so Python does not delay it again — it only feeds the measured ping
  (from the player list) into the lag inputs. Note: servers average ping slowly; it ramps up over ~1.5–2 minutes after joining.
- **Camera smoothing** (default): turn speed accelerates by ≤ 6°/tick per tick, brakes faster, max 20°/tick yaw
  (raw network output can turn 45°/tick = 900°/s). **Calm mode**: when the target is far (> 6 blocks) or hidden, slower
  camera, tiny turns ignored, jumping only in front of an obstacle or in lava. `--surowo` disables both, `--bez_spokoju`
  only the calm mode.
- A fight ends when the target dies, disconnects, respawns as a new entity, is out of sight for 3 s, is > 32 blocks away,
  or both players are teleported at once (end of a round in an arena). The overlay then shows "No target" for 3 s.
- Keys: **R** network on/off (off = keys released immediately), **V** fight the suggested target / stop,
  **B** next suggestion. While on, the network only *suggests* the nearest player within 12 m; it attacks only after V,
  or automatically someone who damages the user.

## 13. Related work (at the time of writing)

Existing Minecraft PvP agents mostly train in a custom simulator and report problems transferring to the real game
(e.g. [rl-minecraft-ai](https://github.com/therohero/rl-minecraft-ai): 86% in simulation, circles in the real game;
[netherite-multiplayer](https://github.com/Yeyito777/netherite-multiplayer): BC → DAgger → PPO in a CUDA simulator, yaw
jitter in the real game). Mineflayer does not support 26.3 and runs on a fixed 50 ms clock. Training directly on real
servers with Carpet fake players and lockstep stepping avoids the simulator gap at ~13k steps/s per server.

## 14. Known limitations

- Only the 23×23 arena with gentle terrain was ever seen in training: no water, ladders, cliffs, buildings or crowds.
- Ping above 200 ms was never trained.
- The training-side opponent bots play without ping; humans were never part of training or evaluation.
- Collectors are threads (GIL); more servers would need processes.
- macOS-specific bits: `/usr/libexec/java_home` in scripts, `/opt/homebrew/bin/ffmpeg` in the recording code,
  `launchctl` for the auto-start, MPS as the default device (`--urzadzenie cuda` / `cpu` to change).
