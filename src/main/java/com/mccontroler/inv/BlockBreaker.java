package com.mccontroler.inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks a single block over however many ticks it takes.
 *
 * <p>{@code MultiPlayerGameMode.destroyBlock} only removes a block outright in creative; in
 * survival it silently does nothing, which is why "reclaim the crafting table" quietly failed and
 * left blocks in the world. Real breaking is a held action: start, keep going each tick, stop.
 *
 * <p>Used to clear a space when the bot has boxed itself into a one-wide shaft and has nowhere to
 * put a crafting table.
 */
public final class BlockBreaker {

    /** Give up rather than grinding forever on bedrock or an unsuitable tool. */
    private static final int MAX_TICKS = 20 * 15;

    private BlockPos target;
    private int ticks;

    /** Aims at a new block. Passing the same position again continues the existing dig. */
    public void begin(BlockPos pos) {
        if (!pos.equals(target)) {
            target = pos;
            ticks = 0;
        }
    }

    public BlockPos target() {
        return target;
    }

    /** True once the block is gone. */
    public boolean done() {
        if (target == null) {
            return true;
        }
        Level level = Minecraft.getInstance().level;
        return level == null || level.getBlockState(target).isAir();
    }

    public boolean givenUp() {
        return ticks > MAX_TICKS;
    }

    public void clear() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        target = null;
        ticks = 0;
    }

    /**
     * Call once per client tick while breaking.
     *
     * @return true when the block is gone
     */
    public boolean tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null || target == null) {
            return false;
        }
        if (done()) {
            mc.gameMode.stopDestroyBlock();
            return true;
        }
        if (givenUp()) {
            return false;
        }
        ticks++;

        // Hold the best tool for the job, and tell the server about it.
        int tool = bestToolSlot(player, target);
        if (tool >= 0) {
            BlockPlacer.hold(player, tool);
        }
        // Face it: the server rejects mining a block the player is not looking at.
        BlockPlacer.lookAt(player, Vec3.atCenterOf(target));

        // startDestroyBlock begins the swing; continueDestroyBlock advances it each tick.
        if (ticks == 1) {
            mc.gameMode.startDestroyBlock(target, Direction.UP);
        } else {
            mc.gameMode.continueDestroyBlock(target, Direction.UP);
        }
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return false;
    }

    /** Hotbar slot holding the fastest tool for this block, or -1 to use whatever is held. */
    private static int bestToolSlot(LocalPlayer player, BlockPos pos) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return -1;
        }
        var state = level.getBlockState(pos);
        int best = -1;
        float bestSpeed = 1.0f;

        var inv = player.getInventory();
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.getSelectionSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }
}
