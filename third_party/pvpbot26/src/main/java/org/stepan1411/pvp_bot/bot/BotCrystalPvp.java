package org.stepan1411.pvp_bot.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;


public class BotCrystalPvp {
    

    private static class CrystalState {
        int step = 0;
        BlockPos lastObsidianPos = null;
        long lastActionTime = 0;
        int cooldownTicks = 0;
        int stuckCounter = 0;
        int lastStep = -1;
        int crystalNotFoundCounter = 0;
        int crystalPlaceFailCounter = 0;
        java.util.Set<BlockPos> triedPositions = new java.util.HashSet<>();
        int obsidianPlaceAttempts = 0;
        

        boolean swordAttackDone = false;
        int swordAttackCooldown = 0;
    }
    
    private static final java.util.Map<String, CrystalState> states = new java.util.HashMap<>();
    
    
    private static CrystalState getState(String botName) {
        return states.computeIfAbsent(botName, k -> new CrystalState());
    }
    
    
    public static boolean canUseCrystalPvp(ServerPlayer bot, Entity target, BotSettings settings) {
        if (!settings.isCrystalPvpEnabled()) return false;
        
        double distance = bot.distanceTo(target);
        if (distance < 2.5 || distance > 8.0) return false;
        
        Inventory inventory = bot.getInventory();
        return hasObsidian(inventory) && hasEndCrystal(inventory);
    }
    
    
    public static boolean doCrystalPvp(ServerPlayer bot, Entity target, BotSettings settings, net.minecraft.server.MinecraftServer server) {
        if (BotUtils.getState(bot.getName().getString()).isEating) {
            return false;
        }
        CrystalState state = getState(bot.getName().getString());
        Level world = bot.level();
        double distance = bot.distanceTo(target);
        

        if (state.step == state.lastStep) {
            state.stuckCounter++;
            if (state.stuckCounter > 100) {
                System.out.println("[Crystal PVP] " + bot.getName().getString() + " STUCK on step " + state.step + "! Resetting state.");
                state.step = 0;
                state.lastObsidianPos = null;
                state.cooldownTicks = 0;
                state.stuckCounter = 0;
                state.swordAttackDone = false;
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
        

        if (state.swordAttackCooldown > 0) {
            state.swordAttackCooldown--;
        }
        

        if (distance > 8.0) {
            moveToward(bot, target, settings.getMoveSpeed());
            state.step = 0;
            state.lastObsidianPos = null;
            return true;
        }
        

        if (distance < 2.5) {
            moveAway(bot, target, settings.getMoveSpeed());
            return true;
        }
        


        Entity nearCrystal = findNearestEndCrystal(bot, target, 6.0);
        if (nearCrystal != null && state.step != 2 && state.cooldownTicks == 0) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " РѕР±РЅР°СЂСѓР¶РёР» РєСЂРёСЃС‚Р°Р»Р», РїРµСЂРµС…РѕРґ Рє С€Р°РіСѓ 2 (С‚РµРєСѓС‰РёР№ step: " + state.step + ")");
            state.step = 2;
            state.cooldownTicks = 0;
        }
        

        switch (state.step) {
            case 0:
                return stepPlaceObsidian(bot, target, state, server, world, settings);
                
            case 1:
                return stepPlaceCrystal(bot, target, state, server, world, settings);
                
            case 2:
                return stepAttackCrystal(bot, target, state, server, world, settings, distance);
                
            default:
                state.step = 0;
                return true;
        }
    }
    
    
    private static boolean stepPlaceObsidian(ServerPlayer bot, Entity target, CrystalState state, 
                                            net.minecraft.server.MinecraftServer server, Level world, BotSettings settings) {
        Inventory inventory = bot.getInventory();
        

        if (!state.swordAttackDone && state.swordAttackCooldown <= 0) {
            double distance = bot.distanceTo(target);
            if (distance <= settings.getMeleeRange() + 1.0) {
                System.out.println("[Crystal PVP] " + bot.getName().getString() + " Step 0: Attacking player with sword first");
                boolean attacked = attackPlayerWithSword(bot, target, state, server, settings);
                if (attacked) {
                    state.swordAttackDone = true;
                    state.swordAttackCooldown = 10;
                    return true;
                }
            }
        }
        

        BlockPos existingObsidian = findExistingObsidian(bot, target, world, 5.0);
        if (existingObsidian != null) {
            double distToExisting = Math.sqrt(bot.distanceToSqr(existingObsidian.getX() + 0.5, existingObsidian.getY() + 0.5, existingObsidian.getZ() + 0.5));
            
            if (distToExisting <= 4.0) {

                System.out.println("[Crystal PVP] " + bot.getName().getString() + " using existing obsidian at " + existingObsidian);
                state.lastObsidianPos = existingObsidian;
                state.step = 1;
                state.cooldownTicks = 0;
                state.stuckCounter = 0;
                state.swordAttackDone = false;
                return true;
            } else {

                System.out.println("[Crystal PVP] " + bot.getName().getString() + " approaching existing obsidian, distance: " + String.format("%.2f", distToExisting));
                moveToward(bot, target, settings.getMoveSpeed());
                return true;
            }
        }
        

        

        int obsidianSlot = findObsidian(inventory);
        if (obsidianSlot < 0) {
            return false;
        }
        

        BlockPos obsidianPos = findBestObsidianPosition(bot, target, world, state.triedPositions);
        if (obsidianPos == null) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " could not find obsidian position!");

