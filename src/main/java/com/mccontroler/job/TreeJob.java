package com.mccontroler.job;

import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.inv.BlockPlacer;
import com.mccontroler.inv.InventoryHelper;
import com.mccontroler.web.EventStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * Fells trees, in one of two modes.
 *
 * <p><b>Count</b> gathers a target number of logs and stops. <b>Loop</b> keeps felling and
 * replants as it goes, so the same patch of forest stays productive.
 *
 * <p>Replanting is opportunistic: while Baritone works the area the bot is constantly standing
 * next to fresh stumps, so each tick we look for a bare, plantable spot within arm's reach and
 * drop a sapling on it. That avoids taking pathing control away from the mining process.
 */
public final class TreeJob implements Job {

    public enum Mode {
        /** Fell until the target log count is reached. */
        COUNT,
        /** Fell and replant indefinitely, until stopped from the panel. */
        LOOP
    }

    /** Logs whose sapling is not simply "<wood>_sapling". */
    private static final Map<String, String> SPECIAL_SAPLINGS = Map.of(
            "minecraft:mangrove_log", "minecraft:mangrove_propagule",
            "minecraft:crimson_stem", "minecraft:crimson_fungus",
            "minecraft:warped_stem", "minecraft:warped_fungus");

    /** Replanting every tick would spam placement packets; once every half second is plenty. */
    private static final int REPLANT_INTERVAL = 10;

    private final String logId;
    private final Mode mode;
    private final int target;

    private Item logItem;
    private Item saplingItem;
    private String displayName;
    private int startCount;
    private int lastCount;
    private int stalledTicks;
    private int tickCounter;
    private int replanted;
    private int stuckTicks;
    private BlockPos lastPos;
    private boolean started;
    private String error = "";

    public TreeJob(String logId, Mode mode, int target) {
        this.logId = logId;
        this.mode = mode;
        this.target = target;
    }

    @Override
    public String title() {
        String what = displayName == null ? logId : displayName;
        if (target > 0) {
            return "Felling " + target + " × " + what
                    + (mode == Mode.LOOP ? " (" + replanted + " replanted)" : "");
        }
        return "Tree farm: " + what + " (" + replanted + " replanted)";
    }

    @Override
    public float progress() {
        if (!started || target <= 0) {
            return -1f;   // open-ended: no meaningful progress to report
        }
        return Math.min(1f, (float) (lastCount - startCount) / target);
    }

    @Override
    public Job.State tick() {
        if (!started) {
            return begin();
        }

        int have = InventoryHelper.count(logItem);
        if (have != lastCount) {
            lastCount = have;
            stalledTicks = 0;
            if (mode == Mode.COUNT) {
                EventStream.log(displayName + ": " + have + "/" + target);
            }
        } else {
            stalledTicks++;
        }

        // A target stops the job in either mode. Loop mode with target 0 runs until stopped,
        // which is the "keep the grove producing" case; with a target it fells and replants
        // until that many logs have been collected.
        if (target > 0 && have - startCount >= target) {
            BaritoneBridge.stop();
            EventStream.log("collected " + (have - startCount) + " × " + displayName
                    + (replanted > 0 ? ", replanted " + replanted : ""), "ok");
            return Job.State.DONE;
        }

        if (saplingItem != null && ++tickCounter % REPLANT_INTERVAL == 0) {
            tryReplant();
        }

        if (!BaritoneBridge.isMining()) {
            if (mode == Mode.COUNT) {
                error = "ran out of reachable " + displayName + " at " + have + "/" + target;
                return Job.State.FAILED;
            }
            // In loop mode, exhausting the local trees is normal; ask for another sweep.
            EventStream.log("no trees in range — rescanning");
            BaritoneBridge.mine(0, logId);
        }

        if (mode == Mode.COUNT && stalledTicks > 20 * 45) {
            BaritoneBridge.stop();
            error = "no logs gathered for 45s (" + (have - startCount) + "/" + target + ")";
            return Job.State.FAILED;
        }

        // Loop mode runs unattended, so it does not time out on a slow patch of forest. It does
        // have to notice being genuinely stuck, though: Baritone reports the mine process as
        // active even when it has no reachable target, which is indistinguishable from working.
        if (mode == Mode.LOOP) {
            var player = Minecraft.getInstance().player;
            BlockPos now = player == null ? null : player.blockPosition();
            if (now != null && !now.equals(lastPos)) {
                lastPos = now;
                stuckTicks = 0;
            } else if (++stuckTicks > 20 * 90) {
                stuckTicks = 0;
                EventStream.log("stuck for 90s with no logs — no reachable "
                        + displayName + " from here; move the bot or stop the job", "err");
            }
        }

        return Job.State.RUNNING;
    }

    private Job.State begin() {
        logItem = InventoryHelper.resolve(logId);
        if (logItem == null) {
            error = "unknown log type: " + logId;
            return Job.State.FAILED;
        }
        displayName = Component.translatable(logItem.getDescriptionId()).getString();

        String saplingId = SPECIAL_SAPLINGS.getOrDefault(logId,
                logId.replace("_log", "_sapling").replace("_stem", "_sapling"));
        saplingItem = InventoryHelper.resolve(saplingId);
        if (mode == Mode.LOOP && saplingItem == null) {
            EventStream.log("no sapling known for " + displayName + " — felling without replanting");
        }

        startCount = InventoryHelper.count(logItem);
        lastCount = startCount;

        // Targets are relative: "64 logs" means collect 64 more, not "end up holding 64".
        // Baritone gets 0 for an open-ended run, which is its "keep going" value.
        BaritoneBridge.mine(Math.max(0, target), logId);
        EventStream.log(target > 0
                ? "felling until " + target + " × " + displayName + " collected"
                + (mode == Mode.LOOP ? ", replanting as it goes" : "")
                : "tree farm started on " + displayName + " — runs until stopped");
        started = true;
        return Job.State.RUNNING;
    }

    /** Drops a sapling on the first plantable spot within reach. */
    private void tryReplant() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (InventoryHelper.count(saplingItem) == 0 || !BlockPlacer.inHotbar(saplingItem)) {
            return;
        }

        BlockPos origin = mc.player.blockPosition();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (isPlantable(mc.level, pos) && BlockPlacer.tryPlace(pos, saplingItem)) {
                        replanted++;
                        EventStream.log("replanted at " + pos.toShortString());
                        return;
                    }
                }
            }
        }
    }

    /** Air with dirt-like ground beneath and open sky-ish space above. */
    private static boolean isPlantable(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        if (!level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        BlockState ground = level.getBlockState(pos.below());
        return ground.is(Blocks.GRASS_BLOCK)
                || ground.is(Blocks.DIRT)
                || ground.is(Blocks.COARSE_DIRT)
                || ground.is(Blocks.PODZOL)
                || ground.is(Blocks.ROOTED_DIRT)
                || ground.is(Blocks.MOSS_BLOCK)
                || ground.is(Blocks.MUD);
    }

    @Override
    public void resume() {
        if (started) {
            BaritoneBridge.mine(Math.max(0, target), logId);
        }
    }

    @Override
    public void cancel() {
        if (started) {
            BaritoneBridge.stop();
        }
    }

    @Override
    public String error() {
        return error;
    }
}
