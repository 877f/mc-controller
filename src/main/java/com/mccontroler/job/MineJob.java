package com.mccontroler.job;

import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.inv.HomeChest;
import com.mccontroler.inv.InventoryHelper;
import com.mccontroler.inv.ToolBelt;
import com.mccontroler.web.EventStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.List;

/**
 * Mines until a target count of one item has been collected.
 *
 * <p>Counts the item that ends up in the inventory, not the block being broken — iron ore yields
 * raw iron, and large ferns yield ferns — and accepts several target blocks at once so Baritone
 * can take whichever variant it reaches first.
 */
public final class MineJob implements Job {

    /**
     * Ticks with no progress before giving up.
     *
     * <p>Progress counts movement as well as items: walking to a target 180 blocks away takes
     * well over a minute with the item count legitimately stuck at zero.
     */
    private static final int STALL_LIMIT = 20 * 60;

    private final String itemId;
    private final String displayName;
    private final int wanted;
    private final List<String> targets;

    private Item item;
    private int startCount;
    private int lastCount;
    private BlockPos lastPos;
    private int stalledTicks;
    private boolean started;
    /** Guards against looping when fetching a replacement tool also fails. */
    private boolean replacedTool;
    /** Re-checks the held tool periodically, since one can break mid-job. */
    private int toolCheckTicks;
    private String error = "";

    public MineJob(String itemId, String displayName, int wanted, List<String> targets) {
        this(itemId, displayName, wanted, targets, false);
    }

    /**
     * @param replacedTool true when a replacement tool has already been fetched for this
     *                     request, so a second failure reports instead of looping
     */
    public MineJob(String itemId, String displayName, int wanted,
                   List<String> targets, boolean replacedTool) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.wanted = wanted;
        this.targets = targets;
        this.replacedTool = replacedTool;
    }

    @Override
    public String title() {
        return "Mining " + wanted + " × " + displayName;
    }

    @Override
    public float progress() {
        if (!started || item == null) {
            return -1f;
        }
        int gained = InventoryHelper.count(item) - startCount;
        return wanted <= 0 ? 1f : Math.min(1f, (float) gained / wanted);
    }

    @Override
    public Job.State tick() {
        if (!started) {
            return begin();
        }

        int have = InventoryHelper.count(item);
        if (have - startCount >= wanted) {
            BaritoneBridge.stop();
            return Job.State.DONE;
        }

        BlockPos now = Minecraft.getInstance().player == null
                ? null : Minecraft.getInstance().player.blockPosition();
        boolean moved = now != null && !now.equals(lastPos);
        if (have != lastCount) {
            EventStream.log(displayName + ": " + (have - startCount) + "/" + wanted);
        }
        if (have != lastCount || moved) {
            lastCount = have;
            lastPos = now;
            stalledTicks = 0;
        } else {
            stalledTicks++;
        }

        // A tool can snap partway through, at which point the bot keeps breaking blocks and
        // collecting nothing. Re-check once a second rather than only at the start.
        if (++toolCheckTicks >= 20) {
            toolCheckTicks = 0;
            Job.State toolCheck = ensureTool();
            if (toolCheck != null) {
                BaritoneBridge.stop();
                return toolCheck;
            }
        }

        // A full inventory silently stops collection, which otherwise looks like "none left".
        if (InventoryHelper.isFull()) {
            BaritoneBridge.stop();
            int remaining = wanted - (have - startCount);

            // With a home chest set this is a round trip, not a dead end: bank the load, then
            // pick the job back up where it left off.
            if (HomeChest.isSet()) {
                EventStream.log("inventory full — going to unload, then carrying on", "ok");
                JobManager.get().submitFront(new MineJob(itemId, displayName, remaining, targets));
                JobManager.get().submitFront(new DepositJob());
                return Job.State.DONE;
            }

            error = "inventory is full at " + (have - startCount) + "/" + wanted
                    + " " + displayName + " — set a home chest, or free some slots and rerun";
            return Job.State.FAILED;
        }

        if (!BaritoneBridge.isMining()) {
            error = "Baritone stopped with " + (have - startCount) + "/" + wanted
                    + " " + displayName + " — none reachable from here";
            return Job.State.FAILED;
        }

        if (stalledTicks > STALL_LIMIT) {
            BaritoneBridge.stop();
            error = "no movement and no items for 60s while mining " + displayName;
            return Job.State.FAILED;
        }

        return Job.State.RUNNING;
    }

    private Job.State begin() {
        item = InventoryHelper.resolve(itemId);
        if (item == null) {
            error = "unknown item: " + itemId;
            return Job.State.FAILED;
        }
        startCount = InventoryHelper.count(item);
        lastCount = startCount;

        Job.State toolCheck = ensureTool();
        if (toolCheck != null) {
            return toolCheck;
        }

        String[] blocks = targets.toArray(new String[0]);
        BaritoneBridge.mine(wanted, blocks);
        EventStream.log("mining " + String.join(" / ", blocks)
                + " for " + wanted + " × " + displayName);
        started = true;
        return Job.State.RUNNING;
    }

    /**
     * Makes sure a usable tool for these blocks is in hand.
     *
     * <p>Returns null to carry on, or a state to return. A tool that breaks mid-run is the
     * common case: the plan was built when one existed, so rather than failing the whole chain
     * this fetches a replacement and requeues the remaining mining behind it.
     *
     * <p>Nearly-spent tools count as missing, since planning around three remaining hits is how
     * a run gets stranded halfway.
     */
    private Job.State ensureTool() {
        // Shears are only a genuine requirement when the target block is being mined for
        // ITSELF. Leaves, cobweb and dead bush also yield a different item (apple/stick/sapling,
        // string, sticks) specifically when NOT holding shears — equipping shears while mining
        // them for that other item would suppress the drop this job is actually waiting on.
        boolean selfMine = targets.contains(itemId);
        String needed = ToolBelt.neededForAny(targets, selfMine);
        if (needed == null) {
            return null;   // hands are fine for these blocks
        }
        if (ToolBelt.hasSuitable(targets.get(0), selfMine)) {
            ToolBelt.equipFor(targets.get(0), selfMine);
            return null;
        }

        if (replacedTool) {
            error = "no usable " + needed.replace("minecraft:", "")
                    + " for " + displayName + ", and making one did not work";
            return Job.State.FAILED;
        }
        replacedTool = true;

        int remaining = wanted - (InventoryHelper.count(item) - startCount);
        EventStream.log("no usable " + needed.replace("minecraft:", "")
                + " — getting one before mining " + displayName, "ok");
        JobManager.get().submitFront(
                new MineJob(itemId, displayName, Math.max(1, remaining), targets, true));
        JobManager.get().submitFront(new AcquireJob(needed, 1));
        return Job.State.DONE;
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
