package com.mccontroler.job;

import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.inv.InventoryHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A snapshot of everything worth knowing when a job fails.
 *
 * <p>Deliberately captured only on failure rather than logged continuously. Every problem in this
 * mod so far has come down to the same handful of questions — where was the bot, what did it
 * think it was doing, was Baritone actually working, and could it reach or break anything — so
 * this answers exactly those instead of dumping everything.
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /** Builds the snapshot for a failed job. Client thread only. */
    public static List<String> snapshot(Job job, String error) {
        return snapshot(job.title(), error);
    }

    /**
     * Saves a screenshot of whatever the bot is looking at.
     *
     * <p>Text says the bot was stuck; a picture says it was walled into a one-block hole facing a
     * wall. Several bugs in this project would have been obvious at a glance. Must run on the
     * client thread, which is also the render thread.
     */
    public static void captureScreenshot() {
        try {
            Minecraft mc = Minecraft.getInstance();
            // Writes a timestamped PNG into run/screenshots; the panel serves the newest one.
            net.minecraft.client.Screenshot.grab(mc, false);
        } catch (Throwable t) {
            // Never let diagnostics turn a job failure into a crash.
            com.mccontroler.MCControler.LOGGER.warn(
                    "[MC Controler] could not take a screenshot: {}", String.valueOf(t));
        }
    }

    /** The newest screenshot on disk, or null. */
    public static java.io.File latestScreenshot() {
        java.io.File dir = new java.io.File(
                Minecraft.getInstance().gameDirectory, "screenshots");
        java.io.File[] shots = dir.listFiles((d, name) -> name.endsWith(".png"));
        if (shots == null || shots.length == 0) {
            return null;
        }
        java.io.File newest = shots[0];
        for (java.io.File candidate : shots) {
            if (candidate.lastModified() > newest.lastModified()) {
                newest = candidate;
            }
        }
        return newest;
    }

    /** Builds a snapshot on demand, for a job that is stuck rather than failed. */
    public static List<String> snapshot(String jobTitle, String error) {
        List<String> out = new ArrayList<>();
        out.add("job:      " + jobTitle);
        out.add("error:    " + error);

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (player == null || level == null) {
            out.add("world:    not in a world");
            return out;
        }

        BlockPos pos = player.blockPosition();
        out.add("position: " + pos.toShortString()
                + " in " + level.dimension().identifier()
                + " (hp " + Math.round(player.getHealth())
                + ", food " + player.getFoodData().getFoodLevel() + ")");
        out.add("onGround: " + player.onGround()
                + ", inWater: " + player.isInWater()
                + ", inLava: " + player.isInLava());

        // What the bot is standing in and on — "sealed in a hole" and "swimming" look identical
        // from a position alone, and both stop a job dead.
        out.add("standing: feet=" + blockAt(level, pos)
                + ", head=" + blockAt(level, pos.above())
                + ", below=" + blockAt(level, pos.below()));

        try {
            out.add("baritone: " + BaritoneBridge.describeState());
        } catch (Throwable t) {
            out.add("baritone: unavailable (" + t.getClass().getSimpleName() + ")");
        }

        out.add("light:    " + level.getMaxLocalRawBrightness(pos)
                + ", canSeeSky=" + level.canSeeSky(pos));

        out.add("tools:    " + describeTools(player));

        int free = InventoryHelper.freeSlots();
        out.add("inventory: " + free + " free slots of 36"
                + (free <= 2 ? "  <-- nearly full, drops may be lost" : ""));

        String held = player.getMainHandItem().isEmpty()
                ? "(empty hand)"
                : player.getMainHandItem().getHoverName().getString();
        out.add("holding:  " + held);

        return out;
    }

    private static String blockAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT)
                .replace(' ', '_');
    }

    /**
     * Best tool of each kind carried. Missing tools are the single most common reason a job
     * stalls with a valid path — Baritone treats unbreakable blocks as infinite cost.
     */
    private static String describeTools(LocalPlayer player) {
        Map<String, String> best = new LinkedHashMap<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = stack.getItem().toString();
            // Shears are listed too: plenty of blocks yield nothing without them, and their
            // absence explains a job that appears to be destroying things for no reason.
            for (String kind : new String[]{"pickaxe", "axe", "shovel", "hoe", "sword", "shears"}) {
                // "pickaxe" also ends in "axe", so match the more specific kind first.
                if (id.endsWith(kind) && !(kind.equals("axe") && id.endsWith("pickaxe"))) {
                    best.putIfAbsent(kind, stack.getHoverName().getString());
                }
            }
        }
        return best.isEmpty() ? "NONE — this stops most mining and digging" : best.toString();
    }
}
