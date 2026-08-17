package com.mccontroler.inv;

import com.mccontroler.MCControler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which items the bot may burn, and in what order it reaches for them.
 *
 * <p>What counts as fuel comes from the game itself rather than a hardcoded list, so it stays
 * correct across versions and picks up modded fuels. What the bot is <em>allowed</em> to burn is
 * the player's decision: nobody wants their bookshelves or a spare boat going into a furnace
 * because they happened to be flammable.
 *
 * <p>Stored one directive per line next to the save:
 * <pre>
 * block   minecraft:oak_boat
 * prefer  minecraft:charcoal
 * </pre>
 */
public final class FuelConfig {

    /** Reached for first, in this order, before anything else. */
    private static final List<String> DEFAULT_PREFERENCE = List.of(
            "minecraft:coal",
            "minecraft:charcoal",
            "minecraft:coal_block",
            "minecraft:dried_kelp_block",
            "minecraft:blaze_rod");

    /**
     * Burnable, but never worth burning.
     *
     * <p>A crafting table is wood, so the game happily counts it as fuel — and the bot cheerfully
     * fed one into a furnace, smelted the iron perfectly, then failed the next step because it no
     * longer had a table to craft on. Workstations, containers, boats and tools are all flammable
     * and all far more valuable as themselves.
     */
    private static final Set<String> DEFAULT_BLOCKED = Set.of(
            "minecraft:crafting_table",
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:bookshelf", "minecraft:chiseled_bookshelf", "minecraft:lectern",
            "minecraft:cartography_table", "minecraft:fletching_table",
            "minecraft:smithing_table", "minecraft:loom", "minecraft:composter",
            "minecraft:jukebox", "minecraft:note_block", "minecraft:beehive",
            "minecraft:bee_nest", "minecraft:ladder", "minecraft:bow",
            "minecraft:fishing_rod", "minecraft:shield", "minecraft:bowl");

    /** Item id suffixes that are never burned: boats, tools and the like. */
    private static final List<String> DEFAULT_BLOCKED_SUFFIXES = List.of(
            "_boat", "_chest_boat", "_raft", "_chest_raft",
            "_sign", "_hanging_sign",
            "wooden_pickaxe", "wooden_axe", "wooden_shovel", "wooden_hoe", "wooden_sword");

    /** True when an item is blocked by default, before any of the player's own choices. */
    private static boolean blockedByDefault(String id) {
        if (DEFAULT_BLOCKED.contains(id)) {
            return true;
        }
        for (String suffix : DEFAULT_BLOCKED_SUFFIXES) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static final Object LOCK = new Object();
    private static Set<String> blocked;
    private static List<String> preferred;

    private FuelConfig() {
    }

    public record Fuel(String id, String name, int burnTicks, int have, boolean blocked, int rank) {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("mccontroler").resolve("fuel.tsv");
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (blocked != null) {
                return;
            }
            blocked = new LinkedHashSet<>();
            preferred = new ArrayList<>(DEFAULT_PREFERENCE);

            Path path = file();
            if (!Files.exists(path)) {
                // No saved choices yet: start from the safe defaults.
                blocked.addAll(DEFAULT_BLOCKED);
                return;
            }
            try {
                List<String> custom = new ArrayList<>();
                boolean versioned = false;
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t");
                    if (parts.length != 2) {
                        continue;
                    }
                    switch (parts[0]) {
                        case "version" -> versioned = true;
                        case "block" -> blocked.add(parts[1]);
                        case "prefer" -> custom.add(parts[1]);
                        default -> {
                            // unknown directive, ignore
                        }
                    }
                }
                if (!custom.isEmpty()) {
                    preferred = custom;
                }
                // A file written before workstations were protected would happily list the
                // crafting table as usable fuel. Re-apply the protections to those.
                if (!versioned) {
                    blocked.addAll(DEFAULT_BLOCKED);
                    preferred.removeAll(DEFAULT_BLOCKED);
                    MCControler.LOGGER.info(
                            "[MC Controler] upgraded fuel settings — protected workstations and tools");
                }
            } catch (IOException e) {
                MCControler.LOGGER.error("[MC Controler] could not read fuel.tsv", e);
                blocked.addAll(DEFAULT_BLOCKED);
            }
        }
    }

    public static void save(List<String> newBlocked, List<String> newPreferred) {
        synchronized (LOCK) {
            ensureLoaded();
            blocked = new LinkedHashSet<>(newBlocked);
            if (!newPreferred.isEmpty()) {
                preferred = new ArrayList<>(newPreferred);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("version\t2\n");
            for (String id : blocked) {
                sb.append("block\t").append(id).append('\n');
            }
            for (String id : preferred) {
                sb.append("prefer\t").append(id).append('\n');
            }
            try {
                Path path = file();
                Files.createDirectories(Objects.requireNonNull(path.getParent()));
                Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                MCControler.LOGGER.error("[MC Controler] could not save fuel.tsv", e);
            }
        }
    }

    public static boolean isBlocked(String id) {
        ensureLoaded();
        synchronized (LOCK) {
            if (blocked.contains(id)) {
                return true;
            }
            // Suffix protections (boats, wooden tools, signs) hold unless deliberately allowed.
            return blockedByDefault(id) && !preferred.contains(id);
        }
    }

    /** Lower is reached for sooner. Unlisted fuels sort after everything named. */
    private static int rank(String id) {
        ensureLoaded();
        synchronized (LOCK) {
            int at = preferred.indexOf(id);
            return at < 0 ? Integer.MAX_VALUE : at;
        }
    }

    /**
     * Every item the game considers fuel, with how much of it the player is carrying.
     * Client thread only.
     */
    public static List<Fuel> catalogue() {
        List<Fuel> out = new ArrayList<>();
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return out;
        }
        for (Item item : level.fuelValues().fuelItems()) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            ItemStack stack = new ItemStack(item);
            out.add(new Fuel(id, stack.getHoverName().getString(),
                    level.fuelValues().burnDuration(stack),
                    InventoryHelper.count(item), isBlocked(id), rank(id)));
        }
        // Preferred first, then the ones you actually have, then longest-burning.
        out.sort(Comparator.comparingInt(Fuel::rank)
                .thenComparing((Fuel f) -> f.have() > 0 ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Fuel::burnTicks).reversed()));
        return out;
    }

    /**
     * The best allowed fuel the player is currently carrying, or null.
     * Client thread only.
     */
    public static Item pick() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        Item best = null;
        int bestRank = Integer.MAX_VALUE;
        int bestBurn = -1;

        for (Item item : level.fuelValues().fuelItems()) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (isBlocked(id) || InventoryHelper.count(item) == 0) {
                continue;
            }
            int rank = rank(id);
            int burn = level.fuelValues().burnDuration(new ItemStack(item));
            // Explicit preference wins; otherwise burn longest first to save trips.
            if (rank < bestRank || (rank == bestRank && burn > bestBurn)) {
                best = item;
                bestRank = rank;
                bestBurn = burn;
            }
        }
        return best;
    }
}
