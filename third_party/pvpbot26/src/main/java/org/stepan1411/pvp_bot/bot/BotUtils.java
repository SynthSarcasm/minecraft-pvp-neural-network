package org.stepan1411.pvp_bot.bot;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SplashPotionItem;
import java.util.HashMap;
import java.util.Map;

public class BotUtils {
    
    private static final Map<String, BotState> botStates = new HashMap<>();
    
    public static class BotState {
        public int shieldCooldown = 0;
        public int eatCooldown = 0;
        public boolean isBlocking = false;
        public boolean isEating = false;
        public int eatingTicks = 0;
        public int windChargeCooldown = 0;
        public int eatingSlot = -1;
        public int potionCooldown = 0;
        public int buffPotionCooldown = 0;
        public boolean isThrowingPotion = false;
        public int throwingPotionTicks = 0;
        public boolean isMending = false;
        public int mendingCooldown = 0;
        public int xpBottlesThrown = 0;
        public int xpBottlesNeeded = 0;
        public java.util.List<Integer> potionsToThrow = new java.util.ArrayList<>();
        public ItemStack savedOffhandItem = ItemStack.EMPTY;
        public int savedMainHandSlot = -1;
        public int blockHoldTicks = 0;
        
        public boolean isInCobweb = false;
        public boolean isEscapingCobweb = false;
        public int cobwebEscapeTicks = 0;
        public int cobwebEscapeSlot = -1;
        public net.minecraft.core.BlockPos waterPosition = null;
        public boolean needsToCollectWater = false;
    }
    
    public static BotState getState(String botName) {
        return botStates.computeIfAbsent(botName, k -> new BotState());
    }
    
    public static void removeState(String botName) {
        botStates.remove(botName);
    }
    
    
    public static void update(ServerPlayer bot, MinecraftServer server) {
        BotSettings settings = BotSettings.get();
        BotState state = getState(bot.getName().getString());
        

        if (state.shieldCooldown > 0) state.shieldCooldown--;
        if (state.eatCooldown > 0) state.eatCooldown--;
        if (state.windChargeCooldown > 0) state.windChargeCooldown--;
        if (state.potionCooldown > 0) state.potionCooldown--;
        if (state.buffPotionCooldown > 0) state.buffPotionCooldown--;
        if (state.mendingCooldown > 0) state.mendingCooldown--;
        

        if (settings.isAutoMendEnabled()) {
            boolean needsMending = handleAutoMend(bot, state, settings, server);
            if (needsMending) {
                return;
            }
        }
        

        if (state.isThrowingPotion) {
            state.throwingPotionTicks++;

            bot.setXRot(90);
            
            if (state.throwingPotionTicks == 2) {

                executeCommand(server, bot, "player " + bot.getName().getString() + " use once");
            }
            if (state.throwingPotionTicks >= 5) {

                if (!state.potionsToThrow.isEmpty()) {
                    int nextSlot = state.potionsToThrow.remove(0);
                    var inventory = bot.getInventory();
                    

                    if (nextSlot >= 9) {
                        ItemStack potion = inventory.getItem(nextSlot);
                        ItemStack current = inventory.getItem(8);
                        inventory.setItem(nextSlot, current);
                        inventory.setItem(8, potion);
                        nextSlot = 8;
                    }
                    
                    org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, nextSlot);
                    state.throwingPotionTicks = 0;
                } else {

                    state.isThrowingPotion = false;
                    state.throwingPotionTicks = 0;
                }
            }
            return;
        }
        

        handleSwimming(bot);
        

        if (settings.isAutoTotemEnabled()) {
            handleAutoTotem(bot);
        }
        

        if (settings.isAutoPotionEnabled() && !state.isEating) {
            handleAutoBuffPotions(bot, state, server);
        }
        

        if (settings.isAutoEatEnabled()) {
            boolean criticalHunger = bot.getFoodData().getFoodLevel() <= 6;
            if (state.isEating || !state.isBlocking || criticalHunger) {
                if (state.isBlocking && criticalHunger) {
                    if (shouldUseMainHandShield(bot)) {
                        stopMainHandBlocking(bot, state, server);
                    } else {
                        stopBlocking(bot, state, server);
                    }
                }
                handleAutoEat(bot, state, settings, server);
            }
        }
        
        handleCobwebEscape(bot, state, server);

