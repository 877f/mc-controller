package com.mccontroler.plan;

import com.mccontroler.inv.InventoryHelper;
import com.mccontroler.inv.ToolBelt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out how to obtain an item: what to mine, what to smelt, what to craft, in what order.
 *
 * <p>Recipes come from {@link RecipeTable} — the complete vanilla set extracted from the server
 * jar — rather than the in-game recipe book, which only contains recipes the player has already
 * unlocked.
 *
 * <p>Resolution backtracks. Most items have several recipes and the obvious first pick is often a
 * cycle: an iron ingot can be smelted from raw iron, or split out of an iron block, which is
 * itself made of nine ingots. Candidates are tried in turn, cycles are blocked with a visiting
 * stack, and the cheapest branch that fully resolves wins.
 */
public final class CraftPlanner {

    /** Guards against pathological recipe graphs; vanilla chains are only a few deep. */
    private static final int MAX_DEPTH = 12;

    /** How much coal the current plan decided it needs; set during {@link #plan}. */
    private static int fuelToPlan;

    /**
     * Tools this plan has already arranged to obtain.
     *
     * <p>Tools are durable, so a second mining step that needs the same tool must not schedule
     * another one. Reset per {@link #plan} call, which only ever runs on the client thread.
     */
    private static final java.util.Set<String> toolsSecured = new java.util.HashSet<>();

    /** The item actually requested, so intermediates can be padded and it cannot be. */
    private static String planTarget = "";

    private CraftPlanner() {
    }

    /** Raised when no route to the item exists. */
    public static class NoRouteException extends RuntimeException {
        public NoRouteException(String message) {
            super(message);
        }
    }

    /** A resolved branch plus what it costs, so competing recipes can be compared. */
    private record Branch(List<PlanStep> steps, Map<Item, Integer> available, int mined, int produced) {
    }

    /**
     * Builds a plan for {@code count} of {@code itemId}. Must run on the client thread.
     * Steps come back in dependency order.
     */
    public static List<PlanStep> plan(String itemId, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            throw new NoRouteException("not in a world");
        }
        Item target = InventoryHelper.resolve(itemId);
        if (target == null) {
            throw new NoRouteException("unknown item: " + itemId);
        }

        toolsSecured.clear();
        fuelToPlan = 0;
        planTarget = itemId;

        List<PlanStep> steps = new ArrayList<>();
        resolve(target, count, new HashMap<>(), steps, new ArrayDeque<>(), 0);
        List<PlanStep> merged = merge(steps);

        // A 3x3 step needs a crafting table, and crafting one costs four planks. Those planks
        // have to be budgeted by the planner, not conjured later: adding the table after the
        // fact means it eats material the plan had already allocated, and the run ends short
        // (12 of 18 fences, in the case that turned this up).
        // Smelting needs a furnace, and a furnace is itself a 3x3 recipe, so it drags in a
        // crafting table too. Both are resolved before the main item so their ingredients are
        // counted and their steps land first.
        List<Item> prerequisites = new ArrayList<>();

        int toSmelt = merged.stream()
                .filter(s -> s.kind() == PlanStep.Kind.SMELT)
                .mapToInt(PlanStep::count)
                .sum();
        boolean needsFurnace = toSmelt > 0;

        if (needsFurnace && InventoryHelper.count("minecraft:furnace") == 0) {
            Item furnace = InventoryHelper.resolve("minecraft:furnace");
            if (furnace != null) {
                prerequisites.add(furnace);
            }
        }

        // Fuel has to be planned like any other material. Previously the bot smelted with
        // whatever happened to be in the bag and simply failed when that ran out — or, worse,
        // burned something it needed. One coal smelts eight items.
        if (needsFurnace) {
            int coalNeeded = (toSmelt + 7) / 8 - InventoryHelper.count("minecraft:coal");
            if (coalNeeded > 0) {
                Item coal = InventoryHelper.resolve("minecraft:coal");
                if (coal != null) {
                    fuelToPlan = coalNeeded;
                    prerequisites.add(coal);
                }
            }
        }

        boolean needsTable = merged.stream().anyMatch(PlanStep::needsTable)
                || needsFurnace;   // the furnace recipe is 3x3
        if (needsTable && InventoryHelper.count("minecraft:crafting_table") == 0) {
            Item table = InventoryHelper.resolve("minecraft:crafting_table");
            if (table != null) {
                prerequisites.add(0, table);   // table before furnace: the furnace needs it
            }
        }

