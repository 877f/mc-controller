package com.mccontroler.bot;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.io.File;

/**
 * Everything this mod asks of Baritone, in one place.
 *
 * <p>Keeping the Baritone surface behind a single class means a breaking change upstream shows
 * up here and nowhere else — worth doing, since we build Baritone from a moving branch.
 */
public final class BaritoneBridge {

    private BaritoneBridge() {
    }

    private static IBaritone bot() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** Applies the defaults this mod relies on. Called once after the client is in a world. */
    public static void applyDefaults() {
        var s = BaritoneAPI.getSettings();

        // The bot has to be able to break and place to mine, bridge and build.
        s.allowBreak.value = true;
        s.allowPlace.value = true;
        s.allowSprint.value = true;

        // Let it pick tools and blocks out of the inventory rather than only the hotbar.
        s.allowInventory.value = true;

        // Picking up what it mines is the whole point of a gather job.
        s.mineScanDroppedItems.value = true;

        // This mod is the control surface, so Baritone's own chat commands stay off to avoid
        // two things fighting over the same processes.
        s.chatControl.value = false;

        // Parkour is where Baritone most often gets itself killed on a long unattended run.
        s.allowParkour.value = false;

        // Don't go exploring for a block that isn't nearby. Left on, a request for four ferns
        // sent the bot 190 blocks away and 125 blocks down to bedrock before giving up. Failing
        // fast with "none reachable from here" is far more useful: the player can move the bot
        // somewhere sensible, and nothing is wrecked in the meantime.
        s.exploreForBlocks.value = false;

        applyPathStability(s);
    }

    /**
     * Stops the bot re-deriving its plan constantly while mining.
     *
     * <p>Out of the box Baritone rescans for a mining goal every 5 ticks — four times a second —
     * and repacks a whole chunk every time a single block changes. Mining changes blocks
     * continuously, so the two feed each other and the bot visibly dithers, recalculating instead
     * of committing to the route it already has. This is most obvious when it has to dig down to
     * reach a target.
     *
     * <p>The trade-off is deliberate: it now takes up to two seconds to notice a closer target,
     * in exchange for actually following the path it picked.
     */
    private static void applyPathStability(baritone.api.Settings s) {
        // Re-evaluate the mining goal every 2s instead of every 0.25s.
        s.mineGoalUpdateInterval.value = 40;

        // Don't repack the entire chunk on every block change — while mining that is constant.
        s.repackOnAnyBlockChange.value = false;

        // Prefer continuing along the existing path over marginally cheaper alternatives.
        s.backtrackCostFavoringCoefficient.value = 0.8;

        // Give path calculation longer before it gives up and retries, so a hard dig-down is
        // solved once rather than attempted repeatedly.
        s.primaryTimeoutMS.value = 2000L;
        s.failureTimeoutMS.value = 5000L;
    }

    /**
     * Mines until {@code quantity} items have been collected.
     *
     * @param quantity total items wanted; 0 means "keep going until stopped"
     * @param blockIds registry ids, e.g. {@code minecraft:oak_log}
     */
    public static void mine(int quantity, String... blockIds) {
        bot().getMineProcess().mineByName(quantity, blockIds);
    }

    /** Removes every block in the box defined by two opposite corners. */
    public static void clearArea(BlockPos a, BlockPos b) {
        bot().getBuilderProcess().clearArea(a, b);
    }

    /** Walks to an exact block position, digging or bridging as needed. */
    public static void travelTo(BlockPos pos) {
        bot().getCustomGoalProcess().setGoalAndPath(
                new GoalBlock(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * Walks to a column, letting Baritone settle on whatever height is reachable.
     * Used for "go to the surface", where the exact Y matters less than getting out.
     */
    public static void travelToColumn(int x, int z) {
        bot().getCustomGoalProcess().setGoalAndPath(new GoalXZ(x, z));
    }

    /** Climbs to a target Y without caring about X/Z. */
    public static void travelToHeight(int y) {
        bot().getCustomGoalProcess().setGoalAndPath(new GoalYLevel(y));
    }

    /** True while a travel goal is being pursued. */
    public static boolean isTravelling() {
        return bot().getCustomGoalProcess().isActive()
                || bot().getPathingBehavior().isPathing();
    }

    /** Builds a schematic file. Returns false when the file could not be parsed. */
    public static boolean build(String name, File schematic, Vec3i origin) {
        return bot().getBuilderProcess().build(name, schematic, origin);
    }

    /** Builds a schematic built in memory, such as the box shapes from the Build tab. */
    public static void buildSchematic(String name, ISchematic schematic, Vec3i origin) {
        bot().getBuilderProcess().build(name, schematic, origin);
    }

    /** Highlights a region; the panel uses this so the player can see what was selected. */
    public static void select(BlockPos a, BlockPos b) {
        var sel = bot().getSelectionManager();
        sel.removeAllSelections();
        sel.addSelection(new BetterBlockPos(a), new BetterBlockPos(b));
    }

    public static void clearSelections() {
        bot().getSelectionManager().removeAllSelections();
    }

    /** True while any Baritone process still has work to do. */
    public static boolean isBusy() {
        IBaritone b = bot();
        return b.getMineProcess().isActive()
                || b.getBuilderProcess().isActive()
                || b.getCustomGoalProcess().isActive()
                || b.getGetToBlockProcess().isActive()
                || b.getPathingBehavior().isPathing();
    }

    /** True specifically while a mining task is running. */
    public static boolean isMining() {
        return bot().getMineProcess().isActive();
    }

    /** True specifically while a build or area clear is running. */
    public static boolean isBuilding() {
        return bot().getBuilderProcess().isActive();
    }

    /**
     * Which Baritone processes currently want control, for failure diagnostics.
     * "mine" being active while nothing moves is a very different problem from nothing active.
     */
    public static String describeState() {
        IBaritone b = bot();
        StringBuilder sb = new StringBuilder();
        appendIf(sb, "mine", b.getMineProcess().isActive());
        appendIf(sb, "build", b.getBuilderProcess().isActive());
        appendIf(sb, "customGoal", b.getCustomGoalProcess().isActive());
        appendIf(sb, "getToBlock", b.getGetToBlockProcess().isActive());
        appendIf(sb, "pathing", b.getPathingBehavior().isPathing());
        if (sb.isEmpty()) {
            sb.append("idle (no process wants control)");
        }
        var goal = b.getCustomGoalProcess().getGoal();
        if (goal != null) {
            sb.append(", goal=").append(goal);
        }
        b.getPathingBehavior().estimatedTicksToGoal()
                .ifPresent(ticks -> sb.append(", etaTicks=").append(Math.round(ticks)));
        return sb.toString();
    }

    private static void appendIf(StringBuilder sb, String label, boolean active) {
        if (active) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(label);
        }
    }

    /** Cancels everything Baritone is doing and drops any selection. */
    public static void stop() {
        IBaritone b = bot();
        b.getMineProcess().cancel();
        b.getBuilderProcess().onLostControl();
        b.getCustomGoalProcess().onLostControl();
        b.getGetToBlockProcess().onLostControl();
        b.getPathingBehavior().cancelEverything();
        b.getSelectionManager().removeAllSelections();
    }
}
