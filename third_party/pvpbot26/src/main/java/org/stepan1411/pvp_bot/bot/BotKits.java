package org.stepan1411.pvp_bot.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DataResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BotKits {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    private static MinecraftServer currentServer;
    

    private static final Map<String, Map<String, Object>> kitsRaw = new HashMap<>();
    
    
    public static void init(MinecraftServer srv) {
        currentServer = srv;
        

        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("pvpbot");
        try {
            Files.createDirectories(configDir);
        } catch (Exception e) {

        }
        
        configPath = org.stepan1411.pvp_bot.config.WorldConfigHelper.getGlobalConfigDir().resolve("kits.json");
        load();
    }
    
    public static void reload(MinecraftServer srv) {
        currentServer = srv;
        configPath = org.stepan1411.pvp_bot.config.WorldConfigHelper.getGlobalConfigDir().resolve("kits.json");
        load();
    }
    
    
    public static boolean createKit(String kitName, ServerPlayer player) {
        String key = kitName.toLowerCase();
        if (kitsRaw.containsKey(key)) {
            return false;
        }
        
        Map<String, Object> kitItems = new HashMap<>();
        var inventory = player.getInventory();
        var registryOps = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
        

        for (int i = 0; i < 41; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {

                DataResult<Tag> result = ItemStack.CODEC.encodeStart(registryOps, stack);
                if (result.result().isPresent()) {
                    Tag nbt = result.result().get();

                    kitItems.put(String.valueOf(i), nbt.toString());
                }
            }
        }
        
        if (kitItems.isEmpty()) {
            return false;
        }
        
        kitsRaw.put(key, kitItems);
        save();
        return true;
    }
    
    
    public static boolean deleteKit(String kitName) {
        boolean removed = kitsRaw.remove(kitName.toLowerCase()) != null;
        if (removed) save();
        return removed;
    }
    
    
    public static Set<String> getKitNames() {
        return new HashSet<>(kitsRaw.keySet());
    }
    
    
    public static boolean kitExists(String kitName) {
        return kitsRaw.containsKey(kitName.toLowerCase());
    }
    
    
    public static Map<Integer, ItemStack> getKitItems(String kitName, ServerPlayer player) {
        Map<String, Object> data = kitsRaw.get(kitName.toLowerCase());
        if (data == null) return null;
        
        var registryOps = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
        Map<Integer, ItemStack> items = new HashMap<>();
        
        for (var entry : data.entrySet()) {
            try {
                int slot = Integer.parseInt(entry.getKey());
                String nbtString = entry.getValue().toString();
                

                CompoundTag nbt = net.minecraft.nbt.TagParser.parseCompoundFully(nbtString);
                

                DataResult<ItemStack> result = ItemStack.CODEC.parse(registryOps, nbt);
                if (result.result().isPresent()) {
                    items.put(slot, result.result().get());
                }
            } catch (Exception e) {
                System.out.println("[PVP_BOT] Failed to parse kit item: " + e.getMessage());
            }
        }
        return items;
    }
    
    
    public static boolean giveKit(String kitName, ServerPlayer bot) {
        Map<Integer, ItemStack> kitItems = getKitItems(kitName, bot);
        if (kitItems == null) return false;
        
        var inventory = bot.getInventory();
        

        inventory.clearContent();
        

        for (var entry : kitItems.entrySet()) {
            int slot = entry.getKey();
            ItemStack stack = entry.getValue();
            inventory.setItem(slot, stack.copy());
        }
        
        return true;
    }
    
    
    public static int giveKitToFaction(String kitName, String factionName) {
        if (!kitExists(kitName)) return -1;
        
        Set<String> members = BotFaction.getMembers(factionName);
        if (members == null || members.isEmpty()) return 0;
        
        return members.size();
    }
    

    
    private static void load() {
        if (configPath == null || !Files.exists(configPath)) return;
        
        try (Reader reader = Files.newBufferedReader(configPath)) {
            Map<String, Map<String, Object>> loaded = GSON.fromJson(
                reader, 
                new TypeToken<Map<String, Map<String, Object>>>(){}.getType()
            );
            if (loaded != null) {
                kitsRaw.clear();
                kitsRaw.putAll(loaded);
            }
        } catch (Exception e) {
            System.out.println("[PVP_BOT] Failed to load kits: " + e.getMessage());
        }
    }
    
    private static void save() {
        if (configPath == null) return;
        
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(kitsRaw, writer);
        } catch (Exception e) {
            System.out.println("[PVP_BOT] Failed to save kits: " + e.getMessage());
        }
    }
}
