package org.stepan1411.pvp_bot.bot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;


public class HerobotMovement {
    
    private static boolean herobotAvailable = false;
    
    static {
        try {

            Class.forName("hero.bane.herobot.HeroBot");
            herobotAvailable = true;
        } catch (ClassNotFoundException e) {
            herobotAvailable = false;
        }
    }
    
    
    public static boolean isHerobotAvailable() {
        return herobotAvailable;
    }
    
    
    public static boolean walkTowards(ServerPlayer bot, Vec3 targetPos) {
        if (!herobotAvailable) {
            return false;
        }
        
        try {
            MinecraftServer server = bot.createCommandSourceStack().getServer();
            if (server == null) {
                return false;
            }
            
            String botName = bot.getName().getString();
            Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
            

            double dx = targetPos.x - botPos.x;
            double dz = targetPos.z - botPos.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            
            if (dist < 0.5) {

                stopMovement(bot);
                return true;
            }
            

            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            

            executeCommand(server, String.format("player %s look %.1f 0", botName, yaw));
            

            executeCommand(server, String.format("player %s move forward", botName));
            

            if (dist > 3.0) {
                executeCommand(server, String.format("player %s sprint", botName));
            } else {
                executeCommand(server, String.format("player %s unsprint", botName));
            }
            

            double dy = targetPos.y - botPos.y;
            if (dy > 0.5 && bot.onGround()) {
                executeCommand(server, String.format("player %s jump", botName));
            }
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    
    public static void stopMovement(ServerPlayer bot) {
        if (!herobotAvailable) {
            return;
        }
        
        try {
            MinecraftServer server = bot.createCommandSourceStack().getServer();
            if (server == null) {
                return;
            }
            
            String botName = bot.getName().getString();
            

            executeCommand(server, String.format("player %s stop", botName));
            executeCommand(server, String.format("player %s unsprint", botName));
        } catch (Exception e) {

        }
    }
    
    
    public static void jump(ServerPlayer bot) {
        if (!herobotAvailable) {
            return;
        }
        
        try {
            MinecraftServer server = bot.createCommandSourceStack().getServer();
            if (server == null) {
                return;
            }
            
            String botName = bot.getName().getString();
            executeCommand(server, String.format("player %s jump", botName));
        } catch (Exception e) {

        }
    }
    
    
    public static void sprint(ServerPlayer bot, boolean enable) {
        if (!herobotAvailable) {
            return;
        }
        
        try {
            MinecraftServer server = bot.createCommandSourceStack().getServer();
            if (server == null) {
                return;
            }
            
            String botName = bot.getName().getString();
            if (enable) {
                executeCommand(server, String.format("player %s sprint", botName));
            } else {
                executeCommand(server, String.format("player %s unsprint", botName));
            }
        } catch (Exception e) {

        }
    }
    
    
    public static void lookAt(ServerPlayer bot, Vec3 targetPos) {
        if (!herobotAvailable) {
            return;
        }
        
        try {
            MinecraftServer server = bot.createCommandSourceStack().getServer();
            if (server == null) {
                return;
            }
            
            String botName = bot.getName().getString();
            Vec3 botPos = bot.getEyePosition();
            

            double dx = targetPos.x - botPos.x;
            double dy = targetPos.y - botPos.y;
            double dz = targetPos.z - botPos.z;
            
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double pitch = -Math.toDegrees(Math.atan2(dy, horizontalDist));
            
            executeCommand(server, String.format("player %s look %.1f %.1f", botName, yaw, pitch));
        } catch (Exception e) {

        }
    }
    
    
    public static void executeCommand(MinecraftServer server, String command) {
        try {
            server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
        } catch (Exception e) {

        }
    }
}
