package com.mccontroler.inv;

import com.mccontroler.MCControler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Crafting tables and furnaces the bot is required to use.
 *
 * <p>Left to itself the bot places a station wherever it happens to be standing and leaves it
 * there, which scatters tables and furnaces across the world. Assigning one pins it to a
 * specific block: it walks to that block and uses it, and will not place or craft another.
 *
 * <p>An assignment holds until it is cleared, so this doubles as a way to keep the bot working
 * around your base rather than littering it.
 */
public final class Stations {

    /** Station kinds, keyed by the name used in the config file and the API. */
    public static final String TABLE = "table";
    public static final String FURNACE = "furnace";

    private static final Object LOCK = new Object();
    private static Map<String, BlockPos> assigned;

    private Stations() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("mccontroler").resolve("stations.tsv");
    }

    /** The assigned position for a kind, or null when the bot may use any. */
    public static BlockPos get(String kind) {
        ensureLoaded();
        synchronized (LOCK) {
            return assigned.get(kind);
        }
    }

    public static Map<String, BlockPos> all() {
        ensureLoaded();
        synchronized (LOCK) {
            return new LinkedHashMap<>(assigned);
        }
    }

    public static void set(String kind, BlockPos pos) {
        synchronized (LOCK) {
            ensureLoaded();
            assigned.put(kind, pos);
            persist();
        }
    }

    public static boolean clear(String kind) {
        synchronized (LOCK) {
            ensureLoaded();
            boolean removed = assigned.remove(kind) != null;
            if (removed) {
                persist();
            }
            return removed;
        }
    }

    /** True when the right kind of block is still standing at the assigned spot. */
    public static boolean stillThere(String kind) {
        BlockPos pos = get(kind);
        if (pos == null) {
            return false;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        var state = level.getBlockState(pos);
        return kind.equals(TABLE) ? state.is(Blocks.CRAFTING_TABLE) : state.is(Blocks.FURNACE);
    }

    /**
     * Nearest station of this kind to the player, for the "assign the one I'm standing by"
     * button. Client thread only.
     */
    public static BlockPos findNearby(String kind, int radius) {
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
                    var state = mc.level.getBlockState(candidate);
                    boolean match = kind.equals(TABLE)
                            ? state.is(Blocks.CRAFTING_TABLE)
                            : state.is(Blocks.FURNACE);
                    if (!match) {
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

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (assigned != null) {
                return;
            }
            assigned = new LinkedHashMap<>();
            Path path = file();
            if (!Files.exists(path)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t");
                    if (parts.length == 4) {
                        assigned.put(parts[0], new BlockPos(Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                MCControler.LOGGER.error("[MC Controler] could not read stations.tsv", e);
            }
        }
    }

    private static void persist() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, BlockPos> entry : assigned.entrySet()) {
            BlockPos at = entry.getValue();
            sb.append(entry.getKey()).append('\t')
                    .append(at.getX()).append('\t')
                    .append(at.getY()).append('\t')
                    .append(at.getZ()).append('\n');
        }
        try {
            Path path = file();
            Files.createDirectories(Objects.requireNonNull(path.getParent()));
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MCControler.LOGGER.error("[MC Controler] could not save stations.tsv", e);
        }
    }
}
