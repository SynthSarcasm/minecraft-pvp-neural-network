package org.stepan1411.pvp_bot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.stepan1411.pvp_bot.bot.BotCombat;
import org.stepan1411.pvp_bot.bot.BotFaction;
import org.stepan1411.pvp_bot.bot.BotKits;
import org.stepan1411.pvp_bot.bot.BotManager;
import org.stepan1411.pvp_bot.bot.BotNameGenerator;
import org.stepan1411.pvp_bot.bot.BotPath;
import org.stepan1411.pvp_bot.bot.BotSettings;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class BotCommand {

    private static final SuggestionProvider<CommandSourceStack> BOT_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String bot : BotManager.getAllBots()) {
                if (bot.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(bot);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> KIT_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String kit : BotKits.getKitNames()) {
                if (kit.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(kit);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (var player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                String name = player.getName().getString();
                if (name.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PATH_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String pathName : BotPath.getAllPaths().keySet()) {
                if (pathName.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(pathName);
                }
            }
            return builder.buildFuture();
        };

    private static LiteralArgumentBuilder<CommandSourceStack> cmd(String literal, String description) {
        return Commands.literal(literal)
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("§7[Tip] §a" + description), false);
                return 1;
            });
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(cmd("pvpbot", "PvP Bot control")
            .requires(s -> true)

            // ========== ADOPT (port pvpmost) ==========
            // take over an existing fake player (e.g. a bot from a pvpmost arena) without spawning
            .then(Commands.literal("adopt")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> { BotManager.adopt(StringArgumentType.getString(ctx, "name")); return 1; })))

            // ========== SPAWN ==========
            .then(Commands.literal("spawn")
                .executes(ctx -> spawn(ctx, null))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))))

            // ========== REMOVE ==========
            .then(cmd("remove", "Remove a bot")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(BOT_SUGGESTIONS)
                    .executes(BotCommand::remove)))

            // ========== REMOVEALL ==========
            .then(Commands.literal("reload")
                .executes(BotCommand::reload))
            .then(Commands.literal("removeall")
                .executes(BotCommand::removeAll))

            // ========== SETTINGS ==========
            .then(buildSettings())

            // ========== BOT-MANAGEMENT ==========
            .then(cmd("bot-management", "Bot management")
                .then(cmd("mass-spawn", "Mass spawn bots")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 10000))
                        .executes(BotCommand::massSpawn)))
                .then(cmd("attack", "Attack a target")
                    .then(Commands.argument("botname", StringArgumentType.word())
                        .suggests(BOT_SUGGESTIONS)
                        .then(Commands.argument("target", StringArgumentType.word())
                            .suggests(PLAYER_SUGGESTIONS)
                            .executes(BotCommand::botAttack))))
                .then(cmd("stop-attack", "Stop attack")
                    .then(Commands.argument("botname", StringArgumentType.word())
                        .suggests(BOT_SUGGESTIONS)
                        .executes(BotCommand::botStopAttack)))
                .then(cmd("inventory", "Show bot inventory")
                    .then(Commands.argument("botname", StringArgumentType.word())
                        .suggests(BOT_SUGGESTIONS)
                        .executes(BotCommand::botInventory)))
                .then(Commands.literal("list")
                    .executes(BotCommand::botList))
                .then(cmd("path", "Path management")
                    .then(cmd("create", "Create a path")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(BotCommand::pathCreate)))
                    .then(cmd("delete", "Delete a path")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathDelete)))
                    .then(cmd("add-point", "Add point to path")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathAddPoint)))
                    .then(cmd("remove-point", "Remove point from path")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("index", IntegerArgumentType.integer())
                                .executes(BotCommand::pathRemovePoint))
                            .executes(BotCommand::pathRemovePointLast)))
                    .then(cmd("clear", "Clear all path points")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathClear)))
                    .then(cmd("loop", "Toggle path looping")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(BotCommand::pathLoop))))
                    .then(cmd("start", "Start bot on path")
                        .then(Commands.argument("bot", StringArgumentType.word())
                            .suggests(BOT_SUGGESTIONS)
                            .then(Commands.argument("path", StringArgumentType.word())
                                .suggests(PATH_SUGGESTIONS)
                                .executes(BotCommand::pathStart))))
                    .then(cmd("stop", "Stop bot on path")
                        .then(Commands.argument("bot", StringArgumentType.word())
                            .suggests(BOT_SUGGESTIONS)
                            .executes(BotCommand::pathStop)))
                    .then(Commands.literal("list")
                        .executes(BotCommand::pathList))
                    .then(cmd("show", "Show/hide path particles")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("visible", BoolArgumentType.bool())
                                .executes(BotCommand::pathShow))))
                    .then(cmd("info", "Path info")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathInfo)))
                    .then(cmd("distribute", "Distribute bots along path")
                        .then(Commands.argument("path", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathDistribute)))
                    .then(cmd("start-near", "Start bots near path")
                        .then(Commands.argument("path", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1))
                                .executes(BotCommand::pathStartNear))))
                    .then(cmd("stop-all", "Stop all bots on path")
                        .then(Commands.argument("path", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathStopAll)))
                    .then(cmd("walk-type", "Path walk type")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("bhop");
                                    builder.suggest("sprint");
                                    builder.suggest("walk");
                                    return builder.buildFuture();
                                })
                                .executes(BotCommand::pathWalkType))))))

            // ========== KIT ==========
            .then(cmd("kit", "Kit management")
                .then(cmd("create-kit", "Create kit from inventory")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommand::kitCreate)))
                .then(cmd("delete-kit", "Delete a kit")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(KIT_SUGGESTIONS)
                        .executes(BotCommand::kitDelete)))
                .then(cmd("give-kit", "Give kit to player")
                    .then(Commands.argument("playername", StringArgumentType.word())
                        .suggests(PLAYER_SUGGESTIONS)
                        .then(Commands.argument("kitname", StringArgumentType.word())
                            .suggests(KIT_SUGGESTIONS)
                            .executes(BotCommand::kitGive))))
                .then(cmd("give-kit-near", "Give kit to bots within radius")
                    .then(Commands.argument("kitname", StringArgumentType.word())
                        .suggests(KIT_SUGGESTIONS)
                        .executes(ctx -> kitGiveNear(ctx, 10.0))
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 1000))
                            .executes(ctx -> kitGiveNear(ctx, DoubleArgumentType.getDouble(ctx, "radius"))))))
                .then(cmd("give-kit-near-random", "Give random kit to bots within radius")
                    .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 1000))
                        .then(Commands.argument("kits", StringArgumentType.greedyString())
                            .suggests(RANDOM_KIT_SUGGESTIONS)
                            .executes(BotCommand::kitGiveNearRandom))))
                .then(cmd("kits", "List all kits")
                    .executes(BotCommand::kitList)))

            // ========== FACTION ==========
            .then(cmd("faction", "Faction management")
                .then(cmd("list", "List all factions")
                    .executes(BotCommand::factionList))
                .then(cmd("create", "Create a faction")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommand::factionCreate)))
                .then(cmd("delete", "Delete a faction")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .executes(BotCommand::factionDelete)))
                .then(cmd("add", "Add player to faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(PLAYER_SUGGESTIONS)
                            .executes(BotCommand::factionAdd))))
                .then(cmd("remove", "Remove player from faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(PLAYER_SUGGESTIONS)
                            .executes(BotCommand::factionRemove))))
                .then(cmd("hostile", "Set faction hostility")
                    .then(Commands.argument("faction1", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("faction2", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .then(Commands.argument("hostile", BoolArgumentType.bool())
                                .executes(ctx -> factionHostile(ctx, BoolArgumentType.getBool(ctx, "hostile"))))
                            .executes(ctx -> factionHostile(ctx, true)))))
                .then(cmd("info", "Faction info")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .executes(BotCommand::factionInfo)))
                .then(cmd("add-near", "Add nearby players to faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 10000))
                            .executes(BotCommand::factionAddNear))))
                .then(cmd("add-all", "Add all players to faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .executes(BotCommand::factionAddAll)))
                .then(cmd("give", "Give item to faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                            .executes(BotCommand::factionGive))))
                .then(cmd("attack", "Faction attack target")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("target", StringArgumentType.word())
                            .suggests(PLAYER_SUGGESTIONS)
                            .executes(BotCommand::factionAttack))))
                .then(cmd("path", "Faction path control")
                    .then(cmd("start", "Start faction on path")
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .then(Commands.argument("path", StringArgumentType.word())
                                .suggests(PATH_SUGGESTIONS)
                                .executes(BotCommand::factionStartPath))))
                    .then(cmd("stop", "Stop faction on path")
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .executes(BotCommand::factionStopPath))))
                .then(cmd("tp", "Teleport faction bots to location")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("location", StringArgumentType.greedyString())
                            .suggests(PLAYER_SUGGESTIONS)
                            .executes(BotCommand::factionTp))))
                .then(cmd("kit", "Faction kit management")
                    .then(cmd("give-kit", "Give kit to faction")
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .then(Commands.argument("kitname", StringArgumentType.word())
                                .suggests(KIT_SUGGESTIONS)
                                .executes(BotCommand::factionGiveKit))))
                    .then(cmd("give-kit-random", "Give random kit to faction by percentage")
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .then(Commands.argument("kits", StringArgumentType.greedyString())
                                .suggests(RANDOM_KIT_SUGGESTIONS)
                                .executes(BotCommand::factionGiveKitRandom))))))

        );
    }

    private static final SuggestionProvider<CommandSourceStack> RANDOM_KIT_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining();
            int tokenCount;
            String partial;
            if (remaining.isEmpty()) {
                tokenCount = 0;
                partial = "";
            } else {
                int lastSpace = remaining.lastIndexOf(' ');
                if (lastSpace >= 0) {
                    partial = remaining.substring(lastSpace + 1);
                    tokenCount = remaining.substring(0, lastSpace).split(" ", -1).length;
                    if (tokenCount == 1 && remaining.substring(0, lastSpace).isEmpty()) tokenCount = 0;
                } else {
                    partial = remaining;
                    tokenCount = 0;
                }
            }
            var wordBuilder = builder.createOffset(builder.getStart() + remaining.length() - partial.length());
            if (tokenCount % 2 == 1) {
                for (int i = 1; i <= 100; i++) {
                    wordBuilder.suggest(i + "%");
                }
            } else {
                for (String kit : BotKits.getKitNames()) {
                    if (kit.regionMatches(true, 0, partial, 0, partial.length())) {
                        wordBuilder.suggest(kit);
                    }
                }
            }
            return wordBuilder.buildFuture();
        };

    // ========== SETTINGS BUILDER ==========

    private static LiteralArgumentBuilder<CommandSourceStack> buildSettings() {
        var settings = Commands.literal("settings")
            .executes(BotCommand::settings);

        settings.then(boolSetting("auto-armor", "Auto-equip armor", () -> BotSettings.get().isAutoEquipArmor(), v -> BotSettings.get().setAutoEquipArmor(v), BotSettings.DEFAULTS::isAutoEquipArmor));
        settings.then(boolSetting("auto-weapon", "Auto-equip best weapon", () -> BotSettings.get().isAutoEquipWeapon(), v -> BotSettings.get().setAutoEquipWeapon(v), BotSettings.DEFAULTS::isAutoEquipWeapon));
        settings.then(boolSetting("drop-armor", "Drop worse armor on pickup", () -> BotSettings.get().isDropWorseArmor(), v -> BotSettings.get().setDropWorseArmor(v), BotSettings.DEFAULTS::isDropWorseArmor));
        settings.then(boolSetting("drop-weapon", "Drop worse weapons on pickup", () -> BotSettings.get().isDropWorseWeapons(), v -> BotSettings.get().setDropWorseWeapons(v), BotSettings.DEFAULTS::isDropWorseWeapons));
        settings.then(doubleSetting("drop-distance", "Drop check distance", () -> BotSettings.get().getDropDistance(), v -> BotSettings.get().setDropDistance(v), 1.0, 10.0, BotSettings.DEFAULTS::getDropDistance));
        settings.then(intSetting("interval", "Equipment check interval (ticks)", () -> BotSettings.get().getCheckInterval(), v -> BotSettings.get().setCheckInterval(v), 1, 100, BotSettings.DEFAULTS::getCheckInterval));
        settings.then(boolSetting("combat", "Enable combat AI", () -> BotSettings.get().isCombatEnabled(), v -> BotSettings.get().setCombatEnabled(v), BotSettings.DEFAULTS::isCombatEnabled));
        settings.then(boolSetting("revenge", "Auto-attack last damager", () -> BotSettings.get().isRevengeEnabled(), v -> BotSettings.get().setRevengeEnabled(v), BotSettings.DEFAULTS::isRevengeEnabled));
        settings.then(boolSetting("auto-target", "Auto-target nearest player", () -> BotSettings.get().isAutoTargetEnabled(), v -> BotSettings.get().setAutoTargetEnabled(v), BotSettings.DEFAULTS::isAutoTargetEnabled));
        settings.then(boolSetting("target-players", "Target real players", () -> BotSettings.get().isTargetPlayers(), v -> BotSettings.get().setTargetPlayers(v), BotSettings.DEFAULTS::isTargetPlayers));
        settings.then(boolSetting("target-mobs", "Target hostile mobs", () -> BotSettings.get().isTargetHostileMobs(), v -> BotSettings.get().setTargetHostileMobs(v), BotSettings.DEFAULTS::isTargetHostileMobs));
        settings.then(boolSetting("target-bots", "Target other bots", () -> BotSettings.get().isTargetOtherBots(), v -> BotSettings.get().setTargetOtherBots(v), BotSettings.DEFAULTS::isTargetOtherBots));
        settings.then(boolSetting("criticals", "Jump-crit on melee attacks", () -> BotSettings.get().isCriticalsEnabled(), v -> BotSettings.get().setCriticalsEnabled(v), BotSettings.DEFAULTS::isCriticalsEnabled));
        settings.then(intSetting("crit-fall-ticks", "Falling ticks before crit attack", () -> BotSettings.get().getCriticalFallTicks(), v -> BotSettings.get().setCriticalFallTicks(v), 1, 10, BotSettings.DEFAULTS::getCriticalFallTicks));
        settings.then(boolSetting("ranged", "Use bows/crossbows", () -> BotSettings.get().isRangedEnabled(), v -> BotSettings.get().setRangedEnabled(v), BotSettings.DEFAULTS::isRangedEnabled));
        settings.then(doubleSetting("ranged-min-range", "Min bow distance", () -> BotSettings.get().getRangedMinRange(), v -> BotSettings.get().setRangedMinRange(v), 3.0, 20.0, BotSettings.DEFAULTS::getRangedMinRange));
        settings.then(doubleSetting("ranged-optimal-range", "Ideal bow distance", () -> BotSettings.get().getRangedOptimalRange(), v -> BotSettings.get().setRangedOptimalRange(v), 10.0, 50.0, BotSettings.DEFAULTS::getRangedOptimalRange));
        settings.then(doubleSetting("ranged-max-range", "Max bow engagement range", () -> BotSettings.get().getRangedMaxRange(), v -> BotSettings.get().setRangedMaxRange(v), 15.0, 100.0, BotSettings.DEFAULTS::getRangedMaxRange));
        settings.then(intSetting("bow-draw-ticks", "Bow full draw time (ticks)", () -> BotSettings.get().getBowMinDrawTime(), v -> BotSettings.get().setBowMinDrawTime(v), 5, 100, BotSettings.DEFAULTS::getBowMinDrawTime));
        settings.then(boolSetting("arrow-prediction", "Predictive bow aiming", () -> BotSettings.get().isArrowPredictionEnabled(), v -> BotSettings.get().setArrowPredictionEnabled(v), BotSettings.DEFAULTS::isArrowPredictionEnabled));
        settings.then(boolSetting("ranged-strafe", "Strafe while shooting bow", () -> BotSettings.get().isRangedStrafeEnabled(), v -> BotSettings.get().setRangedStrafeEnabled(v), BotSettings.DEFAULTS::isRangedStrafeEnabled));
        settings.then(boolSetting("ranged-retreat", "Retreat with bow when target close", () -> BotSettings.get().isRangedRetreatOnClose(), v -> BotSettings.get().setRangedRetreatOnClose(v), BotSettings.DEFAULTS::isRangedRetreatOnClose));
        settings.then(boolSetting("mace", "Use mace smash attack", () -> BotSettings.get().isMaceEnabled(), v -> BotSettings.get().setMaceEnabled(v), BotSettings.DEFAULTS::isMaceEnabled));
        settings.then(boolSetting("special-names", "Use special bot names", () -> BotSettings.get().isUseSpecialNames(), v -> BotSettings.get().setUseSpecialNames(v), BotSettings.DEFAULTS::isUseSpecialNames));
        settings.then(boolSetting("shield-mace", "Auto-shield against mace", () -> BotSettings.get().isShieldMace(), v -> BotSettings.get().setShieldMace(v), BotSettings.DEFAULTS::isShieldMace));
        settings.then(intSetting("attack-cooldown", "Ticks between melee attacks", () -> BotSettings.get().getAttackCooldown(), v -> BotSettings.get().setAttackCooldown(v), 1, 40, BotSettings.DEFAULTS::getAttackCooldown));
        settings.then(doubleSetting("melee-range", "Melee attack range", () -> BotSettings.get().getMeleeRange(), v -> BotSettings.get().setMeleeRange(v), 2.0, 6.0, BotSettings.DEFAULTS::getMeleeRange));
        settings.then(doubleSetting("move-speed", "Movement speed multiplier", () -> BotSettings.get().getMoveSpeed(), v -> BotSettings.get().setMoveSpeed(v), 0.1, 2.0, BotSettings.DEFAULTS::getMoveSpeed));
        settings.then(boolSetting("auto-totem", "Auto-equip totem to offhand", () -> BotSettings.get().isAutoTotemEnabled(), v -> BotSettings.get().setAutoTotemEnabled(v), BotSettings.DEFAULTS::isAutoTotemEnabled));
        settings.then(boolSetting("totem-priority", "Keep totem over shield in offhand", () -> BotSettings.get().isTotemPriority(), v -> BotSettings.get().setTotemPriority(v), BotSettings.DEFAULTS::isTotemPriority));
        settings.then(boolSetting("auto-shield", "Auto-use shield", () -> BotSettings.get().isAutoShieldEnabled(), v -> BotSettings.get().setAutoShieldEnabled(v), BotSettings.DEFAULTS::isAutoShieldEnabled));
        settings.then(boolSetting("auto-potion", "Auto-use potions", () -> BotSettings.get().isAutoPotionEnabled(), v -> BotSettings.get().setAutoPotionEnabled(v), BotSettings.DEFAULTS::isAutoPotionEnabled));
        settings.then(boolSetting("shield-break", "Auto-axe enemy shield", () -> BotSettings.get().isShieldBreakEnabled(), v -> BotSettings.get().setShieldBreakEnabled(v), BotSettings.DEFAULTS::isShieldBreakEnabled));
        settings.then(boolSetting("prefer-sword", "Prefer sword over axe", () -> BotSettings.get().isPreferSword(), v -> BotSettings.get().setPreferSword(v), BotSettings.DEFAULTS::isPreferSword));
        settings.then(boolSetting("bhop", "Bunny-hop movement", () -> BotSettings.get().isBhopEnabled(), v -> BotSettings.get().setBhopEnabled(v), BotSettings.DEFAULTS::isBhopEnabled));
        settings.then(boolSetting("idle", "Wander when idle", () -> BotSettings.get().isIdleWanderEnabled(), v -> BotSettings.get().setIdleWanderEnabled(v), BotSettings.DEFAULTS::isIdleWanderEnabled));
        settings.then(doubleSetting("idle-radius", "Idle wander radius", () -> BotSettings.get().getIdleWanderRadius(), v -> BotSettings.get().setIdleWanderRadius(v), 3.0, 50.0, BotSettings.DEFAULTS::getIdleWanderRadius));
        settings.then(boolSetting("friendly-fire", "Allow attacking allies", () -> BotSettings.get().isFriendlyFireEnabled(), v -> BotSettings.get().setFriendlyFireEnabled(v), BotSettings.DEFAULTS::isFriendlyFireEnabled));
        settings.then(intSetting("miss-chance", "Chance to miss (%)", () -> BotSettings.get().getMissChance(), v -> BotSettings.get().setMissChance(v), 0, 100, BotSettings.DEFAULTS::getMissChance));
        settings.then(intSetting("mistake-chance", "Chance to aim wrong (%)", () -> BotSettings.get().getMistakeChance(), v -> BotSettings.get().setMistakeChance(v), 0, 100, BotSettings.DEFAULTS::getMistakeChance));
        settings.then(intSetting("shield-break-chance", "Shield break attempt chance (%)", () -> BotSettings.get().getShieldBreakChance(), v -> BotSettings.get().setShieldBreakChance(v), 0, 100, BotSettings.DEFAULTS::getShieldBreakChance));
        settings.then(intSetting("shield-hold-ticks", "Max ticks to hold shield", () -> BotSettings.get().getShieldHoldTicks(), v -> BotSettings.get().setShieldHoldTicks(v), 10, 200, BotSettings.DEFAULTS::getShieldHoldTicks));
        settings.then(intSetting("shield-raise-ticks", "Ticks to predict enemy attack", () -> BotSettings.get().getShieldRaiseTicks(), v -> BotSettings.get().setShieldRaiseTicks(v), 2, 40, BotSettings.DEFAULTS::getShieldRaiseTicks));
        settings.then(boolSetting("retreat", "Retreat when low HP", () -> BotSettings.get().isRetreatEnabled(), v -> BotSettings.get().setRetreatEnabled(v), BotSettings.DEFAULTS::isRetreatEnabled));
        settings.then(boolSetting("auto-eat", "Auto-eat when hungry", () -> BotSettings.get().isAutoEatEnabled(), v -> BotSettings.get().setAutoEatEnabled(v), BotSettings.DEFAULTS::isAutoEatEnabled));
        settings.then(boolSetting("auto-mend", "Auto-use XP for mending", () -> BotSettings.get().isAutoMendEnabled(), v -> BotSettings.get().setAutoMendEnabled(v), BotSettings.DEFAULTS::isAutoMendEnabled));
        settings.then(boolSetting("bot-leave-on-death", "Bot leaves on death", () -> BotSettings.get().isBotLeaveOnDeath(), v -> BotSettings.get().setBotLeaveOnDeath(v), BotSettings.DEFAULTS::isBotLeaveOnDeath));
        settings.then(boolSetting("attack-invincible", "Attack creative/spectator", () -> BotSettings.get().isAttackInvincible(), v -> BotSettings.get().setAttackInvincible(v), BotSettings.DEFAULTS::isAttackInvincible));
        settings.then(boolSetting("profile-lagg-fix", "Pre-populate profile cache to prevent lag on bot spawn", () -> BotSettings.get().isProfileLagFix(), v -> BotSettings.get().setProfileLagFix(v), BotSettings.DEFAULTS::isProfileLagFix));
        settings.then(boolSetting("safe-spawn", "Spread mass-spawned bots to prevent entity cramming", () -> BotSettings.get().isSafeSpawn(), v -> BotSettings.get().setSafeSpawn(v), BotSettings.DEFAULTS::isSafeSpawn));
        settings.then(boolSetting("clear-on-remove", "Clear bot inventory before remove/kill", () -> BotSettings.get().isClearOnRemove(), v -> BotSettings.get().setClearOnRemove(v), BotSettings.DEFAULTS::isClearOnRemove));
        settings.then(doubleSetting("aim-speed", "Aim rotation speed", () -> BotSettings.get().getAimSpeed(), v -> BotSettings.get().setAimSpeed(v), 3.0, 45.0, BotSettings.DEFAULTS::getAimSpeed));
        settings.then(doubleSetting("view-distance", "Max target acquisition range", () -> BotSettings.get().getMaxTargetDistance(), v -> BotSettings.get().setMaxTargetDistance(v), 5.0, 128.0, BotSettings.DEFAULTS::getMaxTargetDistance));
        settings.then(intSetting("max-mass-spawn", "Max bots per mass-spawn command", () -> BotSettings.get().getMaxMassSpawn(), v -> BotSettings.get().setMaxMassSpawn(v), 50, 10000, BotSettings.DEFAULTS::getMaxMassSpawn));

        return settings;
    }

    // ========== FAST COMMANDS ==========

    private static final SuggestionProvider<CommandSourceStack> FACTION_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String f : BotFaction.getAllFactions()) {
                if (f.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(f);
                }
            }
            return builder.buildFuture();
        };

    // ========== SETTING HELPERS ==========

    private static LiteralArgumentBuilder<CommandSourceStack> boolSetting(String name, String desc, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier defaultGetter) {
        return Commands.literal(name)
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("§7[Tip] §a" + desc + " §7(current: " + getter.getAsBoolean() + ", default: " + defaultGetter.getAsBoolean() + ")"), false);
                return 1;
            })
            .then(Commands.argument("value", BoolArgumentType.bool())
                .executes(ctx -> {
                    boolean value = BoolArgumentType.getBool(ctx, "value");
                    setter.accept(value);
                    ctx.getSource().sendSuccess(() -> Component.literal(name + ": " + value), true);
                    return 1;
                }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> intSetting(String name, String desc, java.util.function.IntSupplier getter, java.util.function.IntConsumer setter, int min, int max, java.util.function.IntSupplier defaultGetter) {
        return Commands.literal(name)
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("§7[Tip] §a" + desc + " §7(current: " + getter.getAsInt() + ", default: " + defaultGetter.getAsInt() + ")"), false);
                return 1;
            })
            .then(Commands.argument("value", IntegerArgumentType.integer(min, max))
                .executes(ctx -> {
                    int value = IntegerArgumentType.getInteger(ctx, "value");
                    setter.accept(value);
                    ctx.getSource().sendSuccess(() -> Component.literal(name + ": " + value), true);
                    return 1;
                }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> doubleSetting(String name, String desc, java.util.function.DoubleSupplier getter, java.util.function.DoubleConsumer setter, double min, double max, java.util.function.DoubleSupplier defaultGetter) {
        return Commands.literal(name)
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("§7[Tip] §a" + desc + " §7(current: " + getter.getAsDouble() + ", default: " + defaultGetter.getAsDouble() + ")"), false);
                return 1;
            })
            .then(Commands.argument("value", DoubleArgumentType.doubleArg(min, max))
                .executes(ctx -> {
                    double value = DoubleArgumentType.getDouble(ctx, "value");
                    setter.accept(value);
                    ctx.getSource().sendSuccess(() -> Component.literal(name + ": " + value), true);
                    return 1;
                }));
    }

    // ========== COMMAND HANDLERS ==========

    private static int spawn(CommandContext<CommandSourceStack> ctx, String name) {
        var source = ctx.getSource();
        String botName = name != null ? name : BotNameGenerator.generateUniqueName();
        var server = source.getServer();
        var existingPlayer = server.getPlayerList().getPlayerByName(botName);
        if (existingPlayer != null && !BotManager.getAllBots().contains(botName)) {
            source.sendFailure(Component.literal("Cannot create bot '" + botName + "': a real player with this name is online!"));
            return 0;
        }
        if (BotManager.spawnBot(server, botName, source)) {
            source.sendSuccess(() -> Component.literal("PvP Bot '" + botName + "' spawned!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to spawn bot '" + botName + "' (bot already exists or name is taken)"));
            return 0;
        }
    }

    private static int massSpawn(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int maxAllowed = BotSettings.get().getMaxMassSpawn();
        if (count > maxAllowed) {
            source.sendFailure(Component.literal("Cannot spawn more than " + maxAllowed + " bots at once. Use /pvpbot settings max-mass-spawn to increase the limit."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Spawning " + count + " bots..."), false);
        int[] spawned = {0};
        int[] current = {0};
        Vec3 basePos = null;
        if (BotSettings.get().isSafeSpawn()) {
            var player = source.getPlayer();
            if (player != null) {
                basePos = new Vec3(player.getX(), player.getY(), player.getZ());
            }
        }
        scheduleSpawn(server, source, count, spawned, current, basePos);
        return 1;
    }

    private static void scheduleSpawn(MinecraftServer server, CommandSourceStack source, int total, int[] spawned, int[] current, Vec3 basePos) {
        if (current[0] >= total) {
            source.sendSuccess(() -> Component.literal("Finished! Spawned " + spawned[0] + " bots."), true);
            return;
        }
        String name = BotNameGenerator.generateUniqueName();
        Vec3 spawnPos = null;
        if (basePos != null) {
            var rng = java.util.concurrent.ThreadLocalRandom.current();
            double dx = (rng.nextDouble() * 0.4 + 0.1) * (rng.nextBoolean() ? 1 : -1);
            double dz = (rng.nextDouble() * 0.4 + 0.1) * (rng.nextBoolean() ? 1 : -1);
            spawnPos = new Vec3(basePos.x + dx, basePos.y, basePos.z + dz);
        }
        if (BotManager.spawnBot(server, name, source, spawnPos)) {
            spawned[0]++;
        }
        current[0]++;
        server.execute(() -> {
            int[] delay = {0};
            server.execute(new Runnable() {
                @Override
                public void run() {
                    delay[0]++;
                    if (delay[0] < 5) {
                        server.execute(this);
                    } else {
                        scheduleSpawn(server, source, total, spawned, current, basePos);
                    }
                }
            });
        });
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotManager.removeBot(source.getServer(), name, source)) {
            source.sendSuccess(() -> Component.literal("Bot '" + name + "' removed!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Bot '" + name + "' not found!"));
            return 0;
        }
    }

    private static int removeAll(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        int count = BotManager.getBotCount();
        BotManager.removeAllBots(source.getServer(), source);
        source.sendSuccess(() -> Component.literal("Removed " + count + " bots"), true);
        return count;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();
        BotSettings.load();
        BotKits.reload(server);
        BotPath.init();
        BotManager.reloadBots();
        source.sendSuccess(() -> Component.literal("All configurations reloaded!"), true);
        return 1;
    }

    private static int settings(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        BotSettings s = BotSettings.get();
        source.sendSuccess(() -> Component.literal("=== Equipment Settings ==="), false);
        source.sendSuccess(() -> Component.literal("auto-armor: " + s.isAutoEquipArmor()), false);
        source.sendSuccess(() -> Component.literal("auto-weapon: " + s.isAutoEquipWeapon()), false);
        source.sendSuccess(() -> Component.literal("drop-armor: " + s.isDropWorseArmor()), false);
        source.sendSuccess(() -> Component.literal("drop-weapon: " + s.isDropWorseWeapons()), false);
        source.sendSuccess(() -> Component.literal("drop-distance: " + s.getDropDistance()), false);
        source.sendSuccess(() -> Component.literal("interval: " + s.getCheckInterval() + " ticks"), false);
        source.sendSuccess(() -> Component.literal("=== Combat Settings ==="), false);
        source.sendSuccess(() -> Component.literal("combat: " + s.isCombatEnabled()), false);
        source.sendSuccess(() -> Component.literal("revenge: " + s.isRevengeEnabled()), false);
        source.sendSuccess(() -> Component.literal("auto-target: " + s.isAutoTargetEnabled()), false);
        source.sendSuccess(() -> Component.literal("target-players: " + s.isTargetPlayers()), false);
        source.sendSuccess(() -> Component.literal("target-mobs: " + s.isTargetHostileMobs()), false);
        source.sendSuccess(() -> Component.literal("target-bots: " + s.isTargetOtherBots()), false);
        source.sendSuccess(() -> Component.literal("criticals: " + s.isCriticalsEnabled()), false);
        source.sendSuccess(() -> Component.literal("ranged: " + s.isRangedEnabled()), false);
        source.sendSuccess(() -> Component.literal("ranged-min-range: " + s.getRangedMinRange()), false);
        source.sendSuccess(() -> Component.literal("ranged-optimal-range: " + s.getRangedOptimalRange()), false);
        source.sendSuccess(() -> Component.literal("ranged-max-range: " + s.getRangedMaxRange()), false);
        source.sendSuccess(() -> Component.literal("bow-draw-ticks: " + s.getBowMinDrawTime() + " ticks"), false);
        source.sendSuccess(() -> Component.literal("arrow-prediction: " + s.isArrowPredictionEnabled()), false);
        source.sendSuccess(() -> Component.literal("ranged-strafe: " + s.isRangedStrafeEnabled()), false);
        source.sendSuccess(() -> Component.literal("ranged-retreat: " + s.isRangedRetreatOnClose()), false);
        source.sendSuccess(() -> Component.literal("mace: " + s.isMaceEnabled()), false);
        source.sendSuccess(() -> Component.literal("special-names: " + s.isUseSpecialNames()), false);
        source.sendSuccess(() -> Component.literal("shield-mace: " + s.isShieldMace()), false);
        source.sendSuccess(() -> Component.literal("attack-cooldown: " + s.getAttackCooldown() + " ticks"), false);
        source.sendSuccess(() -> Component.literal("melee-range: " + s.getMeleeRange()), false);
        source.sendSuccess(() -> Component.literal("move-speed: " + s.getMoveSpeed()), false);
        source.sendSuccess(() -> Component.literal("=== Utilities ==="), false);
        source.sendSuccess(() -> Component.literal("auto-totem: " + s.isAutoTotemEnabled()), false);
        source.sendSuccess(() -> Component.literal("totem-priority: " + s.isTotemPriority() + " (don't replace totem with shield)"), false);
        source.sendSuccess(() -> Component.literal("auto-shield: " + s.isAutoShieldEnabled()), false);
        source.sendSuccess(() -> Component.literal("auto-potion: " + s.isAutoPotionEnabled()), false);
        source.sendSuccess(() -> Component.literal("shield-break: " + s.isShieldBreakEnabled()), false);
        source.sendSuccess(() -> Component.literal("prefer-sword: " + s.isPreferSword()), false);
        source.sendSuccess(() -> Component.literal("bot-leave-on-death: " + s.isBotLeaveOnDeath()), false);
        source.sendSuccess(() -> Component.literal("attack-invincible: " + s.isAttackInvincible()), false);
        source.sendSuccess(() -> Component.literal("aim-speed: " + s.getAimSpeed()), false);
        source.sendSuccess(() -> Component.literal("=== Navigation Settings ==="), false);
        source.sendSuccess(() -> Component.literal("bhop: " + s.isBhopEnabled()), false);

        source.sendSuccess(() -> Component.literal("idle: " + s.isIdleWanderEnabled()), false);
        source.sendSuccess(() -> Component.literal("idle-radius: " + s.getIdleWanderRadius()), false);
        source.sendSuccess(() -> Component.literal("=== Factions & Mistakes ==="), false);
        source.sendSuccess(() -> Component.literal("factions: " + s.isFactionsEnabled()), false);
        source.sendSuccess(() -> Component.literal("friendly-fire: " + s.isFriendlyFireEnabled()), false);
        source.sendSuccess(() -> Component.literal("miss-chance: " + s.getMissChance() + "%"), false);
        source.sendSuccess(() -> Component.literal("mistake-chance: " + s.getMistakeChance() + "%"), false);
        source.sendSuccess(() -> Component.literal("shield-break-chance: " + s.getShieldBreakChance() + "%"), false);

        return 1;
    }

    // ========== BOT-MANAGEMENT HANDLERS ==========

    private static int botAttack(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String botname = StringArgumentType.getString(ctx, "botname");
        String target = StringArgumentType.getString(ctx, "target");
        if (!BotManager.getAllBots().contains(botname)) {
            source.sendFailure(Component.literal("Bot '" + botname + "' not found!"));
            return 0;
        }
        BotCombat.setTarget(botname, target);
        source.sendSuccess(() -> Component.literal("Bot '" + botname + "' now attacking '" + target + "'"), true);
        return 1;
    }

    private static int botStopAttack(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String botname = StringArgumentType.getString(ctx, "botname");
        if (!BotManager.getAllBots().contains(botname)) {
            source.sendFailure(Component.literal("Bot '" + botname + "' not found!"));
            return 0;
        }
        BotCombat.clearTarget(botname);
        source.sendSuccess(() -> Component.literal("Bot '" + botname + "' stopped attacking"), true);
        return 1;
    }

    private static int botInventory(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String botname = StringArgumentType.getString(ctx, "botname");
        if (!BotManager.getAllBots().contains(botname)) {
            source.sendFailure(Component.literal("Bot '" + botname + "' not found!"));
            return 0;
        }
        var bot = source.getServer().getPlayerList().getPlayerByName(botname);
        if (bot == null) {
            source.sendFailure(Component.literal("Bot '" + botname + "' not online!"));
            return 0;
        }
        var inventory = bot.getInventory();
        source.sendSuccess(() -> Component.literal("=== Inventory: " + botname + " ==="), false);
        StringBuilder hotbar = new StringBuilder("Hotbar: ");
        for (int i = 0; i < 9; i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                hotbar.append("[").append(stack.getHoverName().getString()).append(" x").append(stack.getCount()).append("] ");
            }
        }
        source.sendSuccess(() -> Component.literal(hotbar.toString().trim()), false);
        StringBuilder mainInv = new StringBuilder("Main: ");
        for (int i = 9; i < 36; i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                mainInv.append("[").append(stack.getHoverName().getString()).append(" x").append(stack.getCount()).append("] ");
            }
        }
        source.sendSuccess(() -> Component.literal(mainInv.toString().trim()), false);
        StringBuilder armor = new StringBuilder("Armor: ");
        for (int i = 36; i < 40; i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                armor.append("[").append(stack.getHoverName().getString()).append("] ");
            }
        }
        String armorStr = armor.toString().trim();
        if (!armorStr.equals("Armor:")) {
            source.sendSuccess(() -> Component.literal(armorStr), false);
        }
        var offhand = inventory.getItem(40);
        if (!offhand.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Offhand: [" + offhand.getHoverName().getString() + " x" + offhand.getCount() + "]"), false);
        }
        source.sendSuccess(() -> Component.literal("HP: " + String.format("%.1f", bot.getHealth()) + "/" + String.format("%.1f", bot.getMaxHealth()) +
            " | Food: " + bot.getFoodData().getFoodLevel() +
            " | XP: " + bot.experienceLevel), false);
        return 1;
    }

    private static int botList(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var bots = BotManager.getAllBots();
        if (bots.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active PvP bots"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Active PvP bots (" + bots.size() + "):"), false);
            for (String botName : bots) {
                source.sendSuccess(() -> Component.literal(" - " + botName), false);
            }
        }
        return bots.size();
    }

    // ========== PATH HANDLERS ==========

    private static int pathCreate(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotPath.createPath(name)) {
            BotPath.setPathVisible(name, true);
            source.sendSuccess(() -> Component.literal("Path '" + name + "' created"), true);
            source.sendSuccess(() -> Component.literal("Visualization enabled. To disable: /pvpbot path show " + name + " false"), false);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' already exists"));
            return 0;
        }
    }

    private static int pathDelete(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotPath.deletePath(name)) {
            source.sendSuccess(() -> Component.literal("Path '" + name + "' deleted"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathAddPoint(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can add path points"));
            return 0;
        }
        Vec3 pos = new Vec3(player.getX(), player.getY(), player.getZ());
        if (BotPath.addPoint(name, pos)) {
            var path = BotPath.getPath(name);
            if (!BotPath.isPathVisible(name)) {
                BotPath.setPathVisible(name, true);
                source.sendSuccess(() -> Component.literal("Visualization enabled. To disable: /pvpbot path show " + name + " false"), false);
            }
            source.sendSuccess(() -> Component.literal(String.format("Point #%d added to path '%s' at (%.1f, %.1f, %.1f)", path.points.size(), name, pos.x, pos.y, pos.z)), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathRemovePoint(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        int index = IntegerArgumentType.getInteger(ctx, "index");
        if (BotPath.removePoint(name, index)) {
            source.sendSuccess(() -> Component.literal("Point #" + index + " removed from path '" + name + "'"), true);
            return 1;
        }
        source.sendFailure(Component.literal("Invalid path or index"));
        return 0;
    }

    private static int pathRemovePointLast(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotPath.removeLastPoint(name)) {
            source.sendSuccess(() -> Component.literal("Last point removed from path '" + name + "'"), true);
            return 1;
        }
        source.sendFailure(Component.literal("Invalid path or index"));
        return 0;
    }

    private static int pathClear(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotPath.clearPath(name)) {
            source.sendSuccess(() -> Component.literal("All points cleared from path '" + name + "'"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathLoop(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        boolean value = BoolArgumentType.getBool(ctx, "value");
        if (BotPath.setLoop(name, value)) {
            source.sendSuccess(() -> Component.literal("Path '" + name + "' loop: " + value), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathStart(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String bot = StringArgumentType.getString(ctx, "bot");
        String path = StringArgumentType.getString(ctx, "path");
        if (BotPath.startFollowing(bot, path)) {
            source.sendSuccess(() -> Component.literal("Bot '" + bot + "' started following path '" + path + "'"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + path + "' not found or empty"));
            return 0;
        }
    }

    private static int pathStop(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String bot = StringArgumentType.getString(ctx, "bot");
        if (BotPath.stopFollowing(bot)) {
            source.sendSuccess(() -> Component.literal("Bot '" + bot + "' stopped following path"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Bot '" + bot + "' is not following any path"));
            return 0;
        }
    }

    private static int pathWalkType(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String type = StringArgumentType.getString(ctx, "type");
        if (!type.equals("bhop") && !type.equals("sprint") && !type.equals("walk")) {
            source.sendFailure(Component.literal("Invalid walk type. Use: bhop, sprint, or walk"));
            return 0;
        }
        if (BotPath.setWalkType(name, type)) {
            source.sendSuccess(() -> Component.literal("Path '" + name + "' walk type: " + type), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathList(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var paths = BotPath.getAllPaths();
        if (paths.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No paths created"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("=== Paths ==="), false);
        for (var entry : paths.entrySet()) {
            String name = entry.getKey();
            var path = entry.getValue();
            source.sendSuccess(() -> Component.literal(String.format("%s: %d points, loop: %s, attack: %s, walk-type: %s", name, path.points.size(), path.loop, path.attack, path.walkType)), false);
        }
        return paths.size();
    }

    private static int pathShow(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        boolean visible = BoolArgumentType.getBool(ctx, "visible");
        if (BotPath.setPathVisible(name, visible)) {
            if (visible) {
                source.sendSuccess(() -> Component.literal("Path '" + name + "' visualization enabled"), true);
                source.sendSuccess(() -> Component.literal("To disable: /pvpbot path show " + name + " false"), false);
            } else {
                source.sendSuccess(() -> Component.literal("Path '" + name + "' visualization disabled"), true);
            }
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathInfo(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        var path = BotPath.getPath(name);
        if (path == null) {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("=== Path: " + name + " ==="), false);
        source.sendSuccess(() -> Component.literal("Points: " + path.points.size()), false);
        source.sendSuccess(() -> Component.literal("Loop: " + path.loop), false);
        source.sendSuccess(() -> Component.literal("Attack: " + path.attack), false);
        source.sendSuccess(() -> Component.literal("Walk type: " + path.walkType), false);
        for (int i = 0; i < path.points.size(); i++) {
            var point = path.points.get(i);
            int index = i;
            source.sendSuccess(() -> Component.literal(String.format("#%d: (%.1f, %.1f, %.1f)", index, point.x, point.y, point.z)), false);
        }
        return 1;
    }

    private static int pathDistribute(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String pathName = StringArgumentType.getString(ctx, "path");
        var path = BotPath.getPath(pathName);
        if (path == null) {
            source.sendFailure(Component.literal("Path '" + pathName + "' not found"));
            return 0;
        }
        if (path.points.isEmpty()) {
            source.sendFailure(Component.literal("Path '" + pathName + "' has no points"));
            return 0;
        }
        var server = source.getServer();
        var botsOnPath = new java.util.ArrayList<String>();
        for (String botName : BotManager.getAllBots()) {
            if (BotPath.isFollowing(botName, pathName)) {
                botsOnPath.add(botName);
            }
        }
        if (botsOnPath.isEmpty()) {
            source.sendFailure(Component.literal("No bots are following path '" + pathName + "'"));
            return 0;
        }
        int totalPoints = path.points.size();
        int botCount = botsOnPath.size();
        for (int i = 0; i < botCount; i++) {
            String botName = botsOnPath.get(i);
            int pointIndex = (i * totalPoints) / botCount;
            BotPath.setBotPathIndex(botName, pointIndex);
            var point = path.points.get(pointIndex);
            try {
                String tpCommand = String.format(java.util.Locale.US, "tp %s %.2f %.2f %.2f", botName, point.x, point.y + 1.0, point.z);
                server.getCommands().getDispatcher().execute(tpCommand, server.createCommandSourceStack());
            } catch (Exception e) {
            }
        }
        source.sendSuccess(() -> Component.literal("Distributed " + botCount + " bots along path '" + pathName + "'"), true);
        return botCount;
    }

    private static int pathStartNear(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String pathName = StringArgumentType.getString(ctx, "path");
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        var path = BotPath.getPath(pathName);
        if (path == null) {
            source.sendFailure(Component.literal("Path '" + pathName + "' not found"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by a player"));
            return 0;
        }
        var server = source.getServer();
        int started = 0;
        for (String botName : BotManager.getAllBots()) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(botName);
            if (bot != null && bot.distanceTo(player) <= radius) {
                if (BotPath.startFollowing(botName, pathName)) {
                    started++;
                }
            }
        }
        if (started > 0) {
            int finalStarted = started;
            source.sendSuccess(() -> Component.literal("Started path '" + pathName + "' for " + finalStarted + " bots within " + radius + " blocks"), true);
            return started;
        } else {
            source.sendFailure(Component.literal("No bots found within " + radius + " blocks"));
            return 0;
        }
    }

    private static int pathStopAll(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String pathName = StringArgumentType.getString(ctx, "path");
        var path = BotPath.getPath(pathName);
        if (path == null) {
            source.sendFailure(Component.literal("Path '" + pathName + "' not found"));
            return 0;
        }
        int stopped = 0;
        for (String botName : BotManager.getAllBots()) {
            if (BotPath.isFollowing(botName, pathName)) {
                if (BotPath.stopFollowing(botName)) {
                    stopped++;
                }
            }
        }
        if (stopped > 0) {
            int finalStopped = stopped;
            source.sendSuccess(() -> Component.literal("Stopped " + finalStopped + " bots on path '" + pathName + "'"), true);
            return stopped;
        } else {
            source.sendFailure(Component.literal("No bots are following path '" + pathName + "'"));
            return 0;
        }
    }

    // ========== KIT HANDLERS ==========

    private static int kitCreate(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player!"));
            return 0;
        }
        if (BotKits.kitExists(name)) {
            source.sendFailure(Component.literal("Kit '" + name + "' already exists!"));
            return 0;
        }
        if (BotKits.createKit(name, player)) {
            source.sendSuccess(() -> Component.literal("Kit '" + name + "' created from your inventory!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to create kit (empty inventory?)"));
            return 0;
        }
    }

    private static int kitDelete(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotKits.deleteKit(name)) {
            source.sendSuccess(() -> Component.literal("Kit '" + name + "' deleted!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Kit '" + name + "' not found!"));
            return 0;
        }
    }

    private static int kitGive(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String playername = StringArgumentType.getString(ctx, "playername");
        String kitname = StringArgumentType.getString(ctx, "kitname");
        if (!BotKits.kitExists(kitname)) {
            source.sendFailure(Component.literal("Kit '" + kitname + "' not found!"));
            return 0;
        }
        var player = source.getServer().getPlayerList().getPlayerByName(playername);
        if (player == null) {
            source.sendFailure(Component.literal("Player '" + playername + "' not found!"));
            return 0;
        }
        if (BotKits.giveKit(kitname, player)) {
            source.sendSuccess(() -> Component.literal("Gave kit '" + kitname + "' to '" + playername + "'"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to give kit!"));
            return 0;
        }
    }

    private static int kitList(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var kits = BotKits.getKitNames();
        if (kits.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No kits created. Use /pvpbot kit create-kit <name> to create one."), false);
        } else {
            source.sendSuccess(() -> Component.literal("Kits (" + kits.size() + "): " + String.join(", ", kits)), false);
        }
        return 1;
    }

    // ========== FACTION HANDLERS ==========

    private static int factionList(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var factions = BotFaction.getAllFactions();
        if (factions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No factions created"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Factions (" + factions.size() + "):"), false);
            for (String faction : factions) {
                var members = BotFaction.getMembers(faction);
                var enemies = BotFaction.getHostileFactions(faction);
                source.sendSuccess(() -> Component.literal(" - " + faction + " (" + members.size() + " members, " + enemies.size() + " enemies)"), false);
            }
        }
        return factions.size();
    }

    private static int factionCreate(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotFaction.createFaction(name)) {
            source.sendSuccess(() -> Component.literal("Faction '" + name + "' created!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Faction '" + name + "' already exists!"));
            return 0;
        }
    }

    private static int factionDelete(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotFaction.deleteFaction(name)) {
            source.sendSuccess(() -> Component.literal("Faction '" + name + "' deleted!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Faction '" + name + "' not found!"));
            return 0;
        }
    }

    private static int factionAdd(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String player = StringArgumentType.getString(ctx, "player");
        if (BotFaction.addMember(faction, player)) {
            source.sendSuccess(() -> Component.literal("Added '" + player + "' to faction '" + faction + "'"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
    }

    private static int factionRemove(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String player = StringArgumentType.getString(ctx, "player");
        if (BotFaction.removeMember(faction, player)) {
            source.sendSuccess(() -> Component.literal("Removed '" + player + "' from faction '" + faction + "'"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to remove '" + player + "' from faction '" + faction + "'"));
            return 0;
        }
    }

    private static int factionHostile(CommandContext<CommandSourceStack> ctx, boolean isHostile) {
        var source = ctx.getSource();
        String faction1 = StringArgumentType.getString(ctx, "faction1");
        String faction2 = StringArgumentType.getString(ctx, "faction2");
        if (BotFaction.setHostile(faction1, faction2, isHostile)) {
            if (isHostile) {
                source.sendSuccess(() -> Component.literal("Factions '" + faction1 + "' and '" + faction2 + "' are now hostile!"), true);
            } else {
                source.sendSuccess(() -> Component.literal("Factions '" + faction1 + "' and '" + faction2 + "' are now neutral"), true);
            }
            return 1;
        } else {
            source.sendFailure(Component.literal("One or both factions not found, or same faction!"));
            return 0;
        }
    }

    private static int factionInfo(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        var members = BotFaction.getMembers(faction);
        var enemies = BotFaction.getHostileFactions(faction);
        if (members.isEmpty() && enemies.isEmpty() && !BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("=== Faction: " + faction + " ==="), false);
        source.sendSuccess(() -> Component.literal("Members (" + members.size() + "): " + String.join(", ", members)), false);
        source.sendSuccess(() -> Component.literal("Hostile to (" + enemies.size() + "): " + String.join(", ", enemies)), false);
        return 1;
    }

    private static int factionAddNear(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        var entity = source.getEntity();
        if (entity == null) {
            source.sendFailure(Component.literal("This command must be run by a player!"));
            return 0;
        }
        int count = 0;
        var allBots = BotManager.getAllBots();
        var server = source.getServer();
        for (String botName : allBots) {
            var bot = server.getPlayerList().getPlayerByName(botName);
            if (bot != null && bot.distanceTo(entity) <= radius) {
                BotFaction.addMember(faction, botName);
                count++;
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Added " + finalCount + " bots to faction '" + faction + "'"), true);
        return count;
    }

    private static int factionAddAll(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        var allBots = BotManager.getAllBots();
        int count = 0;
        for (String botName : allBots) {
            BotFaction.addMember(faction, botName);
            count++;
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Added " + finalCount + " bots to faction '" + faction + "'"), true);
        return count;
    }

    private static int factionGive(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String itemCommand = StringArgumentType.getString(ctx, "item");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        var members = BotFaction.getMembers(faction);
        var server = source.getServer();
        int count = 0;
        for (String memberName : members) {
            try {
                server.getCommands().getDispatcher().execute("give " + memberName + " " + itemCommand, server.createCommandSourceStack());
                count++;
            } catch (Exception e) {
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Gave items to " + finalCount + " members of faction '" + faction + "'"), true);
        return count;
    }

    private static int factionAttack(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String target = StringArgumentType.getString(ctx, "target");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        var members = BotFaction.getMembers(faction);
        int count = 0;
        for (String memberName : members) {
            if (BotManager.getAllBots().contains(memberName)) {
                BotCombat.setTarget(memberName, target);
                count++;
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Faction '" + faction + "' (" + finalCount + " bots) attacking " + target + "!"), true);
        return count;
    }

    private static int factionStartPath(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String path = StringArgumentType.getString(ctx, "path");
        var members = BotFaction.getMembers(faction);
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found or has no members"));
            return 0;
        }
        var botPath = BotPath.getPath(path);
        if (botPath == null) {
            source.sendFailure(Component.literal("Path '" + path + "' not found"));
            return 0;
        }
        int started = 0;
        for (String member : members) {
            if (BotManager.getAllBots().contains(member)) {
                if (BotPath.startFollowing(member, path)) {
                    started++;
                }
            }
        }
        if (started > 0) {
            int finalStarted = started;
            source.sendSuccess(() -> Component.literal("Started path '" + path + "' for " + finalStarted + " bots in faction '" + faction + "'"), true);
            return started;
        } else {
            source.sendFailure(Component.literal("No bots in faction '" + faction + "'"));
            return 0;
        }
    }

    private static int factionStopPath(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        var members = BotFaction.getMembers(faction);
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found or has no members"));
            return 0;
        }
        int stopped = 0;
        for (String member : members) {
            if (BotManager.getAllBots().contains(member)) {
                if (BotPath.stopFollowing(member)) {
                    stopped++;
                }
            }
        }
        if (stopped > 0) {
            int finalStopped = stopped;
            source.sendSuccess(() -> Component.literal("Stopped path for " + finalStopped + " bots in faction '" + faction + "'"), true);
            return stopped;
        } else {
            source.sendFailure(Component.literal("No bots in faction '" + faction + "' were following a path"));
            return 0;
        }
    }

    private static int factionGiveKit(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String kitname = StringArgumentType.getString(ctx, "kitname");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        if (!BotKits.kitExists(kitname)) {
            source.sendFailure(Component.literal("Kit '" + kitname + "' not found!"));
            return 0;
        }
        var members = BotFaction.getMembers(faction);
        if (members == null || members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' has no members!"));
            return 0;
        }
        int count = 0;
        for (String memberName : members) {
            if (BotManager.getAllBots().contains(memberName)) {
                var bot = BotManager.getBot(source.getServer(), memberName);
                if (bot != null && BotKits.giveKit(kitname, bot)) {
                    count++;
                }
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Gave kit '" + kitname + "' to " + finalCount + " bots in faction '" + faction + "'"), true);
        return 1;
    }

    // ========== GIVE-KIT-NEAR ==========

    private static int kitGiveNear(CommandContext<CommandSourceStack> ctx, double radius) {
        var source = ctx.getSource();
        String kitname = StringArgumentType.getString(ctx, "kitname");
        if (!BotKits.kitExists(kitname)) {
            source.sendFailure(Component.literal("Kit '" + kitname + "' not found!"));
            return 0;
        }
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player!"));
            return 0;
        }
        var server = source.getServer();
        int count = 0;
        for (String botName : BotManager.getAllBots()) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(botName);
            if (bot != null && bot.distanceTo(player) <= radius) {
                if (BotKits.giveKit(kitname, bot)) {
                    count++;
                }
            }
        }
        if (count > 0) {
            int finalCount = count;
            source.sendSuccess(() -> Component.literal("Gave kit '" + kitname + "' to " + finalCount + " bots within " + radius + " blocks"), true);
            return count;
        } else {
            source.sendFailure(Component.literal("No bots found within " + radius + " blocks"));
            return 0;
        }
    }

    // ========== GIVE-KIT-NEAR-RANDOM ==========

    private static int kitGiveNearRandom(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        String kitsArg = StringArgumentType.getString(ctx, "kits");
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player!"));
            return 0;
        }
        String[] parts = kitsArg.split(" ");
        if (parts.length < 2 || parts.length % 2 != 0) {
            source.sendFailure(Component.literal("Usage: /pvpbot kit give-kit-near-random <radius> <kit> <weight>% [<kit> <weight>% ...]"));
            return 0;
        }
        java.util.LinkedHashMap<String, Integer> weights = new java.util.LinkedHashMap<>();
        for (int i = 0; i < parts.length; i += 2) {
            String kitName = parts[i];
            String weightStr = parts[i + 1];
            if (!weightStr.endsWith("%")) {
                source.sendFailure(Component.literal("Invalid weight '" + weightStr + "' for kit '" + kitName + "'. Use format: <weight>%"));
                return 0;
            }
            if (!BotKits.kitExists(kitName)) {
                source.sendFailure(Component.literal("Kit '" + kitName + "' not found!"));
                return 0;
            }
            try {
                int w = Integer.parseInt(weightStr.substring(0, weightStr.length() - 1));
                if (w <= 0) {
                    source.sendFailure(Component.literal("Weight must be positive for kit '" + kitName + "'"));
                    return 0;
                }
                weights.put(kitName, w);
            } catch (NumberFormatException e) {
                source.sendFailure(Component.literal("Invalid weight '" + weightStr + "' for kit '" + kitName + "'"));
                return 0;
            }
        }
        int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
        var server = source.getServer();
        int count = 0;
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (String botName : BotManager.getAllBots()) {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(botName);
            if (bot == null || bot.distanceTo(player) > radius) continue;
            int roll = rng.nextInt(totalWeight);
            int cumulative = 0;
            String selected = null;
            for (var entry : weights.entrySet()) {
                cumulative += entry.getValue();
                if (roll < cumulative) {
                    selected = entry.getKey();
                    break;
                }
            }
            if (selected != null && BotKits.giveKit(selected, bot)) {
                count++;
            }
        }
        if (count > 0) {
            int finalCount = count;
            source.sendSuccess(() -> Component.literal("Gave random kits to " + finalCount + " bots within " + radius + " blocks"), true);
            return count;
        } else {
            source.sendFailure(Component.literal("No bots found within " + radius + " blocks"));
            return 0;
        }
    }

    // ========== FACTION GIVE-KIT-RANDOM ==========

    private static int factionGiveKitRandom(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        String kitsArg = StringArgumentType.getString(ctx, "kits");
        String[] parts = kitsArg.split(" ");
        if (parts.length < 2 || parts.length % 2 != 0) {
            source.sendFailure(Component.literal("Usage: /pvpbot faction give-kit-random <faction> <kit> <weight>% [<kit> <weight>% ...]"));
            return 0;
        }
        java.util.LinkedHashMap<String, Integer> weights = new java.util.LinkedHashMap<>();
        for (int i = 0; i < parts.length; i += 2) {
            String kitName = parts[i];
            String weightStr = parts[i + 1];
            if (!weightStr.endsWith("%")) {
                source.sendFailure(Component.literal("Invalid weight '" + weightStr + "' for kit '" + kitName + "'. Use format: <weight>%"));
                return 0;
            }
            if (!BotKits.kitExists(kitName)) {
                source.sendFailure(Component.literal("Kit '" + kitName + "' not found!"));
                return 0;
            }
            try {
                int w = Integer.parseInt(weightStr.substring(0, weightStr.length() - 1));
                if (w <= 0) {
                    source.sendFailure(Component.literal("Weight must be positive for kit '" + kitName + "'"));
                    return 0;
                }
                weights.put(kitName, w);
            } catch (NumberFormatException e) {
                source.sendFailure(Component.literal("Invalid weight '" + weightStr + "' for kit '" + kitName + "'"));
                return 0;
            }
        }
        int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
        var members = BotFaction.getMembers(faction);
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' has no members!"));
            return 0;
        }
        var server = source.getServer();
        int count = 0;
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (String memberName : members) {
            if (!BotManager.getAllBots().contains(memberName)) continue;
            var bot = BotManager.getBot(server, memberName);
            if (bot == null) continue;
            int roll = rng.nextInt(totalWeight);
            int cumulative = 0;
            String selected = null;
            for (var entry : weights.entrySet()) {
                cumulative += entry.getValue();
                if (roll < cumulative) {
                    selected = entry.getKey();
                    break;
                }
            }
            if (selected != null && BotKits.giveKit(selected, bot)) {
                count++;
            }
        }
        if (count > 0) {
            int finalCount = count;
            source.sendSuccess(() -> Component.literal("Gave random kits to " + finalCount + " bots in faction '" + faction + "'"), true);
            return count;
        } else {
            source.sendFailure(Component.literal("No bots in faction '" + faction + "' received a kit"));
            return 0;
        }
    }

    // ========== FACTION TP ==========

    private static int factionTp(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        String location = StringArgumentType.getString(ctx, "location");
        String[] parts = location.split(" ");
        double tx, ty, tz;
        if (parts.length == 3) {
            var player = source.getPlayer();
            double ox = (player != null) ? player.getX() : 0;
            double oy = (player != null) ? player.getY() : 0;
            double oz = (player != null) ? player.getZ() : 0;
            try {
                tx = parseCoord(parts[0], ox);
                ty = parseCoord(parts[1], oy);
                tz = parseCoord(parts[2], oz);
            } catch (NumberFormatException e) {
                source.sendFailure(Component.literal("Invalid coordinates. Usage: /pvpbot faction tp <faction> <x y z> or <playername>"));
                return 0;
            }
        } else {
            var server = source.getServer();
            var target = server.getPlayerList().getPlayerByName(location);
            if (target == null) {
                source.sendFailure(Component.literal("Player or bot '" + location + "' not found!"));
                return 0;
            }
            tx = target.getX();
            ty = target.getY();
            tz = target.getZ();
        }
        var members = new java.util.ArrayList<>(BotFaction.getMembers(faction));
        members.removeIf(m -> !BotManager.getAllBots().contains(m));
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' has no bots!"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Teleporting " + members.size() + " bots in faction '" + faction + "'..."), false);
        var server = source.getServer();
        boolean safe = BotSettings.get().isSafeSpawn();
        int[] count = {0};
        scheduleFactionTp(server, source, members, tx, ty, tz, safe, 0, count);
        return 1;
    }

    private static double parseCoord(String s, double origin) {
        if (s.startsWith("~")) {
            double offset = s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0;
            return origin + offset;
        }
        return Double.parseDouble(s);
    }

    private static final java.util.concurrent.ScheduledExecutorService TP_SCHEDULER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FactionTP");
            t.setDaemon(true);
            return t;
        });

    private static void scheduleFactionTp(MinecraftServer server, CommandSourceStack source,
                                          java.util.List<String> members, double tx, double ty, double tz,
                                          boolean safe, int index, int[] count) {
        if (index >= members.size()) {
            source.sendSuccess(() -> Component.literal("Teleported " + count[0] + " bots in faction"), true);
            return;
        }
        int end = Math.min(index + 5, members.size());
        TP_SCHEDULER.schedule(() -> server.execute(() -> {
            var rng = java.util.concurrent.ThreadLocalRandom.current();
            for (int i = index; i < end; i++) {
                String name = members.get(i);
                double x = tx, z = tz;
                if (safe) {
                    x += (rng.nextDouble() * 0.4 + 0.1) * (rng.nextBoolean() ? 1 : -1);
                    z += (rng.nextDouble() * 0.4 + 0.1) * (rng.nextBoolean() ? 1 : -1);
                }
                ServerPlayer bot = BotManager.getBot(server, name);
                if (bot != null) {
                    bot.teleportTo((net.minecraft.server.level.ServerLevel) bot.level(), x, ty, tz, java.util.Set.of(), 0.0f, 0.0f, false);
                    count[0]++;
                }
            }
            scheduleFactionTp(server, source, members, tx, ty, tz, safe, end, count);
        }), 100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}


