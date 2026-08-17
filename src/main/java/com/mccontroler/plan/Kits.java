package com.mccontroler.plan;

import com.mccontroler.MCControler;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Named shopping lists — "starter kit", "mining trip" — so a whole loadout is one click.
 *
 * <p>Stored one line per kit next to the save:
 * <pre>
 * starter kit&#9;minecraft:iron_pickaxe*1,minecraft:torch*32
 * </pre>
 */
public final class Kits {

    /** One line of a kit: an item and how many. */
    public record Entry(String itemId, int count) {
    }

    private static final Object LOCK = new Object();
    private static Map<String, List<Entry>> kits;

    private Kits() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("mccontroler").resolve("kits.tsv");
    }

    public static Map<String, List<Entry>> all() {
        ensureLoaded();
        synchronized (LOCK) {
            return new LinkedHashMap<>(kits);
        }
    }

    public static List<Entry> get(String name) {
        ensureLoaded();
        synchronized (LOCK) {
            return kits.get(normalise(name));
        }
    }

    public static void save(String name, List<Entry> entries) {
        ensureLoaded();
        synchronized (LOCK) {
            kits.put(normalise(name), List.copyOf(entries));
            persist();
        }
    }

    public static boolean delete(String name) {
        ensureLoaded();
        synchronized (LOCK) {
            boolean removed = kits.remove(normalise(name)) != null;
            if (removed) {
                persist();
            }
            return removed;
        }
    }

    /** Parses {@code id*count,id*count}; a missing count means one. */
    public static List<Entry> parse(String spec) {
        List<Entry> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String token : spec.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int star = trimmed.lastIndexOf('*');
            try {
                if (star < 0) {
                    out.add(new Entry(trimmed, 1));
                } else {
                    out.add(new Entry(trimmed.substring(0, star).trim(),
                            Math.max(1, Integer.parseInt(trimmed.substring(star + 1).trim()))));
                }
            } catch (NumberFormatException ignored) {
                // Skip a malformed entry rather than losing the whole kit.
            }
        }
        return out;
    }

    public static String format(List<Entry> entries) {
        List<String> parts = new ArrayList<>();
        for (Entry e : entries) {
            parts.add(e.itemId() + "*" + e.count());
        }
        return String.join(",", parts);
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (kits != null) {
                return;
            }
            kits = new LinkedHashMap<>();
            Path path = file();
            if (!Files.exists(path)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t");
                    if (parts.length == 2) {
                        kits.put(normalise(parts[0]), parse(parts[1]));
                    }
                }
            } catch (IOException e) {
                MCControler.LOGGER.error("[MC Controler] could not read kits.tsv", e);
            }
        }
    }

    private static void persist() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Entry>> kit : kits.entrySet()) {
            sb.append(kit.getKey()).append('\t').append(format(kit.getValue())).append('\n');
        }
        try {
            Path path = file();
            Files.createDirectories(Objects.requireNonNull(path.getParent()));
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MCControler.LOGGER.error("[MC Controler] could not save kits.tsv", e);
        }
    }
}
