package com.mccontroler.job;

import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.inv.BlockPlacer;
import com.mccontroler.inv.FuelConfig;
import com.mccontroler.inv.InventoryHelper;
import com.mccontroler.inv.Stations;
import com.mccontroler.web.EventStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Smelts a target count of one item in a furnace.
 *
 * <p>Places a furnace, loads it by shift-clicking — the furnace menu routes a smeltable item to
 * the input slot and a fuel to the fuel slot by itself, so no slot arithmetic is needed for the
 * loading — waits for the burn, pulls the output, and picks the furnace back up.
 */
public final class SmeltJob implements Job {

    private static final int ACTION_INTERVAL = 4;

    /** Smelting one item takes 200 ticks, so this has to be generous. */
    private static final int STALL_LIMIT_TICKS = 20 * 90;

    private enum Phase {
        START, NEED_FURNACE, PLACE_FURNACE,
        /** Walking to a furnace the player has assigned. */
        GO_TO_STATION,
        OPEN_FURNACE, LOAD, WAITING
    }

    private final String itemId;
    private final int wanted;

    private Item item;
    private String displayName;
    private Phase phase = Phase.START;
    private int startCount;
    private int lastCount;
    private int ticks;
    private int stalledTicks;
    private BlockPos furnacePos;
    private boolean placedFurnace;
    private int placeAttempts;
    /** Guards against looping when crafting a replacement furnace also fails. */
    private boolean triedCraftingFurnace;
    /** Position awaiting confirmation that the block actually got placed. */
    private BlockPos pendingPos;
    private int verifyTicks;
    /** Spots the server refused, so they are not retried forever. */
    private final Set<BlockPos> tried = new HashSet<>();
    /** Consecutive checks seeing an idle, empty furnace — guards against a mid-smelt false alarm. */
    private int drainedChecks;
    private String lastFuelName = "";
    private String error = "";

    public SmeltJob(String itemId, int wanted) {
        this(itemId, wanted, false);
    }

    /**
     * @param triedCraftingFurnace true when a furnace has already been queued for this request,
     *                             so a second failure reports rather than looping
     */
    public SmeltJob(String itemId, int wanted, boolean triedCraftingFurnace) {
        this.itemId = itemId;
        this.wanted = wanted;
        this.triedCraftingFurnace = triedCraftingFurnace;
    }

    @Override
    public String title() {
        return "Smelting " + wanted + " × " + (displayName == null ? itemId : displayName);
    }

    @Override
    public float progress() {
        if (item == null) {
            return -1f;
        }
        int gained = InventoryHelper.count(item) - startCount;
        return wanted <= 0 ? 1f : Math.min(1f, (float) gained / wanted);
    }