            if (!state.triedPositions.isEmpty()) {
                System.out.println("[Crystal PVP] " + bot.getName().getString() + " clearing tried positions (" + state.triedPositions.size() + " positions)");
                state.triedPositions.clear();
            }

            maintainDistance(bot, target, settings);
            return true;
        }
        

        double distToPos = Math.sqrt(bot.distanceToSqr(obsidianPos.getX() + 0.5, obsidianPos.getY() + 0.5, obsidianPos.getZ() + 0.5));
        
        if (distToPos > 3.0) {
            moveToward(bot, target, settings.getMoveSpeed());
            return true;
        }
        

        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
        

        if (!selectItem(bot, obsidianSlot)) {
            return true;
        }
        

        lookAt(bot, obsidianPos);
        

        state.obsidianPlaceAttempts++;
        

        if (state.obsidianPlaceAttempts >= 3) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " failed to place obsidian 3 times at " + obsidianPos + ", trying different position");
            state.triedPositions.add(obsidianPos);
            state.obsidianPlaceAttempts = 0;
            state.cooldownTicks = 3;
            return true;
        }
        

        try {
            server.getCommands().getDispatcher().execute(
                "player " + bot.getName().getString() + " use once", 
                server.createCommandSourceStack()
            );
            
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " placed obsidian at " + obsidianPos + " (attempt " + state.obsidianPlaceAttempts + "/3)");
            

            state.lastObsidianPos = obsidianPos;
            state.step = 1;
            state.cooldownTicks = 5;
            state.stuckCounter = 0;
            state.obsidianPlaceAttempts = 0;
            state.swordAttackDone = false;
            
        } catch (Exception e) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " error placing obsidian: " + e.getMessage());

        }
        

        return true;
    }
    
    
    private static boolean stepPlaceCrystal(ServerPlayer bot, Entity target, CrystalState state,
                                           net.minecraft.server.MinecraftServer server, Level world, BotSettings settings) {
        Inventory inventory = bot.getInventory();
        

        if (!state.swordAttackDone && state.swordAttackCooldown <= 0) {
            double distance = bot.distanceTo(target);
            if (distance <= settings.getMeleeRange() + 1.0) {
                System.out.println("[Crystal PVP] " + bot.getName().getString() + " Step 1: Attacking player with sword first");
                boolean attacked = attackPlayerWithSword(bot, target, state, server, settings);
                if (attacked) {
                    state.swordAttackDone = true;
                    state.swordAttackCooldown = 10;
                    return true;
                }
            }
        }
        

        if (state.lastObsidianPos == null) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " no obsidian position!");
            state.step = 0;
            return true;
        }
        

        net.minecraft.world.level.block.state.BlockState blockAtPos = world.getBlockState(state.lastObsidianPos);
        
        if (!blockAtPos.getBlock().toString().contains("obsidian")) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " NO obsidian at position! Returning to step 0");

            if (state.lastObsidianPos != null) {
                state.triedPositions.add(state.lastObsidianPos);
            }
            state.step = 0;
            state.lastObsidianPos = null;
            state.obsidianPlaceAttempts = 0;
            return true;
        }
        

        net.minecraft.world.level.block.state.BlockState blockAbove = world.getBlockState(state.lastObsidianPos.above());
        
        if (!blockAbove.isAir() && !blockAbove.canBeReplaced()) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " NO space above obsidian! Returning to step 0");
            state.step = 0;
            state.lastObsidianPos = null;
            return true;
        }
        

        int crystalSlot = findEndCrystal(inventory);
        if (crystalSlot < 0) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " no crystals in inventory! Exiting Crystal PVP.");
            state.step = 0;
            state.lastObsidianPos = null;
            state.stuckCounter = 0;
            return false;
        }
        

        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
        

        if (!selectItem(bot, crystalSlot)) {
            return true;
        }
        


        lookAt(bot, state.lastObsidianPos);
        

        if (state.crystalPlaceFailCounter >= 5) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " STUCK - crystal placement failed 5 times! Resetting state.");
            state.step = 0;
            state.lastObsidianPos = null;
            state.cooldownTicks = 5;
            state.crystalPlaceFailCounter = 0;
            return true;
        }
        

        try {
            server.getCommands().getDispatcher().execute(
                "player " + bot.getName().getString() + " use once", 
                server.createCommandSourceStack()
            );
            
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " placed crystal (clicked obsidian at " + state.lastObsidianPos + ") - attempt " + (state.crystalPlaceFailCounter + 1) + "/5");
            

            state.crystalPlaceFailCounter++;
            

            state.step = 2;
            state.cooldownTicks = 5;
            state.stuckCounter = 0;
            state.swordAttackDone = false;
            
        } catch (Exception e) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " error placing crystal: " + e.getMessage());

            state.step = 0;
            state.lastObsidianPos = null;
            state.crystalPlaceFailCounter = 0;
        }
        

        return true;
    }
    
    
    private static boolean stepAttackCrystal(ServerPlayer bot, Entity target, CrystalState state,
                                            net.minecraft.server.MinecraftServer server, Level world, 
                                            BotSettings settings, double distance) {
        Inventory inventory = bot.getInventory();
        

        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
        

        int weaponSlot = findMeleeWeapon(inventory);
        if (weaponSlot >= 0) {
            selectItem(bot, weaponSlot);
        }
        

        Entity crystal = findNearestEndCrystal(bot, target, 6.0);
        
        if (crystal != null) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " found crystal at distance " + bot.distanceTo(crystal));
            

            lookAtEntity(bot, crystal);
            

            state.crystalNotFoundCounter = 0;

            state.crystalPlaceFailCounter = 0;
            

            bot.attack(crystal);
            bot.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " hit crystal!");
            

            if (distance <= 4.5 && state.lastObsidianPos != null) {

                state.step = 1;
                state.cooldownTicks = 2;
                state.swordAttackDone = false;
                System.out.println("[Crystal PVP] " + bot.getName().getString() + " moving to step 1 (new crystal)");
            } else {

                state.step = 0;
                state.lastObsidianPos = null;
                state.cooldownTicks = 5;
                state.swordAttackDone = false;
                System.out.println("[Crystal PVP] " + bot.getName().getString() + " moving to step 0 (new obsidian)");
            }
            
        } else {
            state.crystalNotFoundCounter++;
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " DID NOT FIND crystal! (" + state.crystalNotFoundCounter + "/3)");
            

            if (state.crystalNotFoundCounter >= 3) {
                System.out.println("[Crystal PVP] " + bot.getName().getString() + " STUCK - crystal not found 3 times! Resetting state.");
                state.step = 0;
                state.lastObsidianPos = null;
                state.cooldownTicks = 5;
                state.crystalNotFoundCounter = 0;
                return true;
            }
            

            if (state.lastObsidianPos != null) {
                state.step = 1;
                state.swordAttackDone = false;
                System.out.println("[Crystal PVP] " + bot.getName().getString() + " returning to step 1 (place crystal)");
            } else {
                state.step = 0;
                state.swordAttackDone = false;
                System.out.println("[Crystal PVP] " + bot.getName().getString() + " returning to step 0 (place obsidian)");
            }
            state.cooldownTicks = 3;
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
    
    
    private static void strafe(ServerPlayer bot, Entity target, double speed) {
        Vec3 targetPos = new Vec3(target.getX(), target.getY(), target.getZ());
        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        Vec3 toTarget = targetPos.subtract(botPos).normalize();
        

        Vec3 strafeDir = new Vec3(-toTarget.z, 0, toTarget.x);
        
        bot.setDeltaMovement(strafeDir.x * speed, bot.getDeltaMovement().y, strafeDir.z * speed);
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
    
    
    private static BlockPos findExistingObsidian(ServerPlayer bot, Entity target, Level world, double maxDistance) {
        BlockPos targetPos = target.blockPosition();
        

        int radius = (int) Math.ceil(maxDistance);
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = targetPos.offset(dx, dy, dz);
                    

                    double distFromTarget = Math.sqrt(target.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
                    if (distFromTarget > maxDistance) continue;
                    

                    net.minecraft.world.level.block.state.BlockState blockState = world.getBlockState(pos);
                    if (!blockState.getBlock().toString().contains("obsidian")) continue;
                    

                    net.minecraft.world.level.block.state.BlockState blockAbove = world.getBlockState(pos.above());
                    if (!blockAbove.isAir() && !blockAbove.canBeReplaced()) continue;
                    

                    double distFromBot = Math.sqrt(bot.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
                    if (distFromBot > 4.0) {
                        continue;
                    }
                    
                    System.out.println("[Crystal PVP] Found suitable obsidian at " + pos + ", distance from enemy: " + String.format("%.2f", distFromTarget) + ", from bot: " + String.format("%.2f", distFromBot));
                    return pos;
                }
            }
        }
        
        return null;
    }
    
    
    private static BlockPos findBestObsidianPosition(ServerPlayer bot, Entity target, Level world, java.util.Set<BlockPos> triedPositions) {
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

            if (triedPositions.contains(pos)) {
                continue;
            }
            

            if (!world.getBlockState(pos).isAir() && !world.getBlockState(pos).canBeReplaced()) {
                continue;
            }
            

            if (!world.getBlockState(pos.above()).isAir() && !world.getBlockState(pos.above()).canBeReplaced()) {
                continue;
            }
            

            double dist = Math.sqrt(bot.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            
            if (dist <= 4.0) {
                System.out.println("[Crystal PVP] Found suitable position: " + pos);
                return pos;
            }
        }
        
        System.out.println("[Crystal PVP] No suitable positions found!");
        return null;
    }
    
    
    private static Entity findNearestEndCrystal(ServerPlayer bot, Entity target, double maxDistance) {
        Level world = bot.level();
        net.minecraft.world.phys.AABB searchBox = target.getBoundingBox().inflate(maxDistance);
        
        Entity nearestCrystal = null;
        double nearestDist = maxDistance + 1;
        int crystalCount = 0;
        
        
        for (Entity entity : world.getEntities(bot, searchBox)) {
            
            if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) {
                crystalCount++;
                double dist = target.distanceTo(entity);
                System.out.println("[Crystal PVP] Р­С‚Рѕ РєСЂРёСЃС‚Р°Р»Р»! Р”РёСЃС‚Р°РЅС†РёСЏ from target: " + String.format("%.2f", dist));
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestCrystal = entity;
                }
            }
        }
        
        if (crystalCount > 0) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " found " + crystalCount + " crystals in radius " + maxDistance);
        } else {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " РќР• found РЅРё РѕРґРЅРѕРіРѕ РєСЂРёСЃС‚Р°Р»Р»Р°!");
        }
        
        return nearestCrystal;
    }
    
    
    private static int findObsidian(Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() == Items.OBSIDIAN) return i;
        }
        return -1;
    }
    
    
    private static int findEndCrystal(Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() == Items.END_CRYSTAL) return i;
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
    
    
    private static boolean hasObsidian(Inventory inventory) {
        return findObsidian(inventory) >= 0;
    }
    
    
    private static boolean hasEndCrystal(Inventory inventory) {
        return findEndCrystal(inventory) >= 0;
    }
    
    
    private static boolean attackPlayerWithSword(ServerPlayer bot, Entity target, CrystalState state, 
                                                net.minecraft.server.MinecraftServer server, BotSettings settings) {
        Inventory inventory = bot.getInventory();
        double distance = bot.distanceTo(target);
        

        if (distance > settings.getMeleeRange()) {

            moveToward(bot, target, settings.getMoveSpeed());
            return true;
        }
        

        int weaponSlot = findMeleeWeapon(inventory);
        if (weaponSlot >= 0) {
            selectItem(bot, weaponSlot);
        }
        

        lookAtEntity(bot, target);
        

        if (bot.getAttackStrengthScale(0.5f) < 1.0f) {
            return true;
        }
        

        try {
            server.getCommands().getDispatcher().execute(
                "player " + bot.getName().getString() + " attack once", 
                server.createCommandSourceStack()
            );
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " attacked player with sword!");
            return true;
        } catch (Exception e) {
            System.out.println("[Crystal PVP] " + bot.getName().getString() + " error attacking with sword: " + e.getMessage());
            bot.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
            return true;
        }
    }
    
    
    public static void reset(String botName) {
        CrystalState state = states.get(botName);
        if (state != null) {
            state.step = 0;
            state.lastObsidianPos = null;
            state.cooldownTicks = 0;
            state.stuckCounter = 0;
            state.lastStep = -1;
            state.crystalNotFoundCounter = 0;
            state.crystalPlaceFailCounter = 0;
            state.triedPositions.clear();
            state.obsidianPlaceAttempts = 0;
            state.swordAttackDone = false;
            state.swordAttackCooldown = 0;
        }
    }
}
