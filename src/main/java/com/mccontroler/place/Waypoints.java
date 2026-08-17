package com.mccontroler.place;

import com.mccontroler.MCControler;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Named positions the player can send the bot to.
 *
 * <p>Stored as one tab-separated line per waypoint next to the save, rather than JSON, so the
 * file stays trivially readable and hand-editable and needs no parser beyond {@code split}.
 */
public final class Waypoints {

    public record Waypoint(String name, String dimension, int x, int y, int z) {
    }

    private static final Object LOCK = new Object();
    private static List<Waypoint> cache;

    private Waypoints() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("mccontroler").resolve("waypoints.tsv");
    }

    /** All saved waypoints, newest last. */
    public static List<Waypoint> all() {
        synchronized (LOCK) {
            if (cache == null) {
                cache = load();
            }
            return List.copyOf(cache);
        }
    }

    public static Waypoint byName(String name) {
        String key = normalise(name);
        return all().stream()
                .filter(w -> normalise(w.name()).equals(key))
                .findFirst()
                .orElse(null);
    }

    /** Saves a waypoint, replacing any existing one with the same name. */
    public static Waypoint save(String name, String dimension, int x, int y, int z) {
        Waypoint wp = new Waypoint(name.trim(), dimension, x, y, z);
        synchronized (LOCK) {
            all();
            cache.removeIf(w -> normalise(w.name()).equals(normalise(wp.name())));
            cache.add(wp);
            persist();
        }
        return wp;
    }

    /** Removes a waypoint by name. Returns true when something was actually removed. */
    public static boolean delete(String name) {
        synchronized (LOCK) {
            all();
            boolean removed = cache.removeIf(w -> normalise(w.name()).equals(normalise(name)));
            if (removed) {
                persist();
            }
            return removed;
        }
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static List<Waypoint> load() {
        List<Waypoint> out = new ArrayList<>();
        Path path = file();
        if (!Files.exists(path)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length != 5) {
                    MCControler.LOGGER.warn("[MC Controler] skipping malformed waypoint: {}", line);
                    continue;
                }
                try {
                    out.add(new Waypoint(parts[0], parts[1],
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4])));
                } catch (NumberFormatException e) {
                    MCControler.LOGGER.warn("[MC Controler] bad coordinates in waypoint: {}", line);
                }
            }
        } catch (IOException e) {
            MCControler.LOGGER.error("[MC Controler] could not read waypoints", e);
        }
        return out;
    }

    private static void persist() {
        Path path = file();
        StringBuilder sb = new StringBuilder();
        for (Waypoint w : cache) {
            // Tabs are the separator, so they cannot survive inside a name.
            sb.append(w.name().replace('\t', ' ')).append('\t')
                    .append(w.dimension()).append('\t')
                    .append(w.x()).append('\t')
                    .append(w.y()).append('\t')
                    .append(w.z()).append('\n');
        }
        try {
            Files.createDirectories(Objects.requireNonNull(path.getParent()));
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MCControler.LOGGER.error("[MC Controler] could not save waypoints", e);
        }
    }
}