    @Override
    public Job.State tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            error = "not in a world";
            return Job.State.FAILED;
        }

        if (phase == Phase.START) {
            return begin(player);
        }
        if (++ticks % ACTION_INTERVAL != 0) {
            return Job.State.RUNNING;
        }

        int have = InventoryHelper.count(item);
        if (have - startCount >= wanted) {
            return cleanup(player, Job.State.DONE);
        }
        if (have != lastCount) {
            lastCount = have;
            stalledTicks = 0;
            EventStream.log(displayName + ": " + (have - startCount) + "/" + wanted);
        } else {
            stalledTicks += ACTION_INTERVAL;
        }

        return switch (phase) {
            case NEED_FURNACE -> ensureFurnace(player);
            case PLACE_FURNACE -> placeFurnace(player);
            case GO_TO_STATION -> goToStation(player);
            case OPEN_FURNACE -> openFurnace(mc, player);
            case LOAD -> load(mc, player);
            case WAITING -> waitForOutput(mc, player);
            default -> Job.State.RUNNING;
        };
    }

    private Job.State begin(LocalPlayer player) {
        item = InventoryHelper.resolve(itemId);
        if (item == null) {
            error = "unknown item: " + itemId;
            return Job.State.FAILED;
        }
        displayName = new ItemStack(item).getHoverName().getString();
        startCount = InventoryHelper.count(item);
        lastCount = startCount;

        if (findFuel() == null) {
            error = "no usable fuel in the inventory — check the Fuel tab to see what is allowed"
                    + " (anything the game treats as fuel can be enabled there)";
            return Job.State.FAILED;
        }

        phase = Phase.NEED_FURNACE;
        EventStream.log("smelting " + wanted + " × " + displayName);
        return Job.State.RUNNING;
    }

    private Job.State ensureFurnace(LocalPlayer player) {
        if (player.containerMenu instanceof AbstractFurnaceMenu) {
            phase = Phase.LOAD;
            return Job.State.RUNNING;
        }

        // An assigned furnace is binding: use that one and never place or craft another.
        BlockPos pinned = Stations.get(Stations.FURNACE);
        if (pinned != null) {
            if (!Stations.stillThere(Stations.FURNACE)) {
                error = "the assigned furnace at " + pinned.toShortString()
                        + " is gone — reassign it or clear the assignment in Places";
                return Job.State.FAILED;
            }
            furnacePos = pinned;
            placedFurnace = false;   // not ours to reclaim
            if (player.getEyePosition().distanceTo(Vec3.atCenterOf(pinned)) <= 4.2) {
                phase = Phase.OPEN_FURNACE;
            } else {
                BaritoneBridge.travelTo(pinned);
                phase = Phase.GO_TO_STATION;
                EventStream.log("heading to the assigned furnace at " + pinned.toShortString());
            }
            return Job.State.RUNNING;
        }
        if (furnacePos != null && isFurnaceAt(furnacePos)) {
            phase = Phase.OPEN_FURNACE;
            return Job.State.RUNNING;
        }

        BlockPos nearby = findFurnaceNearby(player);
        if (nearby != null) {
            furnacePos = nearby;
            placedFurnace = false;
            phase = Phase.OPEN_FURNACE;
            EventStream.log("using the furnace at " + nearby.toShortString());
            return Job.State.RUNNING;
        }

        if (InventoryHelper.count("minecraft:furnace") > 0) {
            phase = Phase.PLACE_FURNACE;
            return Job.State.RUNNING;
        }

        // None carried and none nearby — most likely left standing somewhere the bot has walked
        // away from. Make another rather than failing the whole chain.
        if (!triedCraftingFurnace) {
            triedCraftingFurnace = true;
            EventStream.log("no furnace to hand — crafting another", "ok");
            JobManager.get().submitFront(new SmeltJob(itemId, wanted, true));
            JobManager.get().submitFront(new CraftJob("minecraft:furnace", 1, 3));
            return Job.State.DONE;
        }

        error = "no furnace in the inventory, none within reach, and crafting one failed";
        return Job.State.FAILED;
    }

    /**
     * Places a furnace and confirms it actually appeared.
     *
     * <p>{@code tryPlace} only reports that the interaction was sent; the server can reject it.
     * Trusting it meant the job logged "placed a furnace" and then burned every remaining attempt
     * failing to place another one on top of a block that was never there.
     */
    private Job.State placeFurnace(LocalPlayer player) {
        // Confirm the previous attempt before making another.
        if (pendingPos != null) {
            if (isFurnaceAt(pendingPos)) {
                furnacePos = pendingPos;
                placedFurnace = true;
                pendingPos = null;
                phase = Phase.OPEN_FURNACE;
                EventStream.log("placed a furnace at " + furnacePos.toShortString());
                return Job.State.RUNNING;
            }
            // Block updates round-trip to the server; too short a window means placing a second
            // furnace because the first was not visible yet.
            if (++verifyTicks < 8) {
                return Job.State.RUNNING;
            }
            tried.add(pendingPos);
            pendingPos = null;
            verifyTicks = 0;
        }

        Item furnace = InventoryHelper.resolve("minecraft:furnace");
        if (furnace == null || InventoryHelper.count(furnace) == 0) {
            // Most likely already placed rather than actually missing.
            BlockPos standing = findFurnaceNearby(player);
            if (standing != null) {
                furnacePos = standing;
                placedFurnace = true;
                phase = Phase.OPEN_FURNACE;
                EventStream.log("found the furnace already placed at " + standing.toShortString());
                return Job.State.RUNNING;
            }
            error = "no furnace in the inventory and none within reach";
            return Job.State.FAILED;
        }

        // Moving the stack to the hotbar round-trips to the server; retry instead of concluding
        // there is nowhere to place it.
        if (!BlockPlacer.ensureHeld(furnace)) {
            if (++placeAttempts > 20) {
                error = "could not get the furnace into the hotbar";
                return Job.State.FAILED;
            }
            return Job.State.RUNNING;
        }

        BlockPos origin = player.blockPosition();

        for (int dy : new int[]{0, 1, -1}) {
            for (int radius = 1; radius <= 4; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos candidate = origin.offset(dx, dy, dz);
                        if (tried.contains(candidate)) {
                            continue;
                        }
                        if (BlockPlacer.tryPlace(candidate, furnace)) {
                            pendingPos = candidate;   // verified on a later tick
                            verifyTicks = 0;
                            return Job.State.RUNNING;
                        }
                    }
                }
            }
        }
        if (++placeAttempts <= 20) {
            return Job.State.RUNNING;
        }
        error = "could not place a furnace after " + placeAttempts + " attempts around "
                + origin.toShortString() + " (" + tried.size() + " spots rejected)"
                + " — needs an empty block with something solid under it";
        return Job.State.FAILED;
    }

    /** Walks to the assigned furnace until it is within arm's reach. */
    private Job.State goToStation(LocalPlayer player) {
        double distance = player.getEyePosition().distanceTo(Vec3.atCenterOf(furnacePos));
        if (distance <= 4.2) {
            BaritoneBridge.stop();
            phase = Phase.OPEN_FURNACE;
            return Job.State.RUNNING;
        }
        if (!BaritoneBridge.isTravelling()) {
            error = "could not reach the assigned furnace at " + furnacePos.toShortString()
                    + " — still " + Math.round(distance) + " blocks away";
            return Job.State.FAILED;
        }
        return Job.State.RUNNING;
    }

    private Job.State openFurnace(Minecraft mc, LocalPlayer player) {
        if (player.containerMenu instanceof AbstractFurnaceMenu) {
            phase = Phase.LOAD;
            return Job.State.RUNNING;
        }
        if (furnacePos == null || mc.gameMode == null || !isFurnaceAt(furnacePos)) {
            phase = Phase.NEED_FURNACE;
            return Job.State.RUNNING;
        }
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(furnacePos), Direction.UP, furnacePos, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        return Job.State.RUNNING;
    }

    /**
     * Tops up the input and fuel slots. Never fails on an empty inventory: once everything has
     * been shift-clicked in, the bag is legitimately empty while the furnace is full and working.
     */
    private Job.State load(Minecraft mc, LocalPlayer player) {
        if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            phase = Phase.NEED_FURNACE;
            return Job.State.RUNNING;
        }

        if (menu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty()) {
            Item input = smeltingInput();
            if (input != null) {
                quickMoveFromInventory(mc, player, menu, input);
            }
        }
        if (menu.slots.get(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty()) {
            Item fuel = findFuel();
            if (fuel != null) {
                // Say what is being burned: it was silently eating crafting tables before.
                String fuelName = new ItemStack(fuel).getHoverName().getString();
                if (!fuelName.equals(lastFuelName)) {
                    EventStream.log("burning " + fuelName + " as fuel");
                    lastFuelName = fuelName;
                }
                quickMoveFromInventory(mc, player, menu, fuel);
            }
        }

        phase = Phase.WAITING;
        return Job.State.RUNNING;
    }

    /** Pulls finished output out, tops the furnace back up, and decides when it is truly done. */
    private Job.State waitForOutput(Minecraft mc, LocalPlayer player) {
        if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            phase = Phase.NEED_FURNACE;
            return Job.State.RUNNING;
        }

        boolean gotOutput = !menu.slots.get(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty();
        if (gotOutput) {
            mc.gameMode.handleContainerInput(menu.containerId, AbstractFurnaceMenu.RESULT_SLOT,
                    0, ContainerInput.QUICK_MOVE, player);
            stalledTicks = 0;
            drainedChecks = 0;
            return Job.State.RUNNING;
        }

        boolean inputSlotEmpty = menu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty();
        boolean fuelSlotEmpty = menu.slots.get(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty();
        boolean moreInputHeld = smeltingInput() != null;
        boolean moreFuelHeld = findFuel() != null;

        // Something to top up with: go and do it.
        if ((inputSlotEmpty && moreInputHeld) || (fuelSlotEmpty && moreFuelHeld)) {
            phase = Phase.LOAD;
            drainedChecks = 0;
            return Job.State.RUNNING;
        }

        // Out of fuel with work still sitting in the furnace is a real dead end.
        if (fuelSlotEmpty && !moreFuelHeld && (!inputSlotEmpty || moreInputHeld)) {
            if (++drainedChecks > 10) {
                error = "ran out of fuel with " + (InventoryHelper.count(item) - startCount)
                        + "/" + wanted + " " + displayName + " done — allow more fuel in the Fuel tab";
                return cleanup(player, Job.State.FAILED);
            }
            return Job.State.RUNNING;
        }

        // Furnace empty, nothing left to feed it, nothing coming out: everything available has
        // been smelted. Checked over several ticks so a mid-smelt moment is not mistaken for it.
        if (inputSlotEmpty && !moreInputHeld) {
            if (++drainedChecks > 10) {
                int made = InventoryHelper.count(item) - startCount;
                error = "smelted everything available — got " + made + " of " + wanted
                        + " " + displayName + "; gather more raw material and run it again";
                return cleanup(player, Job.State.FAILED);
            }
            return Job.State.RUNNING;
        }

        drainedChecks = 0;
        if (stalledTicks > STALL_LIMIT_TICKS) {
            error = "the furnace produced nothing for 90s";
            return cleanup(player, Job.State.FAILED);
        }
        return Job.State.RUNNING;
    }

    /** Shift-clicks the first inventory stack of this item into the open furnace. */
    private void quickMoveFromInventory(Minecraft mc, LocalPlayer player,
                                        AbstractFurnaceMenu menu, Item wantedItem) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || stack.getItem() != wantedItem) {
                continue;
            }
            // Furnace menu layout: 0-2 are the furnace slots, then 3..29 is the main inventory
            // and 30..38 the hotbar. Inventory indices run hotbar-first, hence the split.
            int menuSlot = i < Inventory.getSelectionSize()
                    ? i + 30
                    : i - Inventory.getSelectionSize() + 3;
            mc.gameMode.handleContainerInput(menu.containerId, menuSlot, 0,
                    ContainerInput.QUICK_MOVE, player);
            return;
        }
    }

    /** What smelts into our target, chosen from what the inventory actually holds. */
    private Item smeltingInput() {
        for (var recipe : com.mccontroler.plan.RecipeTable.producing(itemId)) {
            if (recipe.kind() != com.mccontroler.plan.RecipeTable.Kind.SMELT) {
                continue;
            }
            for (var input : recipe.inputs()) {
                for (Item candidate : com.mccontroler.plan.RecipeTable.itemsFor(input)) {
                    if (InventoryHelper.count(candidate) > 0) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** Best allowed fuel currently carried, per the player's fuel settings. */
    private Item findFuel() {
        return FuelConfig.pick();
    }

    private static boolean isFurnaceAt(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockState(pos).is(Blocks.FURNACE);
    }

    private static BlockPos findFurnaceNearby(LocalPlayer player) {
        BlockPos origin = player.blockPosition();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (isFurnaceAt(candidate)
                            && player.getEyePosition().distanceTo(Vec3.atCenterOf(candidate)) <= 4.2) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Closes the furnace screen and leaves the furnace standing.
     *
     * <p>It deliberately does <em>not</em> break the furnace back down.
     * {@code MultiPlayerGameMode.destroyBlock} only breaks a block outright in creative; in
     * survival it silently does nothing, so the "reclaimed" block stayed in the world and then
     * blocked the next thing trying to occupy that spot. Leaving it is both honest and useful —
     * {@code findFurnaceNearby} reuses it on the next run instead of crafting another.
     */
    private Job.State cleanup(LocalPlayer player, Job.State result) {
        if (player.containerMenu instanceof AbstractFurnaceMenu) {
            player.closeContainer();
        }
        if (placedFurnace && furnacePos != null) {
            EventStream.log("left the furnace at " + furnacePos.toShortString() + " for next time");
        }
        return result;
    }

    @Override
    public void cancel() {
        var player = Minecraft.getInstance().player;
        if (player != null && phase != Phase.START) {
            cleanup(player, Job.State.DONE);
        }
    }

    @Override
    public String error() {
        return error;
    }
}
