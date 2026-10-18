package org.stepan1411.pvp_bot.fixes;

import net.minecraft.server.level.ServerPlayer;
import org.stepan1411.pvp_bot.bot.BotUtils;

public class EatingMovementFix {

    public static void apply(ServerPlayer bot) {
        var state = BotUtils.getState(bot.getName().getString());
        if (!state.isEating) return;

        bot.setSprinting(false);

        org.stepan1411.pvp_bot.bot.BotMove.forward(bot, bot.zza * 0.2f);
        org.stepan1411.pvp_bot.bot.BotMove.strafe(bot, bot.xxa * 0.2f);

        var vel = bot.getDeltaMovement();
        bot.setDeltaMovement(vel.x * 0.8, vel.y, vel.z * 0.8);
    }
}
