package com.mccontroler.job;

import com.mccontroler.inv.BlockPlacer;
import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.inv.InventoryHelper;
import com.mccontroler.inv.Stations;
import com.mccontroler.plan.RecipeTable;
import com.mccontroler.web.EventStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Crafts a target count of one item.
 *
 * <p>Placement is delegated to the game's own recipe-book auto-fill
 * ({@code handlePlaceRecipe}) rather than clicking ingredients into grid slots by hand. That
 * matters because our recipe table records ingredient quantities but not shaped-recipe layouts,
 * and the auto-fill knows the layout. The cost is that it only works for recipes the player has
 * unlocked, which is reported plainly when it bites.
 *
 * <p>2x2 recipes use the player's own inventory grid and need no block. 3x3 recipes need a
 * crafting table: one is placed from the inventory, crafting it first if necessary.
 */
public final class CraftJob implements Job {

    /** Server round-trips need breathing room; act every few ticks rather than every tick. */
    private static final int ACTION_INTERVAL = 4;

    private static final int STALL_LIMIT = 20 * 20;

    private enum Phase {
        START,
        NEED_TABLE,
        PLACE_TABLE,
        /** Walking to a table the player has assigned. */
        GO_TO_STATION,
        /** Digging out a space because there is nowhere to put the table. */
        CLEAR_SPOT,
        OPEN_TABLE,
        CRAFTING,
        CLEANUP
    }

    private final String itemId;
    private final int wanted;
    private final int grid;

    private Item item;
    private String displayName;
    private Phase phase = Phase.START;
    private int startCount;
    private int lastCount;
    private int ticks;
    private int stalledTicks;
    private BlockPos tablePos;
    private boolean placedTable;
    private int placeAttempts;
    /** Guards against looping when crafting a replacement table also fails. */
    private boolean triedCraftingTable;
    /** Guards against looping when topping up materials also fails. */
    private boolean replanned;
    private final com.mccontroler.inv.BlockBreaker breaker = new com.mccontroler.inv.BlockBreaker();
    /** Position awaiting confirmation that the block actually got placed. */
    private BlockPos pendingPos;
    private int verifyTicks;
    /** Spots the server refused, so they are not retried forever. */
    private final Set<BlockPos> tried = new HashSet<>();
    private String error = "";

    public CraftJob(String itemId, int wanted, int grid) {
        this(itemId, wanted, grid, false);
    }

    /**
     * @param triedCraftingTable true when a table has already been queued for this request, so a
     *                           second failure reports rather than looping
     */
    public CraftJob(String itemId, int wanted, int grid, boolean triedCraftingTable) {
        this(itemId, wanted, grid, triedCraftingTable, false);
    }

    /**
     * @param replanned true when materials have already been topped up once for this request,
     *                  so a second shortfall reports instead of looping
     */
    public CraftJob(String itemId, int wanted, int grid,
                    boolean triedCraftingTable, boolean replanned) {
        this.itemId = itemId;
        this.wanted = wanted;
        this.grid = grid;
        this.triedCraftingTable = triedCraftingTable;
        this.replanned = replanned;
    }

    @Override
    public String title() {
        return "Crafting " + wanted + " × " + (displayName == null ? itemId : displayName);
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

        // Everything below talks to the server, so pace it.
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
        } else if (++stalledTicks * ACTION_INTERVAL > STALL_LIMIT) {
            String missing = whatIsMissing();

            // One shortfall should not throw away a chain that has otherwise succeeded. Re-plan
            // for what is still needed and try again: the planner recomputes against the real
            // inventory, so it gathers exactly the shortage. Only once, so a genuinely
            // impossible craft still reports instead of looping.
            if (!replanned && missing.startsWith("need")) {
                replanned = true;
                int remaining = Math.max(1, wanted - (have - startCount));
                EventStream.log("short of materials (" + missing + ") — topping up and retrying",
                        "err");
                cleanup(player, Job.State.DONE);
                JobManager.get().submitFront(new CraftJob(itemId, remaining, grid, true, true));
                JobManager.get().submitFront(new AcquireJob(itemId, remaining));
                return Job.State.DONE;
            }

            error = "stuck at " + (have - startCount) + "/" + wanted + " — " + missing;
            return cleanup(player, Job.State.FAILED);
        }

