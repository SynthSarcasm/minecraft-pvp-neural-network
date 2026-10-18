package org.stepan1411.pvp_bot.bot;

import carpet.fakes.ServerPlayerInterface;
import net.minecraft.server.level.ServerPlayer;

/** Port to 26.3 (pvpmost): every tick Carpet overwrites a fake player's zza/xxa with the action pack values,
 *  so movement goes through the action pack instead of writing the player fields directly. */
public final class BotMove {
    private BotMove() {}

    public static void forward(ServerPlayer bot, float v) {
        if (bot instanceof ServerPlayerInterface spi) spi.getActionPack().setForward(v);
        bot.zza = v;
    }

    public static void strafe(ServerPlayer bot, float v) {
        if (bot instanceof ServerPlayerInterface spi) spi.getActionPack().setStrafing(v);
        bot.xxa = v;
    }
}
