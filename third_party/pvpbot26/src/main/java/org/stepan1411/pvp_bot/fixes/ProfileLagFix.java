package org.stepan1411.pvp_bot.fixes;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import java.util.UUID;

public class ProfileLagFix {

    public static void preloadProfileCache(MinecraftServer server, String name) {
        try {
            var cache = server.services().nameToIdCache();
            if (cache != null) {
                UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                cache.add(new NameAndId(offlineUuid, name));
            }
        } catch (Exception e) {
        }
    }
}
