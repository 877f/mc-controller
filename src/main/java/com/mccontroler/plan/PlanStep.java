package com.mccontroler.plan;

import java.util.List;

/**
 * One action in a plan to obtain an item.
 *
 * <p>Steps come back in dependency order: everything a step needs appears before it.
 *
 * @param targets for {@link Kind#MINE}, every block that yields this item — Baritone takes a list
 *                and mines whichever it finds first, so iron can come from either ore variant
 * @param grid    for {@link Kind#CRAFT}, the smallest grid that fits: 2 for the player's own
 *                inventory, 3 for a crafting table
 */
public record PlanStep(Kind kind,
                       String itemId,
                       String displayName,
                       int count,
                       List<String> targets,
                       int grid) {

    public enum Kind {
        /** Already in the inventory; nothing to do. Kept so the plan explains itself. */
        HAVE,
        /** Mine one of {@link #targets}. */
        MINE,
        /** Craft it from ingredients produced by earlier steps. */
        CRAFT,
        /** Smelt it from an input produced by earlier steps. */
        SMELT
    }

    public static PlanStep have(String id, String name, int count) {
        return new PlanStep(Kind.HAVE, id, name, count, List.of(id), 0);
    }

    public static PlanStep mine(String id, String name, int count, List<String> targets) {
        return new PlanStep(Kind.MINE, id, name, count, targets, 0);
    }

    public static PlanStep craft(String id, String name, int count, int grid) {
        return new PlanStep(Kind.CRAFT, id, name, count, List.of(id), grid);
    }

    public static PlanStep smelt(String id, String name, int count) {
        return new PlanStep(Kind.SMELT, id, name, count, List.of(id), 0);
    }

    /** True when this step cannot be done with the player's own 2x2 grid. */
    public boolean needsTable() {
        return kind == Kind.CRAFT && grid > 2;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case HAVE -> "have " + count + " × " + displayName;
            case MINE -> targets.size() > 1
                    ? "mine " + count + " × " + displayName + " (from any of " + targets.size() + " blocks)"
                    : "mine " + count + " × " + displayName;
            case CRAFT -> "craft " + count + " × " + displayName
                    + (needsTable() ? " (crafting table)" : "");
            case SMELT -> "smelt " + count + " × " + displayName;
        };
    }
}