        if (!prerequisites.isEmpty()) {
            // The re-plan starts from scratch, so the tools secured during the first pass must
            // be forgotten. Leaving them marked made the second pass skip crafting them and
            // produced plans that mined diamonds with no iron pickaxe.
            toolsSecured.clear();

            steps = new ArrayList<>();
            Map<Item, Integer> available = new HashMap<>();
            for (Item prerequisite : prerequisites) {
                int amount = id(prerequisite).equals("minecraft:coal") ? fuelToPlan : 1;
                try {
                    resolve(prerequisite, amount, available, steps, new ArrayDeque<>(), 0);
                } catch (NoRouteException e) {
                    // Coal is a convenience, not a hard requirement: charcoal or spare wood may
                    // already be usable, so a failure here must not sink the whole plan.
                    if (!id(prerequisite).equals("minecraft:coal")) {
                        throw e;
                    }
                }
            }
            resolve(target, count, available, steps, new ArrayDeque<>(), 0);
            merged = merge(steps);
        }
        return merged;
    }

    /**
     * Folds repeated steps for the same item together.
     *
     * <p>An item needed by two different branches — planks for both the fence and the sticks —
     * otherwise produces "craft 24 planks" and "craft 8 planks" as separate steps. Totals are
     * merged into the <em>first</em> occurrence, which keeps dependency order valid: everything
     * is made before the first thing that consumes it.
     */
    private static List<PlanStep> merge(List<PlanStep> steps) {
        List<PlanStep> out = new ArrayList<>();
        Map<String, Integer> indexOf = new HashMap<>();

        for (PlanStep step : steps) {
            String key = step.kind() + "|" + step.itemId();
            Integer at = indexOf.get(key);
            if (at == null) {
                indexOf.put(key, out.size());
                out.add(step);
            } else {
                PlanStep existing = out.get(at);
                out.set(at, new PlanStep(existing.kind(), existing.itemId(), existing.displayName(),
                        existing.count() + step.count(), existing.targets(), existing.grid()));
            }
        }
        return out;
    }

    private static void resolve(Item item,
                                int need,
                                Map<Item, Integer> available,
                                List<PlanStep> steps,
                                Deque<Item> visiting,
                                int depth) {

        // Spend what is already accounted for — real inventory first, then earlier steps' output.
        int have = available.computeIfAbsent(item, InventoryHelper::count);
        int used = Math.min(have, need);
        if (used > 0) {
            available.put(item, have - used);
            steps.add(PlanStep.have(id(item), name(item), used));
            need -= used;
        }
        if (need <= 0) {
            return;
        }
        if (depth >= MAX_DEPTH) {
            throw new NoRouteException("recipe chain for " + name(item) + " is too deep");
        }

        String itemId = id(item);

        // Stonecutter and smithing recipes are extracted and understood, but nothing can execute
        // them yet — there is no job that drives those two stations. Planning through them would
        // produce a route that looks right and then dies at the first step, which is worse than
        // routing the long way round, so they are held back until their jobs exist.
        List<RecipeTable.Recipe> candidates = new ArrayList<>();
        for (RecipeTable.Recipe candidate : RecipeTable.producing(itemId)) {
            if (candidate.kind() == RecipeTable.Kind.CUT
                    || candidate.kind() == RecipeTable.Kind.SMITH) {
                continue;
            }
            candidates.add(candidate);
        }

        // Where this item can be dug up: blocks whose loot yields it (diamond from diamond ore),
        // plus the block itself when it generates in the world. Both are filtered against the
        // naturally-generated set, so crafted-only blocks are never proposed as mining targets.
        List<String> mineTargets = new ArrayList<>();
        // Only mine a block for itself when it actually drops itself. Iron ore generates
        // naturally but yields raw iron, so "mine 9 iron ore" would never register progress and
        // the bot would strip every ore it could find.
        boolean selfMine = RecipeTable.isNatural(itemId) && RecipeTable.dropsSelf(itemId);
        if (selfMine) {
            mineTargets.add(itemId);
        }
        for (String source : RecipeTable.droppedBy(itemId)) {
            if (RecipeTable.isNatural(source) && !mineTargets.contains(source)) {
                mineTargets.add(source);
            }
        }

        Branch best = null;
        String lastFailure = null;

        // Mining competes with crafting on cost rather than short-circuiting, so a route that
        // digs up nine ore can lose to one that crafts from something already to hand.
        if (!mineTargets.isEmpty()) {
            List<PlanStep> mineSteps = new ArrayList<>();
            Map<Item, Integer> mineScratch = new HashMap<>(available);
            boolean canMine = true;

            // Harvesting needs the right tool. Without shears a fern breaks into nothing, and a
            // stone pickaxe shatters diamond ore for no drop, so the tool has to be obtained
            // first or the "mine" step is just vandalism.
            String toolId = requiredToolItem(mineTargets, selfMine);

            // A tool is durable: needing one for two different mining steps does not mean
            // crafting two. Without this, a torch plan wanted a pickaxe for the cobblestone and
            // another for the coal, and the merge summed them into "craft 2 × Wooden Pickaxe".
            if (toolId != null && toolsSecured.contains(toolId)) {
                toolId = null;
            }

            if (toolId != null && !visiting.contains(item)) {
                Item tool = InventoryHelper.resolve(toolId);
                if (tool != null) {
                    visiting.push(item);
                    try {
                        // Scratch state, so a failed attempt leaves nothing half-applied.
                        resolve(tool, 1, mineScratch, mineSteps, visiting, depth + 1);
                        toolsSecured.add(toolId);
                    } catch (NoRouteException e) {
                        // Abandon only the MINING branch — never the whole item. Sticks drop
                        // from leaves, leaves need shears, and shears need iron; throwing here
                        // killed the resolve before "craft sticks from planks" was ever tried.
                        canMine = false;
                        lastFailure = "mining " + name(item) + " needs "
                                + toolId.replace("minecraft:", "") + " (" + e.getMessage() + ")";
                    } finally {
                        visiting.pop();
                    }
                }
            }

            if (canMine) {
                // Mine a little extra for anything feeding a later craft. Plans are otherwise
                // exact to the block: three logs make twelve planks, and a table, sticks and a
                // pickaxe consume eleven — so losing a single log strands the whole chain.
                // The final item is never padded; nobody wants a surprise extra diamond block.
                int toMine = itemId.equals(planTarget) ? need : need + Math.max(1, need / 4);
                mineSteps.add(PlanStep.mine(itemId, name(item), toMine, List.copyOf(mineTargets)));
                best = new Branch(mineSteps, mineScratch,
                        toMine * dimensionPenalty(mineTargets), toMine);
            }
        }

        if (candidates.isEmpty() && best == null) {
            throw new NoRouteException("no recipe for " + name(item)
                    + ", and no block drops it");
        }
        if (!visiting.contains(item)) {
            visiting.push(item);
            try {
                for (RecipeTable.Recipe recipe : mergeSmelting(candidates)) {
                    try {
                        Branch branch = expand(item, need, recipe, available, visiting, depth);
                        if (best == null || branch.mined() < best.mined()) {
                            best = branch;
                        }
                    } catch (NoRouteException e) {
                        // Keep the reason: "every recipe needs something unobtainable" is useless
                        // on its own, and the inner cause is what actually identifies the problem.
                        lastFailure = e.getMessage();
                    }
                }
            } finally {
                visiting.pop();
            }
        }

        if (best != null) {
            available.clear();
            available.putAll(best.available());
            steps.addAll(best.steps());
            if (best.produced() > need) {
                available.merge(item, best.produced() - need, Integer::sum);
            }
            return;
        }

        // Deliberately NO "fall back to mining it" here. Being a BlockItem does not mean a block
        // occurs in the world — iron blocks and cakes are block items too. Allowing that fallback
        // produced plans like "mine 1 Block of Iron, craft 9 ingots, craft 1 Block of Iron", and
        // because it scored as one mined item it beat the correct nine-ore route. Only items with
        // no recipe at all are treated as mineable, which is handled above.
        throw new NoRouteException("no way to get " + name(item)
                + (lastFailure == null ? " — no usable recipe" : " — " + lastFailure));
    }

    /**
     * Collapses the several single-input smelting recipes that share a result into one, so the
     * plan says "smelt iron from raw iron / iron ore / deepslate iron ore" and the mining step
     * can accept whichever turns up first.
     */
    private static List<RecipeTable.Recipe> mergeSmelting(List<RecipeTable.Recipe> candidates) {
        List<RecipeTable.Recipe> out = new ArrayList<>();
        List<String> smeltInputs = new ArrayList<>();
        RecipeTable.Recipe firstSmelt = null;

        for (RecipeTable.Recipe recipe : candidates) {
            if (recipe.kind() == RecipeTable.Kind.SMELT && recipe.inputs().size() == 1) {
                if (firstSmelt == null) {
                    firstSmelt = recipe;
                }
                smeltInputs.addAll(recipe.inputs().get(0).alternatives());
            } else {
                out.add(recipe);
            }
        }

        if (firstSmelt != null) {
            RecipeTable.Input merged = new RecipeTable.Input(List.copyOf(new LinkedHashSet<>(smeltInputs)), 1);
            // Smelting first: for ores and food it is nearly always the intended route.
            out.add(0, new RecipeTable.Recipe(RecipeTable.Kind.SMELT, firstSmelt.resultId(),
                    firstSmelt.resultCount(), 0, List.of(merged)));
        }
        return out;
    }

    /** Expands one recipe into a self-contained branch, resolving every input first. */
    private static Branch expand(Item item,
                                 int need,
                                 RecipeTable.Recipe recipe,
                                 Map<Item, Integer> available,
                                 Deque<Item> visiting,
                                 int depth) {

        int perRun = Math.max(1, recipe.resultCount());

        // Make a few spare of anything that feeds a later step, exactly as with mining. Sticks
        // are the case that bit: four pickaxes need eight, the plan made eight, and then a tool
        // broke mid-run and the replacement ate two — leaving the final craft one stick short
        // after everything else had succeeded. The requested item itself is never padded.
        int padded = id(item).equals(planTarget) ? need : need + Math.max(1, need / 4);
        int runs = (padded + perRun - 1) / perRun;

        List<PlanStep> steps = new ArrayList<>();
        Map<Item, Integer> scratch = new HashMap<>(available);
        int mined = 0;

        for (RecipeTable.Input input : recipe.inputs()) {
            List<Item> options = RecipeTable.itemsFor(input);
            if (options.isEmpty()) {
                // Naming the ingredient matters: an unresolvable tag looks identical to a
                // genuinely unobtainable item unless the message says which one it was.
                throw new NoRouteException("ingredient " + String.join("/", input.alternatives())
                        + " for " + name(item) + " matched no items");
            }

            int wanted = input.qty() * runs;

            // Try every option and keep the cheapest, rather than the first that happens to work.
            // A tag like #minecraft:planks expands to every wood type, and taking the first match
            // picks oak even when the inventory is full of birch logs — which then sends the bot
            // off to fell oak trees it does not need.
            NoRouteException last = null;
            List<PlanStep> bestAttempt = null;
            Map<Item, Integer> bestScratch = null;
            int bestMined = Integer.MAX_VALUE;
            int bestPreference = Integer.MAX_VALUE;

            // Banned materials are removed outright; preferred ones are tried first so a close
            // call goes the player's way.
            List<Item> allowed = new ArrayList<>();
            for (Item option : options) {
                if (!MaterialPolicy.isBanned(id(option))) {
                    allowed.add(option);
                }
            }
            if (allowed.isEmpty()) {
                throw new NoRouteException("every option for " + name(item)
                        + " is banned in the material settings");
            }
            allowed.sort(java.util.Comparator.comparingInt(o -> MaterialPolicy.rank(id(o))));
            options = allowed;

            for (Item option : options) {
                List<PlanStep> attempt = new ArrayList<>();
                Map<Item, Integer> inner = new HashMap<>(scratch);
                try {
                    resolve(option, wanted, inner, attempt, visiting, depth + 1);
                    int cost = countMined(attempt);
                    int preference = MaterialPolicy.rank(id(option));

                    // A stated preference wins ties; cost still decides when it actually differs,
                    // so preferring birch never sends the bot across the world for one.
                    boolean better = cost < bestMined
                            || (cost == bestMined && preference < bestPreference);
                    if (better) {
                        bestMined = cost;
                        bestPreference = preference;
                        bestAttempt = attempt;
                        bestScratch = inner;
                    }
                    if (cost == 0 && preference == 0) {
                        break;  // Free and top of the preference list; nothing can beat that.
                    }
                } catch (NoRouteException e) {
                    last = e;
                }
            }

            if (bestAttempt == null) {
                throw last == null ? new NoRouteException("cannot satisfy " + name(item)) : last;
            }
            steps.addAll(bestAttempt);
            scratch = bestScratch;
            mined += bestMined;
        }

        int produced = runs * perRun;
        steps.add(switch (recipe.kind()) {
            case SMELT -> PlanStep.smelt(id(item), name(item), produced);
            case CUT -> PlanStep.cut(id(item), name(item), produced);
            case SMITH -> PlanStep.smith(id(item), name(item), produced);
            case CRAFT -> PlanStep.craft(id(item), name(item), produced, recipe.grid());
        });

        return new Branch(steps, scratch, mined, produced);
    }

    /**
     * True when this block drops nothing unless the correct tool is used.
     *
     * <p>Stone needs a pickaxe; a log does not need an axe. The game models this per block rather
     * than in the tool tags, so it is read from the block itself.
     */
    private static boolean requiresTool(String blockId) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId))
                .map(block -> block.defaultBlockState().requiresCorrectToolForDrops())
                .orElse(false);
    }

    /** Material ranking for tool tiers; higher harvests everything a lower one can. */
    private static final Map<String, Integer> TIER_RANK = Map.of(
            "none", 0, "wooden", 1, "golden", 1, "stone", 2, "iron", 3, "diamond", 4, "netherite", 5);

    /**
     * The tool that must be obtained before these blocks can be harvested, or null if the player
     * already has something good enough (or none is needed).
     */
    private static String requiredToolItem(List<String> targets, boolean selfMine) {
        String tool = null;
        int tier = 0;

        // Take the strictest requirement across the acceptable targets.
        for (String target : targets) {
            RecipeTable.ToolNeed need = RecipeTable.toolFor(target);
            if (need == null || need.tool().equals("none")) {
                continue;
            }

            // Shears are only a genuine requirement when harvesting the block for ITSELF (a fern
            // breaks into nothing without them). Leaves, cobweb and dead bush also have a separate
            // branch that yields a different item (apple/stick/sapling, string, sticks) precisely
            // when NOT holding shears — equipping shears there suppresses that drop entirely, so
            // when this block is only a source for some other item, shears must not be demanded.
            if (need.tool().equals("shears") && !selfMine) {
                continue;
            }

            // mineable/* says which tool is FASTEST, not which is required. A log is tagged
            // mineable/axe but drops perfectly well bare-handed. Only blocks that actually
            // require the correct tool to drop anything create a real dependency — otherwise
            // "mine a log" demands an axe, which needs planks, which need a log. Shears are
            // the exception: they are a genuine requirement expressed through the loot table.
            if (!need.tool().equals("shears") && !requiresTool(target)) {
                continue;
            }

            int rank = TIER_RANK.getOrDefault(need.tier(), 0);
            if (tool == null || rank > tier) {
                tool = need.tool();
                tier = rank;
            }
        }
        if (tool == null) {
            return null;
        }

        // Ask the tool belt, which ignores nearly-spent tools. Planning around a pickaxe with
        // three hits left is how a run gets stranded: the plan assumes it exists, then it snaps
        // and every later step fails for want of a tool.
        for (String target : targets) {
            if (ToolBelt.neededFor(target, selfMine) != null && !ToolBelt.hasSuitable(target, selfMine)) {
                return ToolBelt.neededFor(target, selfMine);
            }
        }
        return null;
    }

    /**
     * Cost multiplier for mining these blocks, based on how far out of the way they are.
     *
     * <p>A route needing another dimension is not impossible, but it is enormously more expensive
     * than one that is not, and the planner should never pick "fetch nether quartz" over "mine
     * the diorite that is already under your feet". If any target is reachable in the current
     * dimension the cost is normal; otherwise it is penalised hard.
     */
    private static int dimensionPenalty(List<String> targets) {
        String here = currentDimension();
        for (String target : targets) {
            String dim = RecipeTable.naturalDimension(target);
            if (dim == null || dim.equals(here)) {
                return 1;
            }
        }
        return 500;
    }

    private static String currentDimension() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return "overworld";
        }
        return level.dimension().identifier().getPath();
    }

    private static boolean hasAny(Map<Item, Integer> available, List<Item> options) {
        for (Item option : options) {
            if (available.computeIfAbsent(option, InventoryHelper::count) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mining cost of a branch, weighted by how far out of the way the blocks are.
     *
     * <p>The weighting is the point. Blackstone and cobblestone are both valid stone tool
     * materials and both "3 blocks mined", so an unweighted count let a tie go to whichever the
     * tag happened to list first — and sent the bot hunting blackstone in the Overworld. Charging
     * for a dimension change makes the local option win outright.
     */
    private static int countMined(List<PlanStep> steps) {
        int total = 0;
        for (PlanStep step : steps) {
            if (step.kind() == PlanStep.Kind.MINE) {
                total += step.count() * dimensionPenalty(step.targets());
            }
        }
        return total;
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String name(Item item) {
        return Component.translatable(item.getDescriptionId()).getString();
    }
}
