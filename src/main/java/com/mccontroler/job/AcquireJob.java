package com.mccontroler.job;

import com.mccontroler.plan.CraftPlanner;
import com.mccontroler.plan.PlanStep;
import com.mccontroler.web.EventStream;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns "get me N of X" into a plan and queues the plan's steps.
 *
 * <p>This job only plans. Each step becomes its own {@link MineJob}, {@link CraftJob} or
 * {@link SmeltJob}, queued <em>in plan order</em>, so a step never runs before the one it
 * depends on.
 *
 * <p>It used to execute the mining itself and queue the crafting afterwards, which quietly
 * discarded the plan's ordering: asking for ferns produced a correct plan of "craft shears, then
 * mine ferns" and then went mining first, with the bot breaking ferns bare-handed for nothing.
 */
public final class AcquireJob implements Job {

    private final String itemId;
    private final int wanted;

    private String displayName;
    private String error = "";

    public AcquireJob(String itemId, int wanted) {
        this.itemId = itemId;
        this.wanted = wanted;
    }

    @Override
    public String title() {
        return "Planning " + wanted + " × " + (displayName == null ? itemId : displayName);
    }

    @Override
    public float progress() {
        return -1f;
    }

    @Override
    public Job.State tick() {
        List<PlanStep> plan;
        try {
            plan = CraftPlanner.plan(itemId, wanted);
        } catch (CraftPlanner.NoRouteException e) {
            error = e.getMessage();
            return Job.State.FAILED;
        }

        for (PlanStep step : plan) {
            if (step.itemId().equals(itemId)) {
                displayName = step.displayName();
            }
        }

        EventStream.log("plan for " + wanted + " × "
                + (displayName == null ? itemId : displayName) + ":");
        for (PlanStep step : plan) {
            EventStream.log("   " + step);
        }

        List<Job> steps = new ArrayList<>();
        for (PlanStep step : plan) {
            switch (step.kind()) {
                case MINE -> steps.add(new MineJob(
                        step.itemId(), step.displayName(), step.count(), step.targets()));
                case CRAFT -> steps.add(new CraftJob(
                        step.itemId(), step.count(), step.grid()));
                case SMELT -> steps.add(new SmeltJob(step.itemId(), step.count()));
                case HAVE -> {
                    // Already in the inventory; nothing to do.
                }
            }
        }

        // Insert at the FRONT, in order, so the plan runs immediately rather than behind
        // whatever is already waiting. This job stands in for its own steps: appending them
        // let a retry that was queued ahead of us run before the work it was waiting for —
        // a mining job resumed before the pickaxe it had just asked for was crafted.
        for (int i = steps.size() - 1; i >= 0; i--) {
            JobManager.get().submitFront(steps.get(i));
        }
        int queued = steps.size();

        if (queued == 0) {
            EventStream.log("nothing to do — already have " + wanted + " × " + displayName, "ok");
        } else {
            EventStream.log("queued " + queued + " step(s)", "ok");
        }
        return Job.State.DONE;
    }

    @Override
    public void cancel() {
        // Planning holds nothing; the queued steps clean up after themselves.
    }

    @Override
    public String error() {
        return error;
    }
}