        if (settings.isAutoShieldEnabled() && !state.isEating) {
            handleAutoShield(bot, state, settings, server);
        }
    }
    
    
    private static void handleSwimming(ServerPlayer bot) {
        if (bot.isInWater() || bot.isUnderWater()) {
            bot.setSwimming(true);
            

            if (bot.isUnderWater()) {
                bot.push(0, 0.08, 0);
                bot.setSprinting(true);
            } else if (bot.isInWater()) {
                bot.push(0, 0.04, 0);
            }
            

            if (bot.onGround() && bot.isInWater()) {
                bot.jumpFromGround();
            }
        }
    }
    
    
    private static void handleAutoTotem(ServerPlayer bot) {
        BotState state = getState(bot.getName().getString());
        

        if (state.isBlocking) {
            return;
        }
        
        var inventory = bot.getInventory();
        ItemStack offhand = inventory.getItem(40);
        
        if (offhand.getItem() == Items.TOTEM_OF_UNDYING) return;
        
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) {
                inventory.setItem(i, offhand.copy());
                inventory.setItem(40, stack.copy());
                return;
            }
        }
    }
    
    
    private static void handleAutoBuffPotions(ServerPlayer bot, BotState state, MinecraftServer server) {

        var combatState = BotCombat.getState(bot.getName().getString());
        if (combatState.target == null) return;
        
        if (state.buffPotionCooldown > 0) return;
        if (state.isThrowingPotion) return;
        
        var inventory = bot.getInventory();
        

        java.util.List<Integer> potionsToUse = new java.util.ArrayList<>();
        

        boolean needStrength = !hasEffect(bot, MobEffects.STRENGTH, 100);
        boolean needSpeed = !hasEffect(bot, MobEffects.SPEED, 100);
        boolean needFireResist = !hasEffect(bot, MobEffects.FIRE_RESISTANCE, 100);
        
        if (needStrength) {
            int slot = findSplashBuffPotion(inventory, "strength");
            if (slot >= 0) potionsToUse.add(slot);
        }
        
        if (needSpeed) {
            int slot = findSplashBuffPotion(inventory, "swiftness");
            if (slot < 0) slot = findSplashBuffPotion(inventory, "speed");
            if (slot >= 0) potionsToUse.add(slot);
        }
        
        if (needFireResist) {
            int slot = findSplashBuffPotion(inventory, "fire_resistance");
            if (slot >= 0) potionsToUse.add(slot);
        }
        

        if (!potionsToUse.isEmpty()) {

            int firstSlot = potionsToUse.remove(0);
            

            if (firstSlot >= 9) {
                ItemStack potion = inventory.getItem(firstSlot);
                ItemStack current = inventory.getItem(8);
                inventory.setItem(firstSlot, current);
                inventory.setItem(8, potion);
                firstSlot = 8;
            }
            
            org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, firstSlot);
            

            state.potionsToThrow.clear();
            state.potionsToThrow.addAll(potionsToUse);
            

            state.isThrowingPotion = true;
            state.throwingPotionTicks = 0;
            state.buffPotionCooldown = 100;
        }
    }
    
    
    private static int findSplashBuffPotion(net.minecraft.world.entity.player.Inventory inventory, String effectName) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            Item item = stack.getItem();

            if (!(item instanceof SplashPotionItem) && !(item instanceof LingeringPotionItem)) {
                continue;
            }
            
            var potionContents = stack.get(DataComponents.POTION_CONTENTS);
            if (potionContents == null) continue;
            

            var potion = potionContents.potion();
            if (potion.isPresent()) {
                String potionName = potion.get().getRegisteredName().toLowerCase();
                if (potionName.contains(effectName)) {
                    return i;
                }
            }
        }
        return -1;
    }
    
    
    private static boolean hasEffect(ServerPlayer bot, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, int minDuration) {
        var instance = bot.getEffect(effect);
        if (instance == null) return false;
        return instance.getDuration() > minDuration;
    }
    
    
    private static int findBuffPotion(net.minecraft.world.entity.player.Inventory inventory, String effectName) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            Item item = stack.getItem();
            if (!(item instanceof PotionItem) && !(item instanceof SplashPotionItem) && !(item instanceof LingeringPotionItem)) {
                continue;
            }
            
            var potionContents = stack.get(DataComponents.POTION_CONTENTS);
            if (potionContents == null) continue;
            

            var potion = potionContents.potion();
            if (potion.isPresent()) {
                String potionName = potion.get().getRegisteredName().toLowerCase();
                if (potionName.contains(effectName)) {
                    return i;
                }
            }
        }
        return -1;
    }
    
    
    private static boolean useBuffPotion(ServerPlayer bot, BotState state, int slot, MinecraftServer server) {
        var inventory = bot.getInventory();
        ItemStack potionStack = inventory.getItem(slot);
        Item potionItem = potionStack.getItem();
        

        if (slot >= 9) {
            ItemStack current = inventory.getItem(8);
            inventory.setItem(slot, current);
            inventory.setItem(8, potionStack);
            slot = 8;
        }
        

        org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, slot);
        
        if (potionItem instanceof SplashPotionItem || potionItem instanceof LingeringPotionItem) {

            state.isThrowingPotion = true;
            state.throwingPotionTicks = 0;
            state.buffPotionCooldown = 15;
            return true;
        } else if (potionItem instanceof PotionItem) {

            state.isEating = true;
            state.eatingTicks = 0;
            state.eatingSlot = slot;
            state.buffPotionCooldown = 10;
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            return true;
        }
        
        return false;
    }
    
    
    private static void handleAutoEat(ServerPlayer bot, BotState state, BotSettings settings, MinecraftServer server) {

        if (!settings.isAutoEatEnabled()) {
            if (state.isEating) {
                executeCommand(server, bot, "player " + bot.getName().getString() + " stop");
                state.isEating = false;
                state.eatingTicks = 0;
                state.eatingSlot = -1;
            }
            return;
        }
        
        int hunger = bot.getFoodData().getFoodLevel();
        float health = bot.getHealth();
        float maxHealth = bot.getMaxHealth();
        
        boolean needFood = hunger <= settings.getMinHungerToEat();
        boolean needHealth = health <= maxHealth * 0.5f;
        boolean criticalHealth = health <= maxHealth * 0.3f;
        

        var combatState = BotCombat.getState(bot.getName().getString());
        boolean isRetreating = combatState.isRetreating;
        
        if (state.isEating) {
            state.eatingTicks++;
            

            if (state.eatingSlot >= 0 && state.eatingSlot < 9) {
                org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(bot.getInventory(), state.eatingSlot);
            }
            

            ItemStack foodStack = bot.getMainHandItem();
            if (foodStack.getItem().components().get(DataComponents.FOOD) != null) {

                bot.startUsingItem(InteractionHand.MAIN_HAND);
            }
            

            if (state.eatingTicks >= 80) {
                executeCommand(server, bot, "player " + bot.getName().getString() + " stop");
                state.isEating = false;
                state.eatingTicks = 0;
                state.eatingSlot = -1;
                state.eatCooldown = 10;
                

                hunger = bot.getFoodData().getFoodLevel();
                health = bot.getHealth();
                if (health <= maxHealth * 0.5f || hunger < 18) {
                    state.eatCooldown = 0;
                }
            }
            return;
        }
        



        boolean shouldEat = criticalHealth || needHealth || needFood;
        

        boolean shouldEatGoldenApple = needHealth && hasGoldenApple(bot.getInventory());
        
        if ((shouldEat || shouldEatGoldenApple) && state.eatCooldown <= 0 && !state.isBlocking) {
 
            if (combatState.isUsingShield) {
                try {
                    server.getCommands().getDispatcher().execute(
                        "player " + bot.getName().getString() + " stop",
                        server.createCommandSourceStack()
                    );
                } catch (Exception e) {}
                combatState.isUsingShield = false;
                combatState.shieldToggleCooldown = 20;
            }
 
            int foodSlot = findBestFood(bot.getInventory(), needHealth || criticalHealth);
            if (foodSlot >= 0) {
                var inventory = bot.getInventory();
                
 
                ItemStack foodStack = inventory.getItem(foodSlot);
                if (!foodStack.isEmpty() && foodStack.getItem().components().get(DataComponents.FOOD) != null) {
 
                    if (foodSlot >= 9) {
                        ItemStack food = inventory.getItem(foodSlot);
                        ItemStack current = inventory.getItem(8);
                        inventory.setItem(foodSlot, current);
                        inventory.setItem(8, food);
                        foodSlot = 8;
                    }
                    
                    state.eatingSlot = foodSlot;
                    
 
                    org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, foodSlot);
                    
 
                    executeCommand(server, bot, "player " + bot.getName().getString() + " use continuous");
                    state.isEating = true;
                    state.eatingTicks = 0;
                }
            }
        }
    }
    
    
    private static boolean hasGoldenApple(net.minecraft.world.entity.player.Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            Item item = inventory.getItem(i).getItem();
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                return true;
            }
        }
        return false;
    }

    
    private static int findBestFood(net.minecraft.world.entity.player.Inventory inventory, boolean preferGoldenApple) {
        int bestSlot = -1;
        int bestValue = 0;
        
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            int value = getFoodValue(stack.getItem(), preferGoldenApple);
            if (value > bestValue) {
                bestValue = value;
                bestSlot = i;
            }
        }
        return bestSlot;
    }
    
    private static int getFoodValue(Item item, boolean preferGoldenApple) {
        if (preferGoldenApple) {
            if (item == Items.ENCHANTED_GOLDEN_APPLE) return 100;
            if (item == Items.GOLDEN_APPLE) return 90;
        }
        if (item == Items.GOLDEN_CARROT) return 80;
        if (item == Items.COOKED_BEEF) return 70;
        if (item == Items.COOKED_PORKCHOP) return 70;
        if (item == Items.COOKED_MUTTON) return 65;
        if (item == Items.COOKED_SALMON) return 60;
        if (item == Items.COOKED_COD) return 55;
        if (item == Items.COOKED_CHICKEN) return 50;
        if (item == Items.BREAD) return 45;
        if (item == Items.BAKED_POTATO) return 45;
        if (item == Items.APPLE) return 30;
        if (item == Items.CARROT) return 25;
        
        var foodComponent = item.components().get(DataComponents.FOOD);
        if (foodComponent != null) {
            return foodComponent.nutrition() * 5;
        }
        return 0;
    }
    
    
    private static void handleAutoShield(ServerPlayer bot, BotState state, BotSettings settings, MinecraftServer server) {
        var inventory = bot.getInventory();
        int shieldSlot = findShield(inventory);
        if (shieldSlot < 0) {
            state.isBlocking = false;
            return;
        }
        

        var combatState = BotCombat.getState(bot.getName().getString());

        if (combatState.isMaceDefending) {
            return;
        }

        var target = combatState.target;

        if (target == null) {
            if (state.isBlocking) {
                if (shouldUseMainHandShield(bot)) {
                    stopMainHandBlocking(bot, state, server);
                } else {
                    stopBlocking(bot, state, server);
                }
            }
            return;
        }

        if (state.shieldCooldown > 0 && !state.isBlocking) {
            return;
        }

        double distance = bot.distanceTo(target);
        boolean isRetreating = combatState.isRetreating;
        float health = bot.getHealth();
        float maxHealth = bot.getMaxHealth();
        boolean lowHealth = health <= maxHealth * 0.3f;

        boolean shouldBlock = false;

        if (distance <= 4.0) {
            if (target instanceof Player player && player.isSwinging()) {
                shouldBlock = true;
            }
            if (isRetreating && lowHealth) {
                shouldBlock = true;
            }
        }

        if (state.isEating) {
            shouldBlock = false;
        }

        if (shouldBlock && !state.isBlocking) {
            if (shouldUseMainHandShield(bot)) {
                startMainHandBlocking(bot, state, server);
            } else {
                startBlocking(bot, state, shieldSlot, server);
            }
            state.blockHoldTicks = settings.getShieldHoldTicks();
            state.shieldCooldown = 30;
        }

        if (shouldBlock) {
            state.blockHoldTicks = settings.getShieldHoldTicks();
        } else if (state.isBlocking) {
            state.blockHoldTicks--;
        }

        if (!shouldBlock && state.isBlocking && state.blockHoldTicks <= 0) {
            if (shouldUseMainHandShield(bot)) {
                stopMainHandBlocking(bot, state, server);
            } else {
                stopBlocking(bot, state, server);
            }
        }
    }
    
    private static void startBlocking(ServerPlayer bot, BotState state, int shieldSlot, MinecraftServer server) {
        var inventory = bot.getInventory();
        
        if (shieldSlot != 40) {
            ItemStack shield = inventory.getItem(shieldSlot);
            ItemStack offhand = inventory.getItem(40);
            

            state.savedOffhandItem = offhand.copy();
            

            inventory.setItem(shieldSlot, offhand);
            inventory.setItem(40, shield);
        }
        

        executeCommand(server, bot, "player " + bot.getName().getString() + " use continuous");
        state.isBlocking = true;
    }
    
    private static void stopBlocking(ServerPlayer bot, BotState state, MinecraftServer server) {
        executeCommand(server, bot, "player " + bot.getName().getString() + " stop");
        state.isBlocking = false;
        
        var inventory = bot.getInventory();
        ItemStack currentOffhand = inventory.getItem(40);
        

        if (currentOffhand.getItem() == Items.SHIELD && !state.savedOffhandItem.isEmpty()) {

            int emptySlot = -1;
            for (int i = 0; i < 36; i++) {
                if (inventory.getItem(i).isEmpty()) {
                    emptySlot = i;
                    break;
                }
            }
            
            if (emptySlot >= 0) {

                inventory.setItem(emptySlot, currentOffhand.copy());

                inventory.setItem(40, state.savedOffhandItem.copy());
            }
            

            state.savedOffhandItem = ItemStack.EMPTY;
        }
    }
    
    private static int findShield(net.minecraft.world.entity.player.Inventory inventory) {
        if (inventory.getItem(40).getItem() == Items.SHIELD) return 40;
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).getItem() == Items.SHIELD) return i;
        }
        return -1;
    }

    public static boolean shouldUseMainHandShield(ServerPlayer bot) {
        if (!BotSettings.get().isTotemPriority()) return false;
        return bot.getInventory().getItem(40).getItem() == Items.TOTEM_OF_UNDYING;
    }

    private static final int SHIELD_HOTBAR_SLOT = 1;

    public static int equipShieldToMainHand(ServerPlayer bot) {
        var inventory = bot.getInventory();
        int shieldSlot = findShield(inventory);
        if (shieldSlot < 0 || shieldSlot == 40) return -1;

        ItemStack shield = inventory.getItem(shieldSlot);
        ItemStack slot2 = inventory.getItem(SHIELD_HOTBAR_SLOT);
        inventory.setItem(SHIELD_HOTBAR_SLOT, shield);
        inventory.setItem(shieldSlot, slot2);
        org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, SHIELD_HOTBAR_SLOT);
        return SHIELD_HOTBAR_SLOT;
    }

    public static void unequipShieldFromMainHand(ServerPlayer bot) {
        var inventory = bot.getInventory();
        ItemStack shieldStack = inventory.getItem(SHIELD_HOTBAR_SLOT);
        if (shieldStack.getItem() != Items.SHIELD) return;

        for (int i = 9; i < 36; i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, shieldStack);
                inventory.setItem(SHIELD_HOTBAR_SLOT, ItemStack.EMPTY);
                return;
            }
        }
    }

    private static void startMainHandBlocking(ServerPlayer bot, BotState state, MinecraftServer server) {
        state.savedMainHandSlot = org.stepan1411.pvp_bot.utils.InventoryHelper.getSelectedSlot(bot.getInventory());
        equipShieldToMainHand(bot);
        executeCommand(server, bot, "player " + bot.getName().getString() + " use continuous");
        state.isBlocking = true;
    }

    private static void stopMainHandBlocking(ServerPlayer bot, BotState state, MinecraftServer server) {
        executeCommand(server, bot, "player " + bot.getName().getString() + " stop");
        state.isBlocking = false;
        unequipShieldFromMainHand(bot);
        if (state.savedMainHandSlot >= 0) {
            org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(bot.getInventory(), state.savedMainHandSlot);
            state.savedMainHandSlot = -1;
        }
    }

    public static void useWindCharge(ServerPlayer bot, MinecraftServer server) {
        BotState state = getState(bot.getName().getString());
        if (state.windChargeCooldown > 0) return;
        if (state.isEating) return;
        
        var inventory = bot.getInventory();
        int slot = findWindCharge(inventory);
        if (slot < 0) return;
        

        if (slot >= 9) {
            ItemStack wc = inventory.getItem(slot);
            ItemStack current = inventory.getItem(0);
            inventory.setItem(slot, current);
            inventory.setItem(0, wc);
            slot = 0;
        }
        
        org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, slot);
        

        bot.setXRot(90);
        

        executeCommand(server, bot, "player " + bot.getName().getString() + " use once");
        
        state.windChargeCooldown = 20;
    }
    
    private static int findWindCharge(net.minecraft.world.entity.player.Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).getItem() == Items.WIND_CHARGE) return i;
        }
        return -1;
    }
    
    
    public static boolean tryDisableShield(ServerPlayer bot, Entity target) {

        BotState state = getState(bot.getName().getString());
        if (state.isEating) return false;
        
        if (!(target instanceof Player player)) return false;
        if (!player.isBlocking()) return false;
        
        var inventory = bot.getInventory();
        int axeSlot = findAxe(inventory);
        if (axeSlot < 0) return false;
        
        if (axeSlot >= 9) {
            ItemStack axe = inventory.getItem(axeSlot);
            ItemStack current = inventory.getItem(0);
            inventory.setItem(axeSlot, current);
            inventory.setItem(0, axe);
            org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, 0);
        } else {
            org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, axeSlot);
        }
        return true;
    }
    
    private static int findAxe(net.minecraft.world.entity.player.Inventory inventory) {
        int[] priorities = {-1, -1, -1, -1, -1, -1};
        for (int i = 0; i < 36; i++) {
            Item item = inventory.getItem(i).getItem();
            if (item == Items.NETHERITE_AXE) priorities[0] = i;
            else if (item == Items.DIAMOND_AXE) priorities[1] = i;
            else if (item == Items.IRON_AXE) priorities[2] = i;
            else if (item == Items.STONE_AXE) priorities[3] = i;
            else if (item == Items.GOLDEN_AXE) priorities[4] = i;
            else if (item == Items.WOODEN_AXE) priorities[5] = i;
        }
        for (int slot : priorities) {
            if (slot >= 0) return slot;
        }
        return -1;
    }
    
    
    private static void executeCommand(MinecraftServer server, ServerPlayer bot, String command) {
        try {
            server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
        } catch (Exception e) {

        }
    }
    
    
    public static boolean hasFood(ServerPlayer bot) {
        var inventory = bot.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                var foodComponent = stack.getItem().components().get(DataComponents.FOOD);
                if (foodComponent != null) {
                    return true;
                }
            }
        }
        return false;
    }
    
    
    public static boolean tryUseHealingPotion(ServerPlayer bot, MinecraftServer server) {
        BotState state = getState(bot.getName().getString());
        if (state.isEating) return false;
        if (state.potionCooldown > 0) return false;
        
        var inventory = bot.getInventory();
        

        int potionSlot = findHealingPotion(inventory);
        if (potionSlot < 0) return false;
        
        ItemStack potionStack = inventory.getItem(potionSlot);
        Item potionItem = potionStack.getItem();
        

        if (potionSlot >= 9) {
            ItemStack current = inventory.getItem(8);
            inventory.setItem(potionSlot, current);
            inventory.setItem(8, potionStack);
            potionSlot = 8;
        }
        

        org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, potionSlot);
        
        if (potionItem instanceof SplashPotionItem || potionItem instanceof LingeringPotionItem) {

            state.isThrowingPotion = true;
            state.throwingPotionTicks = 0;
            state.potionCooldown = 10;
            return true;
        } else if (potionItem instanceof PotionItem) {

            state.isEating = true;
            state.eatingTicks = 0;
            state.eatingSlot = potionSlot;
            state.potionCooldown = 5;
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            return true;
        }
        
        return false;
    }
    
    
    private static int findHealingPotion(net.minecraft.world.entity.player.Inventory inventory) {

        int splashSlot = -1;
        int normalSlot = -1;
        
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            Item item = stack.getItem();
            

            if (isHealingPotion(stack)) {
                if (item instanceof SplashPotionItem || item instanceof LingeringPotionItem) {
                    if (splashSlot < 0) splashSlot = i;
                } else if (item instanceof PotionItem) {
                    if (normalSlot < 0) normalSlot = i;
                }
            }
        }
        

        return splashSlot >= 0 ? splashSlot : normalSlot;
    }
    
    
    private static boolean isHealingPotion(ItemStack stack) {
        var potionContents = stack.get(DataComponents.POTION_CONTENTS);
        if (potionContents == null) return false;
        

        for (var effect : potionContents.getAllEffects()) {
            var effectType = effect.getEffect().value();
            String effectName = effectType.toString().toLowerCase();
            if (effectName.contains("healing") || effectName.contains("instant_health")) {
                return true;
            }
        }
        

        var potion = potionContents.potion();
        if (potion.isPresent()) {
            String potionName = potion.get().getRegisteredName().toLowerCase();
            if (potionName.contains("healing") || potionName.contains("health")) {
                return true;
            }
        }
        
        return false;
    }
    
    
    private static boolean handleAutoMend(ServerPlayer bot, BotState state, BotSettings settings, MinecraftServer server) {
        var inventory = bot.getInventory();
        

        int xpBottleSlot = findXpBottle(inventory);
        if (xpBottleSlot < 0) {
            state.isMending = false;
            state.xpBottlesThrown = 0;
            state.xpBottlesNeeded = 0;
            return false;
        }
        

        int totalDamageToRepair = 0;
        int itemsNeedingRepair = 0;
        
        for (int armorSlot = 36; armorSlot < 40; armorSlot++) {
            ItemStack armorPiece = inventory.getItem(armorSlot);
            if (armorPiece.isEmpty()) continue;
            

            if (!hasMendingEnchantment(armorPiece)) continue;
            

            int maxDamage = armorPiece.getMaxDamage();
            int currentDamage = armorPiece.getDamageValue();
            double durabilityPercent = 1.0 - ((double) currentDamage / maxDamage);
            

            if (durabilityPercent < settings.getMendDurabilityThreshold()) {

                int targetDamage = (int) (maxDamage * 0.1);
                int damageToRepair = currentDamage - targetDamage;
                if (damageToRepair > 0) {
                    totalDamageToRepair += damageToRepair;
                    itemsNeedingRepair++;
                }
            }
        }
        
        if (totalDamageToRepair <= 0) {

            state.isMending = false;
            state.xpBottlesThrown = 0;
            state.xpBottlesNeeded = 0;
            return false;
        }
        

        if (!state.isMending) {



            state.xpBottlesNeeded = (totalDamageToRepair / 28) + 2;
            if (state.xpBottlesNeeded < 5) state.xpBottlesNeeded = 5;
            state.xpBottlesThrown = 0;
        }
        

        state.isMending = true;
        

        var combatState = BotCombat.getState(bot.getName().getString());
        Entity target = combatState.target;
        

        if (target != null) {
            BotNavigation.lookAway(bot, target);
            BotNavigation.moveAway(bot, target, 1.3);
        }
        

        if (state.xpBottlesThrown >= state.xpBottlesNeeded) {

            state.isMending = false;
            state.xpBottlesThrown = 0;
            state.xpBottlesNeeded = 0;
            return false;
        }
        

        if (xpBottleSlot >= 9) {
            ItemStack xpBottle = inventory.getItem(xpBottleSlot);
            ItemStack current = inventory.getItem(8);
            inventory.setItem(xpBottleSlot, current);
            inventory.setItem(8, xpBottle);
            xpBottleSlot = 8;
        }
        

        org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, xpBottleSlot);
        

        bot.setXRot(90);
        

        executeCommand(server, bot, "player " + bot.getName().getString() + " use once");
        
        state.xpBottlesThrown++;
        
        return true;
    }
    
    
    private static boolean hasMendingEnchantment(ItemStack stack) {
        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return false;
        

        for (var entry : enchantments.keySet()) {
            String enchantName = entry.getRegisteredName().toLowerCase();
            if (enchantName.contains("mending")) {
                return true;
            }
        }
        return false;
    }
    
    
    private static int findXpBottle(net.minecraft.world.entity.player.Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).getItem() == Items.EXPERIENCE_BOTTLE) {
                return i;
            }
        }
        return -1;
    }
    
    
    public static void handleCobwebEscape(ServerPlayer bot, BotState state, MinecraftServer server) {
        boolean inCobweb = bot.level().getBlockState(bot.blockPosition()).getBlock() == net.minecraft.world.level.block.Blocks.COBWEB;
        
        if (state.needsToCollectWater && state.waterPosition != null) {
            net.minecraft.world.phys.Vec3 botPos = new net.minecraft.world.phys.Vec3(bot.getX(), bot.getY(), bot.getZ());
            double distToWater = botPos.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(state.waterPosition));
            
            System.out.println("[COBWEB] Returning for water. Distance: " + distToWater + ", Position: " + state.waterPosition);
            
            if (distToWater < 1.5) {
                net.minecraft.world.level.block.state.BlockState blockStateAtWater = bot.level().getBlockState(state.waterPosition);
                net.minecraft.world.level.block.Block blockAtWater = blockStateAtWater.getBlock();
                
                System.out.println("[COBWEB] Close to water! Block: " + blockAtWater + ", isWater: " + blockStateAtWater.is(net.minecraft.world.level.block.Blocks.WATER));
                
                if (blockStateAtWater.is(net.minecraft.world.level.block.Blocks.WATER)) {
                    System.out.println("[COBWEB] Collecting water at saved position...");
                    
                    if (state.cobwebEscapeSlot >= 0 && state.cobwebEscapeSlot < 9) {
                        org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(bot.getInventory(), state.cobwebEscapeSlot);
                    }
                    
                    bot.setXRot(90.0f);
                    executeCommand(server, bot, "player " + bot.getName().getString() + " use once");
                    
                    state.needsToCollectWater = false;
                    state.waterPosition = null;
                    state.cobwebEscapeSlot = -1;
                    System.out.println("[COBWEB] Water collected successfully!");
                    return;
                } else {
                    System.out.println("[COBWEB] Water not found at position, cancelling...");
                    state.needsToCollectWater = false;
                    state.waterPosition = null;
                    return;
                }
            }
            
            net.minecraft.world.phys.Vec3 waterPos = net.minecraft.world.phys.Vec3.atCenterOf(state.waterPosition);
            BotNavigation.lookAtPosition(bot, waterPos);
            BotNavigation.moveTowardPosition(bot, waterPos, 0.8);
            return;
        }
        
        if (state.isEscapingCobweb) {
            state.cobwebEscapeTicks++;
            

            bot.setXRot(90.0f);
            bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
            

            if (state.cobwebEscapeSlot >= 0 && state.cobwebEscapeSlot < 9) {
                org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(bot.getInventory(), state.cobwebEscapeSlot);
            }
            
            if (state.cobwebEscapeTicks == 5) {

                net.minecraft.core.BlockPos waterPos = bot.blockPosition().below();
                state.waterPosition = waterPos;
                
                System.out.println("[COBWEB] Tick 5 - Collecting water at position: " + waterPos);
                executeCommand(server, bot, "player " + bot.getName().getString() + " use once");
                

                ItemStack currentItem = bot.getInventory().getItem(state.cobwebEscapeSlot);
                if (currentItem.getItem() != Items.WATER_BUCKET) {
                    System.out.println("[COBWEB] Water not collected, will return later. Current item: " + currentItem.getItem());
                    state.needsToCollectWater = true;
                } else {
                    System.out.println("[COBWEB] Water collected successfully!");
                    state.needsToCollectWater = false;
                    state.waterPosition = null;
                }
            }
            
            if (state.cobwebEscapeTicks >= 10) {
                System.out.println("[COBWEB] Tick 10 - Finishing escape process...");
                executeCommand(server, bot, "player " + bot.getName().getString() + " stop");
                state.isEscapingCobweb = false;
                state.cobwebEscapeTicks = 0;
                state.isInCobweb = false;
                

                if (!state.needsToCollectWater) {
                    state.cobwebEscapeSlot = -1;
                }
            }
            return;
        }
        
        state.isInCobweb = inCobweb;
        
        if (!inCobweb) {
            return;
        }
        
        int waterSlot = findWaterBucket(bot.getInventory());
        int pearlSlot = findEnderPearl(bot.getInventory());
        
        if (waterSlot < 0 && pearlSlot < 0) {
            System.out.println("[COBWEB] No water bucket or ender pearl found! Bot can attack normally.");
            return;
        }
        

        if (waterSlot >= 0) {

            if (waterSlot >= 9) {
                ItemStack water = bot.getInventory().getItem(waterSlot);
                ItemStack current = bot.getInventory().getItem(8);
                bot.getInventory().setItem(waterSlot, current);
                bot.getInventory().setItem(8, water);
                waterSlot = 8;
            }
            
            System.out.println("[COBWEB] Starting escape process - placing water...");
            
            state.cobwebEscapeSlot = waterSlot;
            state.isEscapingCobweb = true;
            state.cobwebEscapeTicks = 0;
            state.waterPosition = bot.blockPosition().below();
            state.needsToCollectWater = false;
            

            executeCommand(server, bot, "player " + bot.getName().getString() + " stop");
            bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
            

            org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(bot.getInventory(), waterSlot);
            bot.setXRot(90.0f);
            

            System.out.println("[COBWEB] Placing water at position: " + state.waterPosition);
            executeCommand(server, bot, "player " + bot.getName().getString() + " use once");
        } else if (pearlSlot >= 0) {

            if (pearlSlot >= 9) {
                ItemStack pearl = bot.getInventory().getItem(pearlSlot);
                ItemStack current = bot.getInventory().getItem(8);
                bot.getInventory().setItem(pearlSlot, current);
                bot.getInventory().setItem(8, pearl);
                pearlSlot = 8;
            }
            
            System.out.println("[COBWEB] Using ender pearl to escape...");
            

            org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(bot.getInventory(), pearlSlot);
            

            bot.setXRot(0.0f);
            
            executeCommand(server, bot, "player " + bot.getName().getString() + " use once");
            

            state.isInCobweb = false;
        }
    }
    
    
    private static int findWaterBucket(net.minecraft.world.entity.player.Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() == Items.WATER_BUCKET) {
                return i;
            }
        }
        return -1;
    }
    
    private static int findEnderPearl(net.minecraft.world.entity.player.Inventory inventory) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() == Items.ENDER_PEARL) {
                return i;
            }
        }
        return -1;
    }
    
    
    public static boolean canAttack(ServerPlayer bot, BotState state) {

        if (state.isEscapingCobweb || state.needsToCollectWater) {
            return false;
        }
        

        if (state.isInCobweb) {
            int waterSlot = findWaterBucket(bot.getInventory());
            int pearlSlot = findEnderPearl(bot.getInventory());
            
            if (waterSlot < 0 && pearlSlot < 0) {
                System.out.println("[COBWEB] " + bot.getName().getString() + " in cobweb but no water/pearl - can attack");
                return true;
            }
            System.out.println("[COBWEB] " + bot.getName().getString() + " in cobweb with escape items - cannot attack");
            return false;
        }
        
        return true;
    }
}
