package org.stepan1411.pvp_bot.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;


public class BotAnchorPvp {
    

    private static class AnchorState {
        int step = 0;
        BlockPos lastAnchorPos = null;
        long lastActionTime = 0;
        int cooldownTicks = 0;
        int stuckCounter = 0;
        int lastStep = -1;
        int anchorNotFoundCounter = 0;
        int anchorPlaceFailCounter = 0;
        java.util.Set<BlockPos> triedPositions = new java.util.HashSet<>();
        int anchorPlaceAttempts = 0;
    }
    
    private static final java.util.Map<String, AnchorState> states = new java.util.HashMap<>();
    
    
    private static AnchorState getState(String botName) {
        return states.computeIfAbsent(botName, k -> new AnchorState());
    }
    
    
    public static boolean canUseAnchorPvp(ServerPlayer bot, Entity target, BotSettings settings) {
        if (!settings.isAnchorPvpEnabled()) return false;
        

        String dimension = bot.level().dimension().identifier().toString();
        if (dimension.contains("nether")) return false;
        
        double distance = bot.distanceTo(target);
        if (distance < 2.0 || distance > 8.0) return false;
        

        double healthPercent = bot.getHealth() / bot.getMaxHealth();
        if (healthPercent < 0.4) return false;
        
        Inventory inventory = bot.getInventory();
        return hasRespawnAnchor(inventory) && hasGlowstone(inventory);
    }
    
    
    public static boolean doAnchorPvp(ServerPlayer bot, Entity target, BotSettings settings, net.minecraft.server.MinecraftServer server) {
        if (BotUtils.getState(bot.getName().getString()).isEating) {
            return false;
        }
        AnchorState state = getState(bot.getName().getString());
        Level world = bot.level();
        double distance = bot.distanceTo(target);
        

        if (state.step == state.lastStep) {
            state.stuckCounter++;
            if (state.stuckCounter > 100) {
                System.out.println("[Anchor PVP] " + bot.getName().getString() + " STUCK on step " + state.step + "! Resetting state.");
                state.step = 0;
                state.lastAnchorPos = null;
                state.cooldownTicks = 0;
                state.stuckCounter = 0;
                return true;
            }
        } else {
            state.stuckCounter = 0;
            state.lastStep = state.step;
        }
        

        if (state.cooldownTicks > 0) {
            state.cooldownTicks--;
            maintainDistance(bot, target, settings);
            return true;
        }
        

        if (distance > 8.0) {
            moveToward(bot, target, settings.getMoveSpeed());
            state.step = 0;
            state.lastAnchorPos = null;
            return true;
        }
        

        if (distance < 2.0) {
            moveAway(bot, target, settings.getMoveSpeed());
            return true;
        }
        

        switch (state.step) {
            case 0:
                return stepPlaceAnchor(bot, target, state, server, world, settings);
                
            case 1:
                return stepChargeAnchor(bot, target, state, server, world, settings);
                
            case 2:
                return stepDetonateAnchor(bot, target, state, server, world, settings, distance);
                
            default:
                state.step = 0;
                return true;
        }
    }
    
    
    private static boolean stepPlaceAnchor(ServerPlayer bot, Entity target, AnchorState state,
                                          net.minecraft.server.MinecraftServer server, Level world, BotSettings settings) {
        Inventory inventory = bot.getInventory();
        

        BlockPos existingAnchor = findExistingAnchor(bot, target, world, 5.0);
        if (existingAnchor != null) {
            double distToExisting = Math.sqrt(bot.distanceToSqr(existingAnchor.getX() + 0.5, existingAnchor.getY() + 0.5, existingAnchor.getZ() + 0.5));
            
            if (distToExisting <= 4.0) {
                System.out.println("[Anchor PVP] " + bot.getName().getString() + " using existing anchor at " + existingAnchor);
                state.lastAnchorPos = existingAnchor;
                state.step = 1;
                state.cooldownTicks = 0;
                state.stuckCounter = 0;
                return true;
            } else {
                System.out.println("[Anchor PVP] " + bot.getName().getString() + " approaching existing anchor, distance: " + String.format("%.2f", distToExisting));
                moveToward(bot, target, settings.getMoveSpeed());
                return true;
            }
        }
        

        

        int anchorSlot = findRespawnAnchor(inventory);
        if (anchorSlot < 0) {
            return false;
        }
        

        BlockPos anchorPos = findBestAnchorPosition(bot, target, world, state.triedPositions);
        if (anchorPos == null) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " could not find anchor position!");
            if (!state.triedPositions.isEmpty()) {
                System.out.println("[Anchor PVP] " + bot.getName().getString() + " clearing tried positions (" + state.triedPositions.size() + " positions)");
                state.triedPositions.clear();
            }
            maintainDistance(bot, target, settings);
            return true;
        }
        

        double distToPos = Math.sqrt(bot.distanceToSqr(anchorPos.getX() + 0.5, anchorPos.getY() + 0.5, anchorPos.getZ() + 0.5));
        
        if (distToPos > 3.0) {
            moveToward(bot, target, settings.getMoveSpeed());
            return true;
        }
        

        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
        

        if (!selectItem(bot, anchorSlot)) {
            return true;
        }
        

        lookAt(bot, anchorPos);
        

        state.anchorPlaceAttempts++;
        

        if (state.anchorPlaceAttempts >= 3) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " failed to place anchor 3 times at " + anchorPos + ", trying different position");
            state.triedPositions.add(anchorPos);
            state.anchorPlaceAttempts = 0;
            state.cooldownTicks = 3;
            return true;
        }
        

        try {
            server.getCommands().getDispatcher().execute(
                "player " + bot.getName().getString() + " use once",
                server.createCommandSourceStack()
            );
            
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " placed anchor at " + anchorPos + " (attempt " + state.anchorPlaceAttempts + "/3)");
            
            state.lastAnchorPos = anchorPos;
            state.step = 1;
            state.cooldownTicks = 3;
            state.stuckCounter = 0;
            state.anchorPlaceAttempts = 0;
            
        } catch (Exception e) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " error placing anchor: " + e.getMessage());
        }
        
        return true;
    }

    
    private static boolean stepChargeAnchor(ServerPlayer bot, Entity target, AnchorState state,
                                           net.minecraft.server.MinecraftServer server, Level world, BotSettings settings) {
        Inventory inventory = bot.getInventory();
        

        if (state.lastAnchorPos == null) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " no anchor position!");
            state.step = 0;
            return true;
        }
        

        BlockState blockAtPos = world.getBlockState(state.lastAnchorPos);
        
        if (!(blockAtPos.getBlock() instanceof RespawnAnchorBlock)) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " NO anchor at position! Returning to step 0");
            if (state.lastAnchorPos != null) {
                state.triedPositions.add(state.lastAnchorPos);
            }
            state.step = 0;
            state.lastAnchorPos = null;
            state.anchorPlaceAttempts = 0;
            return true;
        }
        

        int charges = blockAtPos.getValue(RespawnAnchorBlock.CHARGE);
        
        if (charges >= 1) {

            System.out.println("[Anchor PVP] " + bot.getName().getString() + " anchor charged (" + charges + "/4), moving to step 2");
            state.step = 2;
            state.cooldownTicks = 0;
            return true;
        }
        

        int glowstoneSlot = findGlowstone(inventory);
        if (glowstoneSlot < 0) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " no glowstone in inventory! Exiting Anchor PVP.");
            state.step = 0;
            state.lastAnchorPos = null;
            state.stuckCounter = 0;
            return false;
        }
        

        if (state.anchorPlaceFailCounter >= 5) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " STUCK - anchor charging failed 5 times! Resetting state.");
            state.step = 0;
            state.lastAnchorPos = null;
            state.cooldownTicks = 10;
            state.anchorPlaceFailCounter = 0;
            state.triedPositions.clear();
            return true;
        }
        

        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
        

        if (!selectItem(bot, glowstoneSlot)) {
            return true;
        }
        

        lookAt(bot, state.lastAnchorPos);
        

        try {
            server.getCommands().getDispatcher().execute(
                "player " + bot.getName().getString() + " use once",
                server.createCommandSourceStack()
            );
            
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " charged anchor once (charges: " + (charges + 1) + "/4)");
            

            state.step = 2;
            state.cooldownTicks = 2;
            state.stuckCounter = 0;
            
        } catch (Exception e) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " error charging anchor: " + e.getMessage());
            state.step = 0;
            state.lastAnchorPos = null;
            state.anchorPlaceFailCounter = 0;
        }
        
        return true;
    }
    
    
    private static boolean stepDetonateAnchor(ServerPlayer bot, Entity target, AnchorState state,
                                             net.minecraft.server.MinecraftServer server, Level world,
                                             BotSettings settings, double distance) {
        

        if (state.lastAnchorPos == null) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " no anchor position!");
            state.step = 0;
            return true;
        }
        

        BlockState blockAtPos = world.getBlockState(state.lastAnchorPos);
        
        if (!(blockAtPos.getBlock() instanceof RespawnAnchorBlock)) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " NO anchor at position! Returning to step 0");
            state.anchorNotFoundCounter++;
            
            if (state.anchorNotFoundCounter >= 3) {
                System.out.println("[Anchor PVP] " + bot.getName().getString() + " STUCK - anchor not found 3 times! Resetting state.");
                state.step = 0;
                state.lastAnchorPos = null;
                state.cooldownTicks = 10;
                state.anchorNotFoundCounter = 0;
                state.anchorPlaceFailCounter = 0;
                state.triedPositions.clear();
                return true;
            }
            
            state.step = 0;
            state.lastAnchorPos = null;
            return true;
        }
        

        int charges = blockAtPos.getValue(RespawnAnchorBlock.CHARGE);
        
        if (charges == 0) {

            System.out.println("[Anchor PVP] " + bot.getName().getString() + " anchor not charged, returning to step 1");
            state.step = 1;
            state.cooldownTicks = 0;
            return true;
        }
        

        double distToAnchor = Math.sqrt(bot.distanceToSqr(
            state.lastAnchorPos.getX() + 0.5,
            state.lastAnchorPos.getY() + 0.5,
            state.lastAnchorPos.getZ() + 0.5
        ));
        

        double targetDistToAnchor = Math.sqrt(target.distanceToSqr(
            state.lastAnchorPos.getX() + 0.5,
            state.lastAnchorPos.getY() + 0.5,
            state.lastAnchorPos.getZ() + 0.5
        ));
        

        if (distToAnchor < 3.0) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " too close to anchor (" + String.format("%.2f", distToAnchor) + "), retreating");
            moveAway(bot, target, settings.getMoveSpeed());
            return true;
        }
        

        if (targetDistToAnchor > 5.0) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " target too far from anchor (" + String.format("%.2f", targetDistToAnchor) + "), waiting");
            maintainDistance(bot, target, settings);
            return true;
        }
        

        state.anchorNotFoundCounter = 0;
        state.anchorPlaceFailCounter = 0;
        

        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
        

        Inventory inventory = bot.getInventory();
        int anchorSlot = findRespawnAnchor(inventory);
        if (anchorSlot >= 0) {
            selectItem(bot, anchorSlot);
        } else {

            int weaponSlot = findMeleeWeapon(inventory);
            if (weaponSlot >= 0) {
                selectItem(bot, weaponSlot);
            }
        }
        

        lookAt(bot, state.lastAnchorPos);
        

        try {
            server.getCommands().getDispatcher().execute(
                "player " + bot.getName().getString() + " use once",
                server.createCommandSourceStack()
            );
            
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " DETONATED anchor! (charges: " + charges + ")");
            

            state.step = 0;
            state.lastAnchorPos = null;
            state.cooldownTicks = 5;
            state.stuckCounter = 0;
            
        } catch (Exception e) {
            System.out.println("[Anchor PVP] " + bot.getName().getString() + " error detonating anchor: " + e.getMessage());
            state.step = 0;
            state.lastAnchorPos = null;
        }
        
        return true;
    }
    
    
    private static void maintainDistance(ServerPlayer bot, Entity target, BotSettings settings) {
        double distance = bot.distanceTo(target);
        
        if (distance < 3.0) {
            moveAway(bot, target, settings.getMoveSpeed() * 0.7);
        } else if (distance > 5.5) {
            moveToward(bot, target, settings.getMoveSpeed() * 0.7);
        } else {
            lookAtEntity(bot, target);
        }
    }
    
    
    private static void moveToward(ServerPlayer bot, Entity target, double speed) {
        Vec3 targetPos = new Vec3(target.getX(), target.getY(), target.getZ());
        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        Vec3 direction = targetPos.subtract(botPos).normalize();
        
        bot.setDeltaMovement(direction.x * speed, bot.getDeltaMovement().y, direction.z * speed);
        bot.needsSync = true;
        
        String botName = bot.getName().getString();
        String pathWalkType = null;
        if (BotPath.isFollowing(botName)) {
            var follower = BotPath.getFollower(botName);
            if (follower != null) {
                var pathData = BotPath.getPath(follower.pathName);
                if (pathData != null) {
                    pathWalkType = pathData.walkType;
                }
            }
        }
        
        if (pathWalkType != null) {
            if (pathWalkType.equals("bhop") && bot.onGround()) {
                bot.jumpFromGround();
                bot.setSprinting(true);
            } else if (pathWalkType.equals("sprint")) {
                bot.setSprinting(true);
            } else if (pathWalkType.equals("walk")) {
                bot.setSprinting(false);
            }
        }
        
        lookAtEntity(bot, target);
    }
    
    
    private static void moveAway(ServerPlayer bot, Entity target, double speed) {
        Vec3 targetPos = new Vec3(target.getX(), target.getY(), target.getZ());
        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        Vec3 direction = botPos.subtract(targetPos).normalize();
        
        bot.setDeltaMovement(direction.x * speed, bot.getDeltaMovement().y, direction.z * speed);
        bot.needsSync = true;
        
        lookAtEntity(bot, target);
    }
    
    
    private static void lookAt(ServerPlayer bot, BlockPos pos) {
        Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3 botPos = bot.getEyePosition();
        
        double dx = target.x - botPos.x;
        double dy = target.y - botPos.y;
        double dz = target.z - botPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Math.atan2(dy, horizontalDist) * (180.0 / Math.PI));
        
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);
    }
    
    
    private static void lookAtEntity(ServerPlayer bot, Entity target) {
        Vec3 targetPos = target.getEyePosition();
        Vec3 botPos = bot.getEyePosition();
        
        double dx = targetPos.x - botPos.x;
        double dy = targetPos.y - botPos.y;
        double dz = targetPos.z - botPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Math.atan2(dy, horizontalDist) * (180.0 / Math.PI));
        
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);
    }
    
    
    private static boolean selectItem(ServerPlayer bot, int slot) {
        Inventory inventory = bot.getInventory();
        

        if (slot >= 9) {
            ItemStack item = inventory.getItem(slot);
            ItemStack current = inventory.getItem(0);
            inventory.setItem(slot, current);
            inventory.setItem(0, item);
            slot = 0;
        }
        

        org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, slot);
        return true;
    }
    
    
    private static BlockPos findExistingAnchor(ServerPlayer bot, Entity target, Level world, double maxDistance) {
        BlockPos targetPos = target.blockPosition();
        
        int radius = (int) Math.ceil(maxDistance);
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = targetPos.offset(dx, dy, dz);
                    
                    double distFromTarget = Math.sqrt(target.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
                    if (distFromTarget > maxDistance) continue;
                    
                    BlockState blockState = world.getBlockState(pos);
                    if (!(blockState.getBlock() instanceof RespawnAnchorBlock)) continue;
                    
                    double distFromBot = Math.sqrt(bot.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
                    if (distFromBot > 4.0) continue;
                    
                    System.out.println("[Anchor PVP] Found suitable anchor at " + pos + ", distance from enemy: " + String.format("%.2f", distFromTarget) + ", from bot: " + String.format("%.2f", distFromBot));
                    return pos;
                }
            }
        }
        
        return null;
    }
    
    
    private static BlockPos findBestAnchorPosition(ServerPlayer bot, Entity target, Level world, java.util.Set<BlockPos> triedPositions) {
        BlockPos targetPos = target.blockPosition();
        
        BlockPos[] candidates = {
            targetPos.north(),
            targetPos.south(),
            targetPos.east(),
            targetPos.west(),
            targetPos.north().east(),
            targetPos.north().west(),
            targetPos.south().east(),
            targetPos.south().west(),
        };
        
        for (BlockPos pos : candidates) {
            if (triedPositions.contains(pos)) continue;
            
            if (!world.getBlockState(pos).isAir() && !world.getBlockState(pos).canBeReplaced()) continue;
            
            double dist = Math.sqrt(bot.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            
            if (dist <= 4.0) {
                System.out.println("[Anchor PVP] Found suitable position: " + pos);
                return pos;
            }
        }
        
        System.out.println("[Anchor PVP] No suitable positions found!");
        return null;
    }
    
    
    private static int findRespawnAnchor(Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() == Items.RESPAWN_ANCHOR) return i;
        }
        return -1;
    }
    
    
    private static int findGlowstone(Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() == Items.GLOWSTONE) return i;
        }
        return -1;
    }
    
    
    private static int findMeleeWeapon(Inventory inventory) {
        int bestSlot = -1;
        int bestPriority = -1;
        
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            int priority = -1;
            
            if (stack.getItem().toString().contains("sword")) {
                priority = 10;
            } else if (stack.getItem().toString().contains("axe")) {
                priority = 5;
            }
            
            if (priority > bestPriority) {
                bestPriority = priority;
                bestSlot = i;
            }
        }
        
        return bestSlot;
    }
    
    
    private static boolean hasRespawnAnchor(Inventory inventory) {
        return findRespawnAnchor(inventory) >= 0;
    }
    
    
    private static boolean hasGlowstone(Inventory inventory) {
        return findGlowstone(inventory) >= 0;
    }
    
    
    public static void reset(String botName) {
        states.remove(botName);
    }
}
