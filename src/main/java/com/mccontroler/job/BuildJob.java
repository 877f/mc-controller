package com.mccontroler.job;

import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.ShellSchematic;
import baritone.api.schematic.WallsSchematic;
import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.inv.InventoryHelper;
import com.mccontroler.web.EventStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Builds a box out of one block: solid, hollow, or walls only.
 *
 * <p>Covers most of what people actually build by hand — floors, platforms, walls, pillars and
 * rooms are all boxes, a floor simply being a box one block tall. Baritone supplies the
 * schematic shapes, so this job is really about material, lifecycle and progress reporting.
 */
public final class BuildJob implements Job {

    /** Refuse builds past this size, the same guard the excavation job uses. */
    private static final long MAX_BLOCKS = 100_000;

    private static final int STALL_LIMIT = 20 * 45;

    public enum Shape {
        /** Every block in the box. */
        FILL,
        /** Just the outer shell: floor, ceiling and walls. */
        HOLLOW,
        /** The four vertical walls, open above and below. */
        WALLS
    }

    private final BlockPos cornerA;
    private final BlockPos cornerB;
    private final String blockId;
    private final Shape shape;

    private String displayName;
    private int needed;
    private boolean started;
    private int idleTicks;
    private int stalledTicks;
    private BlockPos lastPos;
    private String error = "";

    public BuildJob(BlockPos a, BlockPos b, String blockId, Shape shape) {
        this.cornerA = a;
        this.cornerB = b;
        this.blockId = blockId;
        this.shape = shape;
    }

    @Override
    public String title() {
        return "Building " + shape.name().toLowerCase(java.util.Locale.ROOT)
                + " out of " + (displayName == null ? blockId : displayName);
    }

    @Override
    public float progress() {
        // Baritone does not report how much of a build remains.
        return -1f;
    }

    @Override
    public Job.State tick() {
        if (!started) {
            return begin();
        }

        if (BaritoneBridge.isBuilding()) {
            idleTicks = 0;
            return checkStalled();
        }
        // The builder drops out briefly between layers, so require a run of idle ticks.
        if (++idleTicks > 40) {
            return Job.State.DONE;
        }
        return Job.State.RUNNING;
    }

    private Job.State begin() {
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId)).orElse(null);
        if (block == null) {
            error = "unknown block: " + blockId;
            return Job.State.FAILED;
        }
        displayName = block.getName().getString();

        int dx = Math.abs(cornerA.getX() - cornerB.getX()) + 1;
        int dy = Math.abs(cornerA.getY() - cornerB.getY()) + 1;
        int dz = Math.abs(cornerA.getZ() - cornerB.getZ()) + 1;
        long volume = (long) dx * dy * dz;
        if (volume > MAX_BLOCKS) {
            error = "region is " + volume + " blocks, over the " + MAX_BLOCKS + " limit";
            return Job.State.FAILED;
        }

        needed = estimate(dx, dy, dz);

        // Fetch the material first if there is not enough. Requeueing this job behind the gather
        // means the build resumes automatically once the blocks are in hand.
        Item item = InventoryHelper.resolve(blockId);
        int have = item == null ? 0 : InventoryHelper.count(item);
        if (have < needed) {
            EventStream.log("need about " + needed + " × " + displayName
                    + " and have " + have + " — gathering first", "ok");
            JobManager.get().submitFront(new BuildJob(cornerA, cornerB, blockId, shape));
            JobManager.get().submitFront(new AcquireJob(blockId, needed - have));
            return Job.State.DONE;
        }

        BlockPos origin = new BlockPos(
                Math.min(cornerA.getX(), cornerB.getX()),
                Math.min(cornerA.getY(), cornerB.getY()),
                Math.min(cornerA.getZ(), cornerB.getZ()));

        ISchematic schematic = new FillSchematic(dx, dy, dz, block.defaultBlockState());
        schematic = switch (shape) {
            case HOLLOW -> new ShellSchematic(schematic);
            case WALLS -> new WallsSchematic(schematic);
            case FILL -> schematic;
        };

        BaritoneBridge.select(cornerA, cornerB);
        BaritoneBridge.buildSchematic(title(), schematic, origin);
        EventStream.log("building " + dx + "×" + dy + "×" + dz + " from "
                + origin.toShortString() + " (about " + needed + " blocks)");
        started = true;
        return Job.State.RUNNING;
    }

    /** Roughly how many blocks the shape consumes, used to decide whether to gather first. */
    private int estimate(int dx, int dy, int dz) {
        return switch (shape) {
            case FILL -> dx * dy * dz;
            case HOLLOW -> dx * dy * dz
                    - Math.max(0, dx - 2) * Math.max(0, dy - 2) * Math.max(0, dz - 2);
            case WALLS -> Math.max(0, 2 * dx + 2 * dz - 4) * dy;
        };
    }

    /**
     * Fails when the builder claims to be active but nothing is happening — usually missing
     * material or a region it cannot reach.
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
            error = "no progress for 45s — probably out of " + displayName
                    + ", or the area cannot be reached";
            return Job.State.FAILED;
        }
        return Job.State.RUNNING;
    }

    @Override
    public void resume() {
        // Rebuilding the schematic from the same corners is cheap and avoids holding a second
        // copy of it just for this.
        if (started) {
            started = false;
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
