package com.mccontroler.job;

import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.inv.HomeChest;
import com.mccontroler.inv.InventoryHelper;
import com.mccontroler.inv.Screens;
import com.mccontroler.web.EventStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Walks to the home chest and unloads everything the bot does not need to keep working.
 *
 * <p>Tools, food, torches and the crafting station stay in the inventory; everything else is
 * banked. This is what turns a full inventory from a dead end into a round trip.
 */
public final class DepositJob implements Job {

    private static final int ACTION_INTERVAL = 4;
    private static final double REACH = 4.0;
    private static final int STALL_LIMIT = 20 * 90;

    private enum Phase {
        START, TRAVEL, OPEN, DEPOSIT
    }

    private Phase phase = Phase.START;
    private BlockPos chest;
    private int ticks;
    private int stalledTicks;
    private BlockPos lastPos;
    private int deposited;
    private String error = "";

    @Override
    public String title() {
        return "Depositing at the home chest";
    }

    @Override
    public float progress() {
        return -1f;
    }

    @Override
    public Job.State tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            error = "not in a world";
            return Job.State.FAILED;
        }

        if (phase == Phase.START) {
            chest = HomeChest.position();
            if (chest == null) {
                error = "no home chest set — stand next to one and press \"Set home chest\"";
                return Job.State.FAILED;
            }
            BaritoneBridge.travelTo(chest);
            EventStream.log("heading to the home chest at " + chest.toShortString());
            phase = Phase.TRAVEL;
            return Job.State.RUNNING;
        }

        if (++ticks % ACTION_INTERVAL != 0) {
            return Job.State.RUNNING;
        }

        return switch (phase) {
            case TRAVEL -> travel(player);
            case OPEN -> open(mc, player);
            case DEPOSIT -> deposit(mc, player);
            default -> Job.State.RUNNING;
        };
    }

    private Job.State travel(LocalPlayer player) {
        double distance = player.getEyePosition().distanceTo(Vec3.atCenterOf(chest));
        if (distance <= REACH) {
            BaritoneBridge.stop();
            phase = Phase.OPEN;
            return Job.State.RUNNING;
        }

        BlockPos now = player.blockPosition();
        if (!now.equals(lastPos)) {
            lastPos = now;
            stalledTicks = 0;
        } else if ((stalledTicks += ACTION_INTERVAL) > STALL_LIMIT) {
            error = "could not reach the home chest — still "
                    + Math.round(distance) + " blocks away";
            return Job.State.FAILED;
        }

        if (!BaritoneBridge.isTravelling()) {
            error = "Baritone gave up " + Math.round(distance)
                    + " blocks from the home chest";
            return Job.State.FAILED;
        }
        return Job.State.RUNNING;
    }

    private Job.State open(Minecraft mc, LocalPlayer player) {
        if (player.containerMenu instanceof ChestMenu) {
            phase = Phase.DEPOSIT;
            return Job.State.RUNNING;
        }
        if (!(mc.level.getBlockState(chest).getBlock() instanceof ChestBlock)) {
            error = "there is no chest at " + chest.toShortString() + " any more";
            return Job.State.FAILED;
        }
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(chest), Direction.UP, chest, false);
        // The screen arrives a few ticks later, from the server. Flag it now so it still gets
        // closed if this job ends before it lands.
        Screens.expectOpen();
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        return Job.State.RUNNING;
    }

    /** Shift-clicks one stack per action tick so the server keeps up. */
    private Job.State deposit(Minecraft mc, LocalPlayer player) {
        if (!(player.containerMenu instanceof ChestMenu menu)) {
            phase = Phase.OPEN;
            return Job.State.RUNNING;
        }

        // The player's 36 slots always sit at the end of a chest menu, whatever its size.
        int playerStart = menu.slots.size() - 36;
        Inventory inv = player.getInventory();

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (HomeChest.keep(id)) {
                continue;
            }

            // Inventory indices are hotbar-first; the menu lists the main inventory first.
            int menuSlot = i < Inventory.getSelectionSize()
                    ? playerStart + 27 + i
                    : playerStart + (i - Inventory.getSelectionSize());
            mc.gameMode.handleContainerInput(menu.containerId, menuSlot, 0,
                    ContainerInput.QUICK_MOVE, player);
            deposited++;
            return Job.State.RUNNING;
        }

        // Closes the screen as well as the menu; closing only the menu leaves the mouse released.
        Screens.closeAny();
        EventStream.log("deposited " + deposited + " stack(s) — "
                + InventoryHelper.freeSlots() + " free slots", "ok");
        return Job.State.DONE;
    }

    @Override
    public void cancel() {
        BaritoneBridge.stop();
        Screens.closeAny();
    }

    @Override
    public String error() {
        return error;
    }
}
