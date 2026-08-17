package com.mccontroler;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * On/off switches for the things the mod does on its own.
 *
 * <p>Anything that acts without being asked needs a way to turn it off. Auto-eat in particular
 * takes over the hotbar, so when it misbehaves it fights the player for control of their own
 * character — there has to be a switch.
 *
 * <p>Stored as {@code name<TAB>true|false} next to the save.
 */
public final class BotSettings {

    /** Defaults for every toggle. Also defines which names are valid. */
    private static final Map<String, Boolean> DEFAULTS = Map.of(
            "autoEat", true,
            "autoTorch", true,
            "autoRespawn", true);

    private static final Object LOCK = new Object();
    private static Map<String, Boolean> values;

    private BotSettings() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("mccontroler").resolve("settings.tsv");
    }

    public static boolean get(String name) {
        ensureLoaded();
        synchronized (LOCK) {
            return values.getOrDefault(name, DEFAULTS.getOrDefault(name, false));
        }
    }

    public static Map<String, Boolean> all() {
        ensureLoaded();
        synchronized (LOCK) {
            return new LinkedHashMap<>(values);
        }
    }

    public static void set(String name, boolean on) {
        if (!DEFAULTS.containsKey(name)) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded();
            values.put(name, on);
            persist();
        }
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (values != null) {
                return;
            }
            values = new LinkedHashMap<>(DEFAULTS);

            Path path = file();
            if (!Files.exists(path)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t");
                    if (parts.length == 2 && DEFAULTS.containsKey(parts[0])) {
                        values.put(parts[0], Boolean.parseBoolean(parts[1]));
                    }
                }
            } catch (IOException e) {
                MCControler.LOGGER.error("[MC Controler] could not read settings.tsv", e);
            }
        }
    }

    private static void persist() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            sb.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }
        try {
            Path path = file();
            Files.createDirectories(Objects.requireNonNull(path.getParent()));
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MCControler.LOGGER.error("[MC Controler] could not save settings.tsv", e);
        }
    }
}
