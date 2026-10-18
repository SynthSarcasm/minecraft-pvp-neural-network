# pvp-bot-fabric - port to Minecraft 26.3 (used as an opponent by this project)

This directory is a modified copy of **[Stepan1411/pvp-bot-fabric](https://github.com/Stepan1411/pvp-bot-fabric)**
v0.0.15 (commit `f672e99`, Minecraft 1.21.11), released by its author under **The Unlicense** (see `LICENSE`).
All credit for the bot's combat logic goes to the original author. Our changes:

- **Port to 26.3**: mappings migrated from Yarn to Mojang names (`gradlew migrateMappings`), built with Fabric Loom 26.3
  like the other mods in this repository; API fixes (`world.item.*` packages, `ItemTags.AXES`,
  `swing(hand, SwingAnimation.DEFAULT, true)`, `drop(..., Prediction.SERVER_ONLY)`, `isSwinging()`,
  `InventoryHelper` via public `get/setSelectedSlot`).
- **Movement through Carpet's action pack** (`bot/BotMove.java`): Carpet overwrites a fake player's `zza/xxa` every tick,
  so writing them directly (as the original did) has no effect with Carpet.
- **Removed**: the usage-statistics reporter (`StatsReporter`, which sent data to the author's server by default),
  downloading bot names from the internet, client GUI packets, mixins, HeroBot integration.
- **Added**: `/pvpbot adopt <player>` - take control of an existing fake player (e.g. a bot of a `pvpmost` arena)
  without spawning it.

Build: `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew build` → `build/libs/pvpbot26-0.0.15-pvp26.jar`.
How the training code drives it (RCON commands, settings): `docs/design.md`, section "Opponents".
