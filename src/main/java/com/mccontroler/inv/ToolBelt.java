package com.mccontroler.inv;

import com.mccontroler.plan.RecipeTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Picks and holds the right tool for a block, and says when a replacement is needed.
 *
 * <p>Two things go wrong without this. A wooden pickaxe cannot harvest iron ore at all, so the
 * bot happily breaks blocks and collects nothing; and a tool one hit from breaking counts as
 * "have a pickaxe" at planning time, then snaps mid-plan and every later step fails.
 */
public final class ToolBelt {

    /** Higher tiers harvest everything a lower tier can. Gold mines fast but harvests like wood. */
    private static final Map<String, Integer> TIER_RANK = Map.of(
            "wooden", 1, "golden", 1, "stone", 2, "iron", 3, "diamond", 4, "netherite", 5);

    /**
     * Treat a tool as spent below this fraction of its durability.
     *
     * <p>Planning around a tool with three hits left is how a run gets stranded halfway: the
     * plan is built assuming a pickaxe exists, and then it breaks.
     */
    private static final float SPENT_BELOW = 0.10f;

    private ToolBelt() {
    }

    /** Item id of the tool needed to harvest this block, or null when hands will do. */
    public static String neededFor(String blockId) {
        return neededFor(blockId, true);
    }

    /**
     * @param allowShears whether a shears requirement applies here. Shears are only a genuine
     *                     requirement when harvesting the block for ITSELF (a fern breaks into
     *                     nothing without them). Several shearable blocks — leaves, cobweb,
     *                     dead bush — also have a separate, unconditioned-or-fortune-gated branch
     *                     that yields a different item (apple/stick/sapling, string, sticks) when
     *                     NOT holding shears; equipping shears there would suppress that drop
     *                     entirely, so callers mining for that other item must pass false.
     */
    public static String neededFor(String blockId, boolean allowShears) {
        RecipeTable.ToolNeed need = RecipeTable.toolFor(blockId);
        if (need == null || need.tool().equals("none")) {
            return null;
        }
        if (need.tool().equals("shears")) {
            return allowShears ? "minecraft:shears" : null;
        }
        // mineable/* only says which tool is fastest; only some blocks truly require one.
        if (!requiresTool(blockId)) {
            return null;
        }
        int tier = Math.max(1, TIER_RANK.getOrDefault(need.tier(), 0));
        String material = switch (tier) {
            case 2 -> "stone";
            case 3 -> "iron";
            case 4 -> "diamond";
            case 5 -> "netherite";
            default -> "wooden";
        };
        return "minecraft:" + material + "_" + need.tool();
    }

    /** True when the block yields nothing unless the correct tool is used. */
    public static boolean requiresTool(String blockId) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId))
                .map(block -> block.defaultBlockState().requiresCorrectToolForDrops())
                .orElse(false);
    }

    /** True when the player carries a usable tool good enough for this block. */
    public static boolean hasSuitable(String blockId) {
        return findSlot(blockId, true) >= 0;
    }

    /** True when the player carries a usable tool good enough for this block. */
    public static boolean hasSuitable(String blockId, boolean allowShears) {
        return findSlot(blockId, allowShears) >= 0;
    }

    /**
     * Inventory slot of the best usable tool for the block, or -1.
     * Prefers the lowest adequate tier, so a diamond pickaxe is not worn out on stone.
     */
    public static int findSlot(String blockId) {
        return findSlot(blockId, true);
    }

    /** @param allowShears see {@link #neededFor(String, boolean)}. */
    public static int findSlot(String blockId, boolean allowShears) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return -1;
        }
        RecipeTable.ToolNeed need = RecipeTable.toolFor(blockId);
        if (need == null || need.tool().equals("none")) {
            return -1;
        }
        if (need.tool().equals("shears") && !allowShears) {
            return -1;
        }

        String kind = need.tool();
        int required = kind.equals("shears") ? 0
                : Math.max(1, TIER_RANK.getOrDefault(need.tier(), 0));

        Inventory inv = player.getInventory();
        int best = -1;
        int bestRank = Integer.MAX_VALUE;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || isSpent(stack)) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (kind.equals("shears")) {
                if (id.equals("minecraft:shears")) {
                    return i;
                }
                continue;
            }
            if (!id.endsWith("_" + kind)) {
                continue;
            }
            // "pickaxe" also ends with "axe"; make sure we matched the right kind.
            if (kind.equals("axe") && id.endsWith("_pickaxe")) {
                continue;
            }
            int rank = materialRank(id);
            if (rank >= required && rank < bestRank) {
                bestRank = rank;
                best = i;
            }
        }
        return best;
    }

    /**
     * Holds the right tool for the block.
     *
     * @return false when nothing suitable is carried
     */
    public static boolean equipFor(String blockId) {
        return equipFor(blockId, true);
    }

    /** @param allowShears see {@link #neededFor(String, boolean)}. */
    public static boolean equipFor(String blockId, boolean allowShears) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        int slot = findSlot(blockId, allowShears);
        if (slot < 0) {
            return false;
        }
        if (slot < Inventory.getSelectionSize()) {
            BlockPlacer.hold(player, slot);
            return true;
        }
        // In the main inventory: swap it down to the hotbar first.
        return BlockPlacer.ensureHeld(player.getInventory().getItem(slot).getItem());
    }

    /** Nearly-broken tools are treated as gone, so plans do not depend on them. */
    public static boolean isSpent(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return false;
        }
        int left = stack.getMaxDamage() - stack.getDamageValue();
        return left <= 1 || left < stack.getMaxDamage() * SPENT_BELOW;
    }

    /** Tier of a tool from its material prefix, e.g. {@code minecraft:stone_pickaxe} → 2. */
    private static int materialRank(String itemId) {
        String path = itemId.substring(itemId.indexOf(':') + 1);
        for (Map.Entry<String, Integer> tier : TIER_RANK.entrySet()) {
            if (path.startsWith(tier.getKey() + "_")) {
                return tier.getValue();
            }
        }
        return 0;
    }

    /** The strictest tool requirement across several candidate blocks. */
    public static String neededForAny(List<String> blockIds) {
        return neededForAny(blockIds, true);
    }

    /** @param allowShears see {@link #neededFor(String, boolean)}. */
    public static String neededForAny(List<String> blockIds, boolean allowShears) {
        String needed = null;
        int bestRank = -1;
        for (String blockId : blockIds) {
            String tool = neededFor(blockId, allowShears);
            if (tool == null) {
                continue;
            }
            int rank = materialRank(tool);
            if (rank > bestRank) {
                bestRank = rank;
                needed = tool;
            }
        }
        return needed;
    }
}
