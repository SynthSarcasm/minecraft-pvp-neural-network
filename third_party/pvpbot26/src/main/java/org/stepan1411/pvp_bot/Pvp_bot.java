package org.stepan1411.pvp_bot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stepan1411.pvp_bot.bot.BotCombat;
import org.stepan1411.pvp_bot.bot.BotKits;
import org.stepan1411.pvp_bot.bot.BotManager;
import org.stepan1411.pvp_bot.bot.BotPath;
import org.stepan1411.pvp_bot.bot.BotSettings;
import org.stepan1411.pvp_bot.bot.BotTicker;
import org.stepan1411.pvp_bot.command.BotCommand;
import org.stepan1411.pvp_bot.config.WorldConfigHelper;


import java.util.ArrayList;
import java.util.List;

public class Pvp_bot implements ModInitializer {
    public static final String MOD_ID = "pvp_bot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing PvP Bot");

        /* telemetry removed */;
        registerCommands();
        registerServerEvents();
        registerTickEvents();

        LOGGER.info("PvP Bot initialized");
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            BotCommand.register(dispatcher);
        });
    }

    private void registerServerEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            /* telemetry removed */;
            WorldConfigHelper.init(server);
            BotKits.init(server);
            BotPath.init();
            BotManager.init(server);

        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BotManager.updateBotData(server);
            BotManager.saveBots();
            BotManager.reset(server);
        });
    }

    private void registerTickEvents() {
        BotTicker.register();
    }
}
