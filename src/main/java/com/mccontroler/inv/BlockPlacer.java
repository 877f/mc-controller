package com.mccontroler.inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places a single block from the hotbar.
 *
 * <p>Used for replanting saplings. Baritone handles placement during builds, but a tree farm
 * needs to drop one sapling on a specific spot without handing the whole job over to it.
 */
public final class BlockPlacer {

    /** Survival reach is 4.5; staying under it keeps the interaction server-legal. */
    private static final double REACH = 4.2;

    private BlockPlacer() {
    }

    /**
     * Tries to place {@code item} at {@code pos}.
     *
     * @return true if the placement was attempted, false when it was not currently possible
     */
    public static boolean tryPlace(BlockPos pos, Item item) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || mc.gameMode == null) {
            return false;
        }

        // The target has to be empty and supported from below.
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        if (level.getBlockState(pos.below()).isAir()) {
            return false;
        }

        Vec3 target = Vec3.atCenterOf(pos);
        if (player.getEyePosition().distanceTo(target) > REACH) {
            return false;
        }

        int slot = ensureInHotbar(player, item);
        if (slot < 0) {
            return false;
        }
        hold(player, slot);

        // Click the top face of the supporting block below the target.
        Vec3 hit = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

        // Face the block first. A real client always looks at what it places and the server
        // checks it, so placing while facing elsewhere gets silently rejected — which looked
        // like "nowhere to put it" even on wide open ground.
        lookAt(player, hit);

        BlockHitResult result = new BlockHitResult(hit, Direction.UP, pos.below(), false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, result);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * Selects a hotbar slot on the client <em>and tells the server</em>.
     *
     * <p>{@code setSelectedSlot} only changes the client's view. Without the packet the server
     * still believes the old item is held, so "place block" arrives while it thinks the bot is
     * holding a pickaxe and is silently ignored — which looked like "nowhere to place it" on
     * wide open ground, for twenty-one attempts in a row.
     */
    public static void hold(LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    /** Points the player at a world position. */
    public static void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        player.setYRot(yaw);
        player.setXRot(pitch);
        // Keep the previous-frame rotation in step so the change is sent this tick rather than
        // being smoothed into an in-between angle.
        player.yRotO = yaw;
        player.xRotO = pitch;
    }

    /** Finds the item in the hotbar. Returns -1 when it is not there. */
    private static int findHotbarSlot(Inventory inv, Item item) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Gets the item into the hotbar and returns its slot, or -1 if the player has none at all.
     *
     * <p>Only hotbar items can be held and placed, and a freshly-crafted item lands wherever
     * there is room — usually the main inventory, not the hotbar. Without this, placing a block
     * the bot just crafted fails with a misleading "no free block beside the bot", because every
     * candidate position was rejected for want of a held item rather than want of space.
     */
    private static int ensureInHotbar(LocalPlayer player, Item item) {
        Inventory inv = player.getInventory();

        int hotbar = findHotbarSlot(inv, item);
        if (hotbar >= 0) {
            return hotbar;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) {
            return -1;
        }

        // Main inventory occupies indices 9..35, which map straight through as menu slot ids.
        for (int i = Inventory.getSelectionSize(); i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            // SWAP moves the stack into the hotbar slot given by the button index.
            int target = Inventory.getSelectionSize() - 1;
            mc.gameMode.handleContainerInput(
                    player.inventoryMenu.containerId, i, target, ContainerInput.SWAP, player);
            return findHotbarSlot(inv, item);
        }
        return -1;
    }

    /** True when the player has the item at all; it is moved to the hotbar when placing. */
    public static boolean inHotbar(Item item) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        if (findHotbarSlot(player.getInventory(), item) >= 0) {
            return true;
        }
        return InventoryHelper.count(item) > 0;
    }

    /**
     * Ensures the item is in the hotbar, returning true only once it is actually there.
     *
     * <p>Callers must treat false as "try again next tick", not as failure. Moving a stack is a
     * container click that round-trips to the server, so checking for it in the same tick can
     * come back empty — which previously made every placement candidate fail and produced a
     * bogus "nothing placeable within reach".
     */
    public static boolean ensureHeld(Item item) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        return ensureInHotbar(player, item) >= 0;
    }
}
