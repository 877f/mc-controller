package com.mccontroler.job;

import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.web.EventStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Walks the bot to a position — a saved waypoint, or straight up to the surface.
 *
 * <p>Baritone does the pathing; this job owns arrival detection and the stall guard, because
 * a pathing process that has given up looks the same from outside as one still working.
 */
public final class TravelJob implements Job {

    /** Close enough to count as arrived; demanding the exact block invites oscillation. */
    private static final double ARRIVAL_DISTANCE = 2.5;

    private static final int STALL_LIMIT = 20 * 45;

    private final BlockPos target;
    private final String label;
    private final boolean toSurface;

    private BlockPos resolved;
    private double startDistance = -1;
    private BlockPos lastPos;
    private int stalledTicks;
    private boolean started;
    private String error = "";

    private TravelJob(BlockPos target, String label, boolean toSurface) {
        this.target = target;
        this.label = label;
        this.toSurface = toSurface;
    }

    /** Travels to a fixed position. */
    public static TravelJob to(BlockPos pos, String label) {
        return new TravelJob(pos, label, false);
    }

    /**
     * Travels to open sky above the bot's current column. Resolved at start rather than at
     * construction, so the height is read when the job actually runs.
     */
    public static TravelJob toSurface() {
        return new TravelJob(null, "the surface", true);
    }

    @Override
    public String title() {
        return "Travelling to " + label;
    }

    @Override
    public float progress() {
        if (!started || startDistance <= 0 || resolved == null) {
            return -1f;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return -1f;
        }
        double left = Math.sqrt(player.distanceToSqr(
                resolved.getX() + 0.5, resolved.getY(), resolved.getZ() + 0.5));
        return (float) Math.min(1.0, Math.max(0.0, 1.0 - left / startDistance));
    }

    @Override
    public Job.State tick() {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.level == null) {
            error = "not in a world";
            return Job.State.FAILED;
        }

        if (!started) {
            if (toSurface) {
                BlockPos here = player.blockPosition();
                // The highest non-air block in this column, then stand on top of it.
                int surfaceY = mc.level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, here.getX(), here.getZ());
                resolved = new BlockPos(here.getX(), surfaceY, here.getZ());
                if (here.getY() >= surfaceY - 1) {
                    EventStream.log("already at the surface", "ok");
                    return Job.State.DONE;
                }
            } else {
                resolved = target;
            }

            startDistance = Math.sqrt(player.distanceToSqr(
                    resolved.getX() + 0.5, resolved.getY(), resolved.getZ() + 0.5));
            BaritoneBridge.travelTo(resolved);
            EventStream.log("heading to " + resolved.toShortString()
                    + " (" + Math.round(startDistance) + " blocks)");
            started = true;
            return Job.State.RUNNING;
        }

        double left = Math.sqrt(player.distanceToSqr(
                resolved.getX() + 0.5, resolved.getY(), resolved.getZ() + 0.5));
        if (left <= ARRIVAL_DISTANCE) {
            BaritoneBridge.stop();
            EventStream.log("arrived at " + label, "ok");
            return Job.State.DONE;
        }

        if (!BaritoneBridge.isTravelling()) {
            error = "Baritone stopped " + Math.round(left) + " blocks short of " + label
                    + " — it may not be reachable from here";
            return Job.State.FAILED;
        }

        BlockPos now = player.blockPosition();
        if (!now.equals(lastPos)) {
            lastPos = now;
            stalledTicks = 0;
        } else if (++stalledTicks > STALL_LIMIT) {
            BaritoneBridge.stop();
            error = "stuck for 45s, still " + Math.round(left) + " blocks from " + label;
            return Job.State.FAILED;
        }

        return Job.State.RUNNING;
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
