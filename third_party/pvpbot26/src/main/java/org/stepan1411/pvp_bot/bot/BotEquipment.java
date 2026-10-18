package org.stepan1411.pvp_bot.bot;

import net.minecraft.world.item.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;

public class BotEquipment {

    public static void autoEquip(ServerPlayer bot) {

        var utilsState = BotUtils.getState(bot.getName().getString());
        if (utilsState.isEating) {
            return;
        }
        
        BotSettings settings = BotSettings.get();
        
        if (settings.isAutoEquipArmor()) {
            equipBestArmor(bot);
        }
        if (settings.isAutoEquipWeapon()) {
            equipBestWeapon(bot);
        }
    }

    private static void equipBestArmor(ServerPlayer bot) {
        equipBestForSlot(bot, EquipmentSlot.HEAD);
        equipBestForSlot(bot, EquipmentSlot.CHEST);
        equipBestForSlot(bot, EquipmentSlot.LEGS);
        equipBestForSlot(bot, EquipmentSlot.FEET);
    }

    private static void equipBestForSlot(ServerPlayer bot, EquipmentSlot slot) {
        BotSettings settings = BotSettings.get();
        var inventory = bot.getInventory();
        

        ItemStack currentArmor = bot.getItemBySlot(slot);
        double currentValue = getArmorValue(currentArmor, slot);
        
        int bestInvSlot = -1;
        double bestValue = currentValue;
        

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            if (!isArmorForSlot(stack.getItem(), slot)) continue;
            
            double value = getArmorValue(stack, slot);
            if (value > bestValue) {
                bestValue = value;
                bestInvSlot = i;
            }
        }
        

        if (bestInvSlot >= 0) {
            ItemStack newArmor = inventory.getItem(bestInvSlot).copy();
            ItemStack oldArmor = currentArmor.copy();
            

            bot.setItemSlot(slot, newArmor);
            inventory.setItem(bestInvSlot, ItemStack.EMPTY);
            

            if (!oldArmor.isEmpty()) {
                inventory.setItem(bestInvSlot, oldArmor);
            }
            
            currentArmor = newArmor;
            currentValue = bestValue;
        }

        

        if (settings.isDropWorseArmor()) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                if (!isArmorForSlot(stack.getItem(), slot)) continue;
                
                double value = getArmorValue(stack, slot);
                

                if (value < currentValue) {
                    dropItemBackward(bot, stack);
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    
    private static void dropItemBackward(ServerPlayer bot, ItemStack stack) {
        if (stack.isEmpty()) return;
        
        BotSettings settings = BotSettings.get();
        double distance = settings.getDropDistance();
        

        float oldYaw = bot.getYRot();
        float oldHeadYaw = bot.getYHeadRot();
        

        float turnAngle = 90 + (float)(Math.random() * 180);
        float newYaw = oldYaw + turnAngle;
        
        bot.setYRot(newYaw);
        bot.setYHeadRot(newYaw);
        

        ItemEntity dropped = bot.drop(stack.copy(), false, net.minecraft.util.Prediction.SERVER_ONLY);
        
        if (dropped != null) {

            double yawRad = Math.toRadians(newYaw);
            double speed = 0.3 * distance;
            dropped.setDeltaMovement(
                -Math.sin(yawRad) * speed,
                0.2,
                Math.cos(yawRad) * speed
            );
            dropped.setPickUpDelay(60);
        }
        

        bot.setYRot(oldYaw);
        bot.setYHeadRot(oldHeadYaw);
    }

    private static boolean isArmorForSlot(Item item, EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> item == Items.NETHERITE_HELMET || item == Items.DIAMOND_HELMET ||
                         item == Items.IRON_HELMET || item == Items.CHAINMAIL_HELMET ||
                         item == Items.GOLDEN_HELMET || item == Items.LEATHER_HELMET ||
                         item == Items.TURTLE_HELMET;
            case CHEST -> item == Items.NETHERITE_CHESTPLATE || item == Items.DIAMOND_CHESTPLATE ||
                          item == Items.IRON_CHESTPLATE || item == Items.CHAINMAIL_CHESTPLATE ||
                          item == Items.GOLDEN_CHESTPLATE || item == Items.LEATHER_CHESTPLATE ||
                          item == Items.ELYTRA;
            case LEGS -> item == Items.NETHERITE_LEGGINGS || item == Items.DIAMOND_LEGGINGS ||
                         item == Items.IRON_LEGGINGS || item == Items.CHAINMAIL_LEGGINGS ||
                         item == Items.GOLDEN_LEGGINGS || item == Items.LEATHER_LEGGINGS;
            case FEET -> item == Items.NETHERITE_BOOTS || item == Items.DIAMOND_BOOTS ||
                         item == Items.IRON_BOOTS || item == Items.CHAINMAIL_BOOTS ||
                         item == Items.GOLDEN_BOOTS || item == Items.LEATHER_BOOTS;
            default -> false;
        };
    }

