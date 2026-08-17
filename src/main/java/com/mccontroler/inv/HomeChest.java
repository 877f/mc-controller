package com.mccontroler.inv;

import com.mccontroler.MCControler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Where the bot unloads.
 *
 * <p>A long excavation fills 36 slots long before it finishes, at which point everything stops.
 * A known chest turns that dead end into a round trip.
 */
public final class HomeChest {

    /** Kept out of the chest: the bot needs these to keep working. */
    private static final java.util.List<String> KEEP_SUFFIXES = java.util.List.of(
            "_pickaxe", "_axe", "_shovel", "_hoe", "_sword", "shears");

    private static final java.util.Set<String> KEEP_ITEMS = java.util.Set.of(
            "minecraft:torch", "minecraft:crafting_table", "minecraft:furnace",
            "minecraft:coal", "minecraft:charcoal", "minecraft:water_bucket");

    private static final Object LOCK = new Object();
    private static BlockPos position;
    private static String dimension;
    private static boolean loaded;

    private HomeChest() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("mccontroler").resolve("chest.tsv");
    }

    public static BlockPos position() {
        ensureLoaded();
        synchronized (LOCK) {
            return position;
        }
    }

    public static String dimension() {
        ensureLoaded();
        synchronized (LOCK) {
            return dimension;
        }
    }

    public static boolean isSet() {
        return position() != null;
    }

    public static void set(BlockPos pos, String dim) {
        synchronized (LOCK) {
            position = pos;
            dimension = dim;
            loaded = true;
            try {
                Path path = file();
                Files.createDirectories(Objects.requireNonNull(path.getParent()));
                Files.writeString(path, dim + "\t" + pos.getX() + "\t" + pos.getY()
                        + "\t" + pos.getZ() + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                MCControler.LOGGER.error("[MC Controler] could not save the home chest", e);
            }
        }
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            loaded = true;
            Path path = file();
            if (!Files.exists(path)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t");
                    if (parts.length == 4) {
                        dimension = parts[0];
                        position = new BlockPos(Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                MCControler.LOGGER.error("[MC Controler] could not read the home chest", e);
            }
        }
    }

    /** Nearest chest to the player within {@code radius}, or null. Client thread only. */
    public static BlockPos findNearby(int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }
        BlockPos origin = mc.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!(mc.level.getBlockState(candidate).getBlock() instanceof ChestBlock)) {
                        continue;
                    }
                    double distance = candidate.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /** True when an item should stay with the bot rather than going into the chest. */
    public static boolean keep(String itemId) {
        if (KEEP_ITEMS.contains(itemId)) {
            return true;
        }
        for (String suffix : KEEP_SUFFIXES) {
            if (itemId.endsWith(suffix)) {
                return true;
            }
        }
        // Food keeps the bot alive; it is never worth banking.
        var item = InventoryHelper.resolve(itemId);
        return item != null && new net.minecraft.world.item.ItemStack(item)
                .has(net.minecraft.core.component.DataComponents.FOOD);
    }
}
