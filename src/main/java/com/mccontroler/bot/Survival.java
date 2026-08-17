package com.mccontroler.bot;

import com.mccontroler.BotSettings;
import com.mccontroler.inv.BlockPlacer;
import com.mccontroler.inv.InventoryHelper;
import com.mccontroler.job.JobManager;
import com.mccontroler.web.EventStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps the bot alive while it works unattended.
 *
 * <p>Every one of these exists because of an observed failure. The bot starved down to half a
 * food bar on long jobs, mined at light level zero and was killed by a zombie that spawned on
 * top of it, and then lost everything with no way to find the spot again.
 */
public final class Survival {

    /** Eat below this many hunger points. 17 keeps regeneration running and sprinting available. */
    private static final int EAT_BELOW = 17;

    /** A meal is 32 ticks; wait a little longer before considering another. */
    private static final int EAT_TICKS = 40;

    /** Mobs spawn at light 0; place a torch below this to keep the work area quiet. */
    private static final int TORCH_BELOW_LIGHT = 8;

    private static final int TORCH_INTERVAL = 40;

    private static int useCooldown;
    private static int torchCooldown;
    private static boolean announcedNoFood;

    private Survival() {
    }

    /** Called every client tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        eatIfHungry(mc, player);
        torchIfDark(mc, player);
    }

    /** Holds food and eats it when hunger drops, so long jobs do not end in starvation. */
    private static void eatIfHungry(Minecraft mc, LocalPlayer player) {
        if (!BotSettings.get("autoEat")) {
            return;
        }
        if (useCooldown > 0) {
            useCooldown--;
            return;
        }

        // Eating takes about 32 ticks of CONTINUOUS use. Re-sending the request part-way
        // restarts it, so the meal never finishes — and meanwhile the hotbar keeps getting
        // grabbed, which fights the player trying to eat for themselves. Leave it alone while
        // a use is already in progress.
        if (player.isUsingItem()) {
            return;
        }
        if (player.getFoodData().getFoodLevel() >= EAT_BELOW) {
            announcedNoFood = false;
            return;
        }

        int slot = findFoodSlot(player);
        if (slot < 0) {
            if (!announcedNoFood && JobManager.get().isBusy()) {
                EventStream.log("hungry with no food in the inventory", "err");
                announcedNoFood = true;
            }
            return;
        }

        // Must go through hold(): selecting a slot client-side alone leaves the server thinking
        // a different item is in hand, and the eat request is then ignored.
        BlockPlacer.hold(player, slot);
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        // Long enough for the whole meal, so the next check cannot interrupt it.
        useCooldown = EAT_TICKS;
    }

    /** First edible hotbar item. Only the hotbar can be held, so only the hotbar is searched. */
    private static int findFoodSlot(LocalPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (isFood(inv.getItem(i))) {
                return i;
            }
        }
        // Nothing to hand: bring some down from the main inventory for next time.
        for (int i = Inventory.getSelectionSize(); i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (isFood(stack) && BlockPlacer.ensureHeld(stack.getItem())) {
                return findFirstHotbar(inv, stack.getItem());
            }
        }
        return -1;
    }

    private static int findFirstHotbar(Inventory inv, Item item) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    /** Food is a data component, so this covers modded edibles too. */
    private static boolean isFood(ItemStack stack) {
        if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) {
            return false;
        }
        // Rotten flesh and spider eyes do more harm than good while working.
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return !id.equals("minecraft:rotten_flesh")
                && !id.equals("minecraft:spider_eye")
                && !id.equals("minecraft:poisonous_potato")
                && !id.equals("minecraft:pufferfish")
                && !id.equals("minecraft:chicken");
    }

    /**
     * Drops a torch when working in the dark.
     *
     * <p>Only while a job is running, so it does not litter the world during idle time. Mining at
     * light zero is what got the bot killed: hostile mobs spawn in exactly the tunnel it digs.
     */
    private static void torchIfDark(Minecraft mc, LocalPlayer player) {
        if (torchCooldown > 0) {
            torchCooldown--;
            return;
        }
        torchCooldown = TORCH_INTERVAL;

        if (!BotSettings.get("autoTorch") || !JobManager.get().isBusy()) {
            return;
        }
        BlockPos at = player.blockPosition();
        if (mc.level.getMaxLocalRawBrightness(at) >= TORCH_BELOW_LIGHT) {
            return;
        }

        Item torch = InventoryHelper.resolve("minecraft:torch");
        if (torch == null || InventoryHelper.count(torch) == 0) {
            return;
        }
        // The bot's own square is free ground with something solid beneath it, which is exactly
        // what a torch needs, and it is guaranteed to be within reach.
        BlockPlacer.tryPlace(at, torch);
    }

    /** Resets the per-life state after a death. */
    public static void onRespawn() {
        useCooldown = 0;
        torchCooldown = 0;
        announcedNoFood = false;
    }
}