        return switch (phase) {
            case NEED_TABLE -> ensureTable(player);
            case PLACE_TABLE -> placeTable(player);
            case GO_TO_STATION -> goToStation(player);
            case CLEAR_SPOT -> clearSpot();
            case OPEN_TABLE -> openTable(mc, player);
            case CRAFTING -> craft(mc, player);
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

        if (findRecipe(player) == null) {
            error = displayName + " is not in the recipe book yet, so the game cannot auto-fill it."
                    + " Craft one by hand once to unlock it.";
            return Job.State.FAILED;
        }

        phase = grid > 2 ? Phase.NEED_TABLE : Phase.CRAFTING;
        EventStream.log("crafting " + wanted + " × " + displayName
                + (grid > 2 ? " (needs a crafting table)" : " (2x2, no table needed)"));
        return Job.State.RUNNING;
    }

    /** Makes sure a crafting table is available, reusing one nearby before placing another. */
    private Job.State ensureTable(LocalPlayer player) {
        if (player.containerMenu instanceof CraftingMenu) {
            phase = Phase.CRAFTING;
            return Job.State.RUNNING;
        }

        // An assigned table is binding: walk to that one, never place or craft another.
        BlockPos pinned = Stations.get(Stations.TABLE);
        if (pinned != null) {
            if (!Stations.stillThere(Stations.TABLE)) {
                error = "the assigned crafting table at " + pinned.toShortString()
                        + " is gone — reassign it or clear the assignment in Places";
                return Job.State.FAILED;
            }
            tablePos = pinned;
            placedTable = false;   // not ours to reclaim
            if (player.getEyePosition().distanceTo(Vec3.atCenterOf(pinned)) <= 4.2) {
                phase = Phase.OPEN_TABLE;
            } else {
                BaritoneBridge.travelTo(pinned);
                phase = Phase.GO_TO_STATION;
                EventStream.log("heading to the assigned crafting table at "
                        + pinned.toShortString());
            }
            return Job.State.RUNNING;
        }

        // The one we placed a moment ago is still standing; reopen it rather than placing again.
        if (tablePos != null && isTableAt(tablePos)) {
            phase = Phase.OPEN_TABLE;
            return Job.State.RUNNING;
        }

        BlockPos existing = findTableNearby(player);
        if (existing != null) {
            tablePos = existing;
            placedTable = false;   // not ours, so leave it standing afterwards
            phase = Phase.OPEN_TABLE;
            EventStream.log("using the crafting table at " + existing.toShortString());
            return Job.State.RUNNING;
        }

        if (InventoryHelper.count("minecraft:crafting_table") > 0) {
            phase = Phase.PLACE_TABLE;
            return Job.State.RUNNING;
        }

        // None carried and none nearby — the last one is probably standing somewhere the bot has
        // since walked away from. Tables are four planks, so make another rather than failing;
        // this job requeues itself behind that so it resumes automatically.
        if (!triedCraftingTable) {
            triedCraftingTable = true;
            EventStream.log("no table to hand — crafting another", "ok");
            JobManager.get().submitFront(new CraftJob(itemId, wanted, grid, true));
            JobManager.get().submitFront(new CraftJob("minecraft:crafting_table", 1, 2));
            return Job.State.DONE;
        }

        error = "no crafting table in the inventory, none within reach, and crafting one failed";
        return Job.State.FAILED;
    }

