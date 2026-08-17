package com.mccontroler.job;

import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.web.EventStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Removes every block inside an axis-aligned box.
 *
 * <p>Baritone's builder process does the digging; this job owns the lifecycle, the safety
 * limit and the progress reporting.
 */
public final class ExcavateJob implements Job {

    /**
     * Refuse boxes past this size. A careless drag in the panel can otherwise queue millions of
     * blocks and leave the bot digging for days.
     */
    private static final long MAX_BLOCKS = 500_000;

    private final BlockPos cornerA;
    private final BlockPos cornerB;
    private final long volume;

    /**
     * Baritone's builder reports {@code isActive()} as "a schematic is set", so a builder that
     * cannot reach or break anything looks exactly like one that is working. Without this the
     * job sits at "running" forever. Position is the cheapest available progress signal.
     */
    private static final int STALL_LIMIT = 20 * 45;

    private boolean started;
    private int idleTicks;
    private int stalledTicks;
    private BlockPos lastPos;
    private String error = "";

    public ExcavateJob(BlockPos a, BlockPos b) {
        this.cornerA = a;
        this.cornerB = b;
        long dx = Math.abs(a.getX() - b.getX()) + 1L;
        long dy = Math.abs(a.getY() - b.getY()) + 1L;
        long dz = Math.abs(a.getZ() - b.getZ()) + 1L;
        this.volume = dx * dy * dz;
    }

    public long volume() {
        return volume;
    }

    @Override
    public String title() {
        return "Excavating " + volume + " blocks";
    }

    @Override
    public float progress() {
        // Baritone does not report how much of a clear-area remains, so this stays indeterminate.
        return -1f;
    }

    @Override
    public Job.State tick() {
        if (!started) {
            if (volume > MAX_BLOCKS) {
                error = "region is " + volume + " blocks, over the " + MAX_BLOCKS + " limit";
                return Job.State.FAILED;
            }
            BaritoneBridge.select(cornerA, cornerB);
            BaritoneBridge.clearArea(cornerA, cornerB);
            EventStream.log("clearing " + cornerA.toShortString() + " → " + cornerB.toShortString());
            started = true;
            return Job.State.RUNNING;
        }

        if (BaritoneBridge.isBuilding()) {
            idleTicks = 0;
            return checkStalled();
        }

        // The builder process drops out for a tick or two between layers, so require a run of
        // idle ticks before calling it finished.
        if (++idleTicks > 40) {
            BaritoneBridge.clearSelections();
            return Job.State.DONE;
        }
        return Job.State.RUNNING;
    }

    /**
     * Fails the job when the player has not moved for {@link #STALL_LIMIT} ticks while the
     * builder claims to be active — usually "cannot break these blocks with what I am holding"
     * or "cannot path to the region from here".
     */
    private Job.State checkStalled() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            error = "left the world";
            return Job.State.FAILED;
        }

        BlockPos now = player.blockPosition();
        if (!now.equals(lastPos)) {
            lastPos = now;
            stalledTicks = 0;
            return Job.State.RUNNING;
        }

        if (++stalledTicks > STALL_LIMIT) {
            BaritoneBridge.stop();
            error = "no progress for 45s — the bot may lack a tool for these blocks,"
                    + " or cannot reach the region from where it is standing";
            return Job.State.FAILED;
        }
        return Job.State.RUNNING;
    }

    @Override
    public void resume() {
        if (started) {
            BaritoneBridge.clearArea(cornerA, cornerB);
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
