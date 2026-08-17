package com.mccontroler.web;

import com.mccontroler.GameThread;
import com.mccontroler.MCControler;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves item textures to the block picker.
 *
 * <p>Reads the PNG straight out of the loaded resource packs rather than rendering each item to
 * an offscreen buffer. Rendering would give a true inventory icon — a proper 3D cube for blocks —
 * but 26.2 rewrote the GPU layer, and a texture lookup gets a recognisable picture for well over
 * a thousand items with no render-thread work at all. Resource packs and modded items come along
 * for free, since the namespace is preserved.
 *
 * <p>The trade-off: blocks show one face rather than an angled cube, and a handful of items whose
 * texture is named unusually resolve to nothing. Those simply 404 and the panel hides them.
 */
public final class Icons {

    /**
     * Where a texture for {@code <path>} might live, in order of preference. Most items are
     * textures/item/<path>.png; blocks are textures/block/<path>.png, with the common
     * multi-face naming conventions after that.
     */
    private static final List<String> CANDIDATES = List.of(
            "textures/item/%s.png",
            "textures/block/%s.png",
            "textures/block/%s_side.png",
            "textures/block/%s_front.png",
            "textures/block/%s_top.png",
            "textures/item/%s_00.png",
            "textures/block/%s_still.png");

    /**
     * Items drawn from an entity model rather than a block texture, so none of the usual paths
     * find them. Chests, shulker boxes and beds are the ones people actually pick from the grid.
     */
    private static final Map<String, String> SPECIAL = Map.of(
            "chest", "textures/entity/chest/normal.png",
            "trapped_chest", "textures/entity/chest/trapped.png",
            "ender_chest", "textures/entity/chest/ender.png",
            "shulker_box", "textures/entity/shulker/shulker.png");

    /** Textures never change at runtime outside a resource reload, so results are cached. */
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    /** Marker for "looked and found nothing", so misses are not retried on every scroll. */
    private static final byte[] MISSING = new byte[0];

    private Icons() {
    }

    /**
     * PNG bytes for an item id, or null when no texture matches.
     *
     * @param namespace e.g. {@code minecraft}
     * @param path      e.g. {@code oak_log}
     */
    public static byte[] get(String namespace, String path) {
        String key = namespace + ":" + path;
        byte[] cached = CACHE.get(key);
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }

        byte[] found = GameThread.get(() -> load(namespace, path));
        CACHE.put(key, found == null ? MISSING : found);
        return found;
    }

    /** Suffixes of blocks that borrow their base block's texture. */
    private static final List<String> DERIVED_SUFFIXES = List.of(
            "_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_button",
            "_pressure_plate", "_trapdoor", "_door");

    /**
     * Names to try for a derived block: {@code red_sandstone_stairs} borrows
     * {@code red_sandstone}, {@code dark_oak_button} borrows {@code dark_oak_planks}, and
     * {@code nether_brick_fence} borrows {@code nether_bricks} — hence the plural attempt.
     */
    private static List<String> derivedNames(String path) {
        List<String> out = new java.util.ArrayList<>();
        String base = path;

        // "waxed_" is a finish, not a texture of its own.
        if (base.startsWith("waxed_")) {
            out.add(base.substring("waxed_".length()));
            base = base.substring("waxed_".length());
        }

        for (String suffix : DERIVED_SUFFIXES) {
            if (base.endsWith(suffix)) {
                String stem = base.substring(0, base.length() - suffix.length());
                out.add(stem);              // red_sandstone_stairs -> red_sandstone
                out.add(stem + "s");        // nether_brick_fence   -> nether_bricks
                out.add(stem + "_planks");  // dark_oak_button      -> dark_oak_planks
                break;
            }
        }

        // A "wood" block is a log on every face, and shares its texture.
        if (base.endsWith("_wood")) {
            out.add(base.substring(0, base.length() - "_wood".length()) + "_log");
        }
        // Carpet is dyed wool.
        if (base.endsWith("_carpet")) {
            out.add(base.substring(0, base.length() - "_carpet".length()) + "_wool");
        }
        // magma_block -> magma, honeycomb_block -> honeycomb, and friends.
        if (base.endsWith("_block")) {
            out.add(base.substring(0, base.length() - "_block".length()));
        }
        // Infested variants are ordinary stone to look at.
        if (base.startsWith("infested_")) {
            out.add(base.substring("infested_".length()));
        }
        return out;
    }

    /** Client thread: the resource manager is not safe to touch from an HTTP worker. */
    private static byte[] load(String namespace, String path) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getResourceManager() == null) {
            return null;
        }

        byte[] direct = loadExact(mc, namespace, path);
        if (direct != null) {
            return direct;
        }
        // Nothing under its own name: fall back to whatever it is made of.
        for (String alias : derivedNames(path)) {
            byte[] borrowed = loadExact(mc, namespace, alias);
            if (borrowed != null) {
                return borrowed;
            }
        }
        return null;
    }

    private static byte[] loadExact(Minecraft mc, String namespace, String path) {

        java.util.List<String> patterns = new java.util.ArrayList<>(CANDIDATES);
        String special = SPECIAL.get(path);
        if (special != null) {
            patterns.add(0, special);
        }

        for (String pattern : patterns) {
            Identifier id;
            try {
                // Special paths are already complete; the rest are format templates.
                String texture = pattern.contains("%s") ? String.format(pattern, path) : pattern;
                id = Identifier.fromNamespaceAndPath(namespace, texture);
            } catch (Exception malformed) {
                continue;
            }

            // The stack runs lowest priority first, so the last entry wins — that is what makes
            // an installed resource pack override the vanilla texture.
            List<Resource> stack = mc.getResourceManager().getResourceStack(id);
            if (stack.isEmpty()) {
                continue;
            }
            try (InputStream in = stack.get(stack.size() - 1).open()) {
                return in.readAllBytes();
            } catch (IOException e) {
                MCControler.LOGGER.warn("[MC Controler] could not read texture {}", id);
            }
        }
        return null;
    }

    /** Drops the cache, for a resource reload. */
    public static void clear() {
        CACHE.clear();
    }
}