    private static boolean isTableAt(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    /** Looks for a crafting table already standing within arm's reach. */
    private static BlockPos findTableNearby(LocalPlayer player) {
        BlockPos origin = player.blockPosition();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (isTableAt(candidate)
                            && player.getEyePosition().distanceTo(Vec3.atCenterOf(candidate)) <= 4.2) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Drops a crafting table on a free spot within reach.
     *
     * <p>Scans outwards rather than only checking the four blocks at foot level: the bot is often
     * standing in a hole it dug, where those four are solid stone but a spot one block up is free.
     */
    private Job.State placeTable(LocalPlayer player) {
        Item table = InventoryHelper.resolve("minecraft:crafting_table");
        if (table == null || InventoryHelper.count(table) == 0) {
            // It is probably not missing — we most likely just placed it and the block did not
            // register before the verify window closed. Look around before giving up: failing
            // here reported "went missing from the inventory" while it stood a block away.
            BlockPos standing = findTableNearby(player);
            if (standing != null) {
                tablePos = standing;
                placedTable = true;
                phase = Phase.OPEN_TABLE;
                EventStream.log("found the table already placed at " + standing.toShortString());
                return Job.State.RUNNING;
            }
            error = "no crafting table in the inventory and none within reach";
            return Job.State.FAILED;
        }

        // Confirm the previous attempt: tryPlace only reports that the interaction was sent, and
        // the server can refuse it.
        if (pendingPos != null) {
            if (isTableAt(pendingPos)) {
                tablePos = pendingPos;
                placedTable = true;
                pendingPos = null;
                phase = Phase.OPEN_TABLE;
                EventStream.log("placed a crafting table at " + tablePos.toShortString());
                return Job.State.RUNNING;
            }
            // Block updates round-trip to the server, so be patient before writing the spot off.
            // Too short a window made it place a table, fail to see it, and try to place another.
            if (++verifyTicks < 8) {
                return Job.State.RUNNING;
            }
            tried.add(pendingPos);
            pendingPos = null;
            verifyTicks = 0;
        }

        // Getting the stack into the hotbar is a server round-trip; retry rather than treating a
        // not-yet-moved item as "nowhere to place".
        if (!BlockPlacer.ensureHeld(table)) {
            if (++placeAttempts > 20) {
                error = "could not get the crafting table into the hotbar";
                return Job.State.FAILED;
            }
            return Job.State.RUNNING;
        }

        // Foot level first, then one up, then one down: standing on a ledge or in a shallow
        // trench can leave the only free ground a step above or below.
        BlockPos origin = player.blockPosition();
        for (int dy : new int[]{0, 1, -1}) {
            for (int radius = 1; radius <= 4; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        // Only the ring at this radius; inner rings were covered already.
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos candidate = origin.offset(dx, dy, dz);
                        if (tried.contains(candidate)) {
                            continue;
                        }
                        if (BlockPlacer.tryPlace(candidate, table)) {
                            pendingPos = candidate;   // verified on a later tick
                            verifyTicks = 0;
                            return Job.State.RUNNING;
                        }
                    }
                }
            }
        }

        // A placement can fail for a tick for reasons outside our control (chunk not synced,
        // server rejecting the interaction), so give it several goes before giving up.
        // Nowhere to put it. The usual cause is standing at the bottom of a one-wide shaft
        // Baritone dug, where every neighbour is solid — so make room instead of giving up.
        if (++placeAttempts <= 6) {
            BlockPos toClear = spotToClear(player, origin);
            if (toClear != null) {
                breaker.begin(toClear);
                phase = Phase.CLEAR_SPOT;
                EventStream.log("no room for a table — digging out " + toClear.toShortString());
                return Job.State.RUNNING;
            }
            return Job.State.RUNNING;
        }

        error = "could not place a crafting table near " + origin.toShortString()
                + " and could not clear a space either";
        return Job.State.FAILED;
    }

    /** Walks to the assigned table until it is within arm's reach. */
    private Job.State goToStation(LocalPlayer player) {
        double distance = player.getEyePosition().distanceTo(Vec3.atCenterOf(tablePos));
        if (distance <= 4.2) {
            BaritoneBridge.stop();
            phase = Phase.OPEN_TABLE;
            return Job.State.RUNNING;
        }
        if (!BaritoneBridge.isTravelling()) {
            error = "could not reach the assigned crafting table at " + tablePos.toShortString()
                    + " — still " + Math.round(distance) + " blocks away";
            return Job.State.FAILED;
        }
        return Job.State.RUNNING;
    }

    /** Digs the chosen block away, then goes back to placing. */
    private Job.State clearSpot() {
        if (breaker.tick() || breaker.done()) {
            breaker.clear();
            phase = Phase.PLACE_TABLE;
            return Job.State.RUNNING;
        }
        if (breaker.givenUp()) {
            EventStream.log("could not dig out " + breaker.target() + " — trying elsewhere", "err");
            breaker.clear();
            phase = Phase.PLACE_TABLE;
        }
        return Job.State.RUNNING;
    }

