package com.mccontroler.inv;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads the local player's inventory. Client thread only. */
public final class InventoryHelper {

    private InventoryHelper() {
    }

    /** Total count of an item across the whole inventory, including the offhand and armour slots. */
    public static int count(Item item) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static int count(String itemId) {
        Item item = resolve(itemId);
        return item == null ? 0 : count(item);
    }

    /** Looks up an item by registry id, or null when the id is unknown. */
    public static Item resolve(String itemId) {
        Identifier id = Identifier.parse(itemId);
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /** Free slots in the main inventory (hotbar included, armour and offhand excluded). */
    public static int freeSlots() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        Inventory inv = player.getInventory();
        int free = 0;
        // The main grid is the first 36 slots; beyond that lie armour and the offhand.
        int mainSize = Math.min(36, inv.getContainerSize());
        for (int i = 0; i < mainSize; i++) {
            if (inv.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** True when the main inventory has no empty slot left. */
    public static boolean isFull() {
        return freeSlots() == 0;
    }

    /**
     * Stone and dirt that fill the inventory during any serious dig.
     *
     * <p>Used when no explicit list is given. Deliberately conservative — only bulk terrain, never
     * ores, drops or anything crafted.
     */
    public static final List<String> DEFAULT_JUNK = List.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:deepslate",
            "minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
            "minecraft:tuff", "minecraft:dirt", "minecraft:gravel", "minecraft:netherrack",
            "minecraft:rotten_flesh", "minecraft:sandstone");

    /**
     * Throws away every stack matching these ids.
     *
     * @return how many stacks were dropped
     */
    public static int dropMatching(Collection<String> itemIds) {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.gameMode == null) {
            return 0;
        }

        Set<Item> unwanted = new HashSet<>();
        for (String id : itemIds) {
            Item item = resolve(id);
            if (item != null) {
                unwanted.add(item);
            }
        }
        if (unwanted.isEmpty()) {
            return 0;
        }

        Inventory inv = player.getInventory();
        int dropped = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !unwanted.contains(stack.getItem())) {
                continue;
            }
            // Inventory indices run hotbar-first; the inventory menu puts the hotbar at 36..44.
            int menuSlot = i < Inventory.getSelectionSize() ? i + 36 : i;
            // Button 1 with THROW drops the whole stack, matching ctrl-Q in the inventory.
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, 1,
                    ContainerInput.THROW, player);
            dropped++;
        }
        return dropped;
    }
}
