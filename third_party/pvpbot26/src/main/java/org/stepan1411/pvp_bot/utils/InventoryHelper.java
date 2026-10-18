package org.stepan1411.pvp_bot.utils;

import net.minecraft.world.entity.player.Inventory;

/** Port to 26.3: public Inventory methods instead of reflection on an obfuscated field name. */
public class InventoryHelper {
    public static void setSelectedSlot(Inventory inventory, int slot) {
        inventory.setSelectedSlot(slot);
    }

    public static int getSelectedSlot(Inventory inventory) {
        return inventory.getSelectedSlot();
    }
}
