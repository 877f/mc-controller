package com.mccontroler.plan;

import com.mccontroler.MCControler;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which materials the planner may use, and which it should reach for first.
 *
 * <p>Recipes name broad tags — {@code #minecraft:planks} covers every wood — and the planner
 * otherwise picks purely on cost, so it will happily fell an oak when the base is built from
 * birch. This lets a preference win over a marginal cost difference, and lets a material be
 * banned outright.
 *
 * <p>Stored one directive per line:
 * <pre>
 * prefer  minecraft:birch_log
 * ban     minecraft:oak_log
 * </pre>
 */
public final class MaterialPolicy {

    private static final Object LOCK = new Object();
    private static List<String> preferred;
    private static Set<String> banned;

    private MaterialPolicy() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("mccontroler").resolve("materials.tsv");
    }

    public static List<String> preferred() {
        ensureLoaded();
        synchronized (LOCK) {
            return List.copyOf(preferred);
        }
    }

    public static Set<String> banned() {
        ensureLoaded();
        synchronized (LOCK) {
            return Set.copyOf(banned);
        }
    }

    /** True when the planner must not use this item at all. */
    public static boolean isBanned(String itemId) {
        ensureLoaded();
        synchronized (LOCK) {
            return banned.contains(itemId);
        }
    }

    /**
     * Preference rank: lower is chosen sooner, {@link Integer#MAX_VALUE} for anything unlisted.
     * Used as a tie-break ahead of cost, so a preference does not override a genuinely cheaper
     * route by an order of magnitude — only a close call.
     */
    public static int rank(String itemId) {
        ensureLoaded();
        synchronized (LOCK) {
            int at = preferred.indexOf(itemId);
            return at < 0 ? Integer.MAX_VALUE : at;
        }
    }

    public static void save(List<String> newPreferred, List<String> newBanned) {
        synchronized (LOCK) {
            preferred = new ArrayList<>(newPreferred);
            banned = new LinkedHashSet<>(newBanned);

            StringBuilder sb = new StringBuilder();
            for (String id : preferred) {
                sb.append("prefer\t").append(id).append('\n');
            }
            for (String id : banned) {
                sb.append("ban\t").append(id).append('\n');
            }
            try {
                Path path = file();
                Files.createDirectories(Objects.requireNonNull(path.getParent()));
                Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                MCControler.LOGGER.error("[MC Controler] could not save materials.tsv", e);
            }
        }
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (preferred != null) {
                return;
            }
            preferred = new ArrayList<>();
            banned = new LinkedHashSet<>();

            Path path = file();
            if (!Files.exists(path)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t");
                    if (parts.length != 2) {
                        continue;
                    }
                    if (parts[0].equals("prefer")) {
                        preferred.add(parts[1]);
                    } else if (parts[0].equals("ban")) {
                        banned.add(parts[1]);
                    }
                }
            } catch (IOException e) {
                MCControler.LOGGER.error("[MC Controler] could not read materials.tsv", e);
            }
        }
    }
}