    /**
     * A solid, breakable neighbour whose removal leaves a placeable spot: the gap must have
     * something solid beneath it, or the table has nothing to stand on.
     */
    private static BlockPos spotToClear(LocalPlayer player, BlockPos origin) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(dir);
            var state = level.getBlockState(candidate);
            if (state.isAir() || state.getDestroySpeed(level, candidate) < 0) {
                continue;   // already open, or unbreakable like bedrock
            }
            if (level.getBlockState(candidate.below()).isAir()) {
                continue;   // clearing it would leave nothing to stand the table on
            }
            return candidate;
        }
        return null;
    }

    /** Right-clicks the table so its 3x3 menu becomes the open container. */
    private Job.State openTable(Minecraft mc, LocalPlayer player) {
        if (player.containerMenu instanceof CraftingMenu) {
            phase = Phase.CRAFTING;
            return Job.State.RUNNING;
        }
        if (tablePos == null || mc.gameMode == null) {
            error = "lost track of the crafting table";
            return Job.State.FAILED;
        }
        if (!mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
            // Something removed it; try again from scratch.
            phase = Phase.NEED_TABLE;
            return Job.State.RUNNING;
        }

        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(tablePos), Direction.UP, tablePos, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        return Job.State.RUNNING;
    }

    /** Fills the grid from the recipe book and pulls the result into the inventory. */
    private Job.State craft(Minecraft mc, LocalPlayer player) {
        if (grid > 2 && !(player.containerMenu instanceof CraftingMenu)) {
            // The screen was closed underneath us.
            phase = Phase.NEED_TABLE;
            return Job.State.RUNNING;
        }
        if (mc.gameMode == null) {
            error = "no game mode";
            return Job.State.FAILED;
        }

        RecipeDisplayEntry recipe = findRecipe(player);
        if (recipe == null) {
            error = displayName + " left the recipe book";
            return Job.State.FAILED;
        }

        AbstractContainerMenu menu = grid > 2 ? player.containerMenu : player.inventoryMenu;
        int resultSlot = grid > 2 ? CraftingMenu.RESULT_SLOT : InventoryMenu.RESULT_SLOT;

        // useMaxItems MUST be false. True means "use as many ingredients as possible", which
        // happily turns every plank you own into sticks. False places exactly one craft's worth,
        // and the loop below repeats until the requested count is reached — overshooting by at
        // most a single craft, which is unavoidable given recipes yield in batches.
        mc.gameMode.handlePlaceRecipe(menu.containerId, recipe.id(), false);
        if (!menu.getSlot(resultSlot).getItem().isEmpty()) {
            // 26.2 renamed ClickType to ContainerInput and the method to handleContainerInput.
            mc.gameMode.handleContainerInput(
                    menu.containerId, resultSlot, 0, ContainerInput.QUICK_MOVE, player);
        }
        return Job.State.RUNNING;
    }

    /**
     * Closes the table screen, leaving the table standing.
     *
     * <p>Breaking it back down looked tidier but did not work: {@code destroyBlock} only breaks a
     * block outright in creative, so in survival the table silently stayed put — and then blocked
     * the spot the furnace tried to occupy on the very next step. Leaving it is deliberate, and
     * {@code findTableNearby} reuses it rather than crafting another.
     */
    private Job.State cleanup(LocalPlayer player, Job.State result) {
        if (player.containerMenu instanceof CraftingMenu) {
            player.closeContainer();
        }
        if (placedTable && tablePos != null) {
            EventStream.log("left the crafting table at " + tablePos.toShortString() + " for next time");
        }
        return result;
    }

    /**
     * Names the ingredients the bot is short of.
     *
     * <p>"Most likely missing ingredients" was a guess that left you reading the log to work out
     * which one. This checks the recipe against the inventory and says so.
     */
    private String whatIsMissing() {
        List<String> short_ = new java.util.ArrayList<>();
        for (RecipeTable.Recipe recipe : RecipeTable.producing(itemId)) {
            if (recipe.kind() != RecipeTable.Kind.CRAFT) {
                continue;
            }
            for (RecipeTable.Input input : recipe.inputs()) {
                int held = 0;
                String label = null;
                for (Item option : RecipeTable.itemsFor(input)) {
                    held += InventoryHelper.count(option);
                    if (label == null) {
                        label = new ItemStack(option).getHoverName().getString();
                    }
                }
                if (held < input.qty() && label != null) {
                    short_.add("need " + input.qty() + " × " + label + ", have " + held);
                }
            }
            // Report against the first real recipe; alternatives would only add noise.
            break;
        }
        return short_.isEmpty()
                ? "no ingredients are missing, so the crafting grid is likely not responding"
                : String.join("; ", short_);
    }

    /** Finds the unlocked recipe whose result is our item. */
    private RecipeDisplayEntry findRecipe(LocalPlayer player) {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        ContextMap ctx = SlotDisplayContext.fromLevel(Minecraft.getInstance().level);
        for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                ItemStack result = entry.display().result().resolveForFirstStack(ctx);
                if (!result.isEmpty() && result.getItem() == item) {
                    // Only offer recipes that fit the grid we are planning to use.
                    if (grid > 2 || fitsSmallGrid(entry)) {
                        return entry;
                    }
                }
            }
        }
        return null;
    }

    /** A 2x2 grid can only take recipes with at most four ingredient slots. */
    private static boolean fitsSmallGrid(RecipeDisplayEntry entry) {
        return entry.craftingRequirements().map(list -> list.size() <= 4).orElse(false);
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