    private static double getArmorValue(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        
        if (item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE ||
            item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS) return 100;
        if (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE ||
            item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) return 80;
        if (item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE ||
            item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS) return 60;
        if (item == Items.CHAINMAIL_HELMET || item == Items.CHAINMAIL_CHESTPLATE ||
            item == Items.CHAINMAIL_LEGGINGS || item == Items.CHAINMAIL_BOOTS) return 50;
        if (item == Items.GOLDEN_HELMET || item == Items.GOLDEN_CHESTPLATE ||
            item == Items.GOLDEN_LEGGINGS || item == Items.GOLDEN_BOOTS) return 40;
        if (item == Items.LEATHER_HELMET || item == Items.LEATHER_CHESTPLATE ||
            item == Items.LEATHER_LEGGINGS || item == Items.LEATHER_BOOTS) return 20;
        if (item == Items.TURTLE_HELMET) return 55;
        if (item == Items.ELYTRA) return 10;
        
        return 0;
    }


    private static void equipBestWeapon(ServerPlayer bot) {
        BotSettings settings = BotSettings.get();
        var inventory = bot.getInventory();
        

        int bestSlotIndex = -1;
        double bestScore = 0;
        
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            double score = getWeaponScore(stack, settings.isPreferSword());
            if (score > bestScore) {
                bestScore = score;
                bestSlotIndex = i;
            }
        }
        

        if (bestSlotIndex >= 0) {
            if (bestSlotIndex >= 9) {

                ItemStack weapon = inventory.getItem(bestSlotIndex);
                ItemStack slot0 = inventory.getItem(0);
                inventory.setItem(bestSlotIndex, slot0);
                inventory.setItem(0, weapon);
                bestSlotIndex = 0;
            }
            org.stepan1411.pvp_bot.utils.InventoryHelper.setSelectedSlot(inventory, bestSlotIndex);
        }
        

        if (settings.isDropWorseWeapons() && bestScore > 0) {
            for (int i = 0; i < 36; i++) {
                if (i == bestSlotIndex) continue;
                
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                
                double score = getWeaponScore(stack, settings.isPreferSword());
                if (score > 0 && score < bestScore) {
                    dropItemBackward(bot, stack);
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    
    private static double getWeaponScore(ItemStack stack, boolean preferSword) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        
        double baseDamage = getWeaponDamage(stack);
        if (baseDamage == 0) return 0;
        

        if (preferSword && isSword(item)) {
            return baseDamage + 5;
        }
        
        return baseDamage;
    }
    
    
    private static boolean isSword(Item item) {
        return item == Items.NETHERITE_SWORD || 
               item == Items.DIAMOND_SWORD || 
               item == Items.IRON_SWORD || 
               item == Items.GOLDEN_SWORD || 
               item == Items.STONE_SWORD || 
               item == Items.WOODEN_SWORD;
    }

    private static double getWeaponDamage(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        
        if (item == Items.NETHERITE_SWORD) return 8;
        if (item == Items.NETHERITE_AXE) return 10;
        if (item == Items.DIAMOND_SWORD) return 7;
        if (item == Items.DIAMOND_AXE) return 9;
        if (item == Items.IRON_SWORD) return 6;
        if (item == Items.IRON_AXE) return 9;
        if (item == Items.STONE_SWORD) return 5;
        if (item == Items.STONE_AXE) return 9;
        if (item == Items.GOLDEN_SWORD) return 4;
        if (item == Items.GOLDEN_AXE) return 7;
        if (item == Items.WOODEN_SWORD) return 4;
        if (item == Items.WOODEN_AXE) return 7;
        if (item == Items.TRIDENT) return 9;
        if (item == Items.MACE) return 6;
        
        return 0;
    }
}
