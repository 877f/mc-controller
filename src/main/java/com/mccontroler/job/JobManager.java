package com.mccontroler.job;

import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.inv.Screens;
import com.mccontroler.web.EventStream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs one job at a time, in submission order.
 *
 * <p>Jobs are submitted from HTTP worker threads but only ever executed on the client thread,
 * so the incoming queue is concurrent while everything else is touched from the tick only.
 */
public final class JobManager {

    private static final JobManager INSTANCE = new JobManager();

    /** Written by HTTP threads, drained by the client thread. */
    private final Queue<Job> incoming = new ConcurrentLinkedQueue<>();

    /** Client-thread only. */
    private final Deque<Job> pending = new ArrayDeque<>();
    private Job active;

    /** Set from any thread to ask the running job to stop. */
    private volatile boolean stopRequested;

    /**
     * Set from any thread to hold the queue where it is.
     *
     * <p>Stop is destructive: it cancels the job and throws away the rest of the plan. Pause is
     * the answer to "I want my keyboard back for a minute" — the active job simply stops being
     * ticked, and Baritone is told to let go, so control returns to the player. Nothing is
     * cancelled and nothing is lost.
     */
    private volatile boolean paused;

    private JobManager() {
    }

    public static JobManager get() {
        return INSTANCE;
    }

    /** Queues a job. Safe to call from an HTTP thread. */
    public void submit(Job job) {
        incoming.add(job);
    }

    /**
     * Queues a job to run before anything already waiting. Client thread only.
     *
     * <p>Used when a running job discovers it needs something first — a full inventory turning
     * into "go and unload, then resume" — which must jump ahead of the rest of the plan.
     * Submitted in reverse order, so call it with the last step first.
     */
    public void submitFront(Job job) {
        pending.addFirst(job);
    }

    /** Cancels the running job and clears the queue. Safe to call from an HTTP thread. */
    public void stopAll() {
        stopRequested = true;
    }

    public boolean isBusy() {
        return active != null;
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Holds or resumes the queue.
     *
     * <p>Pausing hands control back to the player by stopping Baritone, so the bot does not keep
     * walking. Resuming lets the active job pick up from wherever it left off — jobs re-read the
     * world each tick, so there is no stale state to repair.
     */
    public void setPaused(boolean value) {
        if (paused == value) {
            return;
        }
        paused = value;
        if (value) {
            BaritoneBridge.stop();
            Screens.closeAny();
            EventStream.log("paused — the bot has stopped and you have control", "ok");
        } else {
            // The active job handed work to Baritone before the pause and Baritone has since been
            // stopped; without this it would see an idle Baritone and report that it gave up.
            if (active != null) {
                active.resume();
            }
            EventStream.log("resumed", "ok");
        }
        broadcast();
    }

    /**
     * Titles of the jobs waiting to run, in order. Client thread only.
     *
     * <p>Anything still sitting in {@code incoming} is included, so a job submitted a moment ago
     * from the panel shows up straight away rather than only after the next tick.
     */
    public List<String> pendingTitles() {
        List<String> out = new ArrayList<>();
        for (Job job : pending) {
            out.add(job.title());
        }
        for (Job job : incoming) {
            out.add(job.title());
        }
        return out;
    }

    /**
     * Drops one waiting job by its position in {@link #pendingTitles()}.
     * The running job is untouched — use stop for that.
     *
     * @return true when something was removed
     */
    public boolean removePending(int index) {
        if (index < 0) {
            return false;
        }
        if (index < pending.size()) {
            List<Job> remaining = new ArrayList<>(pending);
            Job dropped = remaining.remove(index);
            pending.clear();
            pending.addAll(remaining);
            EventStream.log("removed from the queue: " + dropped.title());
            return true;
        }
        // Past the end of pending, it must be one of the freshly submitted jobs.
        int offset = index - pending.size();
        List<Job> incomingList = new ArrayList<>(incoming);
        if (offset >= incomingList.size()) {
            return false;
        }
        Job dropped = incomingList.remove(offset);
        incoming.clear();
        incoming.addAll(incomingList);
        EventStream.log("removed from the queue: " + dropped.title());
        return true;
    }

    /** Title of the running job, or "Idle". Client-thread read. */
    public String activeTitle() {
        return active == null ? "Idle" : active.title();
    }

    public float activeProgress() {
        return active == null ? 0f : active.progress();
    }

    /** Drives the active job. Call once per client tick. */
    public void tick() {
        if (stopRequested) {
            stopRequested = false;
            paused = false;
            handleStop();
        }

        // Stop is still honoured while paused (handled above); everything else waits.
        if (paused) {
            return;
        }

        Job queued;
        while ((queued = incoming.poll()) != null) {
            pending.add(queued);
            EventStream.log("queued: " + queued.title());
        }

        if (active == null) {
            active = pending.poll();
            if (active == null) {
                return;
            }
            EventStream.log("starting: " + active.title(), "ok");
            broadcast();
        }

        Job.State state;
        try {
            state = active.tick();
        } catch (Throwable e) {
            // Throwable, not Exception: a missing Baritone class surfaces as NoClassDefFoundError,
            // which is an Error. Letting that escape takes the whole game down, which is exactly
            // what happened before the api/standalone jar mix-up was found.
            EventStream.log("job crashed: " + e, "err");
            // A stack trace is the one case where the game log genuinely has more than we do,
            // so surface the top frames in the panel rather than making anyone go looking.
            StackTraceElement[] trace = e.getStackTrace();
            for (int i = 0; i < Math.min(6, trace.length); i++) {
                EventStream.log("    at " + trace[i], "err");
            }
            report(active, String.valueOf(e));
            finish();
            return;
        }

        switch (state) {
            case RUNNING -> broadcast();
            case DONE -> {
                EventStream.log("finished: " + active.title(), "ok");
                finish();
            }
            case FAILED -> {
                EventStream.log("failed: " + active.title() + " — " + active.error(), "err");
                report(active, active.error());
                // Queued jobs are the rest of a plan and depend on this one having worked —
                // running them anyway just produces a cascade of confusing follow-on failures
                // ("no furnace available" right after the furnace failed to be crafted).
                int abandoned = pending.size() + incoming.size();
                if (abandoned > 0) {
                    pending.clear();
                    incoming.clear();
                    EventStream.log("cancelled " + abandoned
                            + " later step(s) that depended on this one", "err");
                }
                finish();
            }
        }
    }

    private void handleStop() {
        int dropped = pending.size() + incoming.size();
        pending.clear();
        incoming.clear();
        if (active != null) {
            active.cancel();
            EventStream.log("stopped: " + active.title(), "err");
            active = null;
        }
        if (dropped > 0) {
            EventStream.log("dropped " + dropped + " queued job(s)");
        }
        broadcast();
    }

    /** Emits a failure snapshot so the panel shows why, without anyone reading the game log. */
    private void report(Job job, String error) {
        try {
            for (String line : Diagnostics.snapshot(job, error)) {
                EventStream.log("  " + line, "err");
            }
            Diagnostics.captureScreenshot();
            EventStream.log("  screenshot saved — see the Shot button in the dock", "err");
        } catch (Throwable t) {
            // Diagnostics must never turn a job failure into a crash.
            EventStream.log("  (diagnostics unavailable: " + t + ")", "err");
        }
    }

    private void finish() {
        if (active != null) {
            active.cancel();
        }
        active = null;
        broadcast();
    }

    private void broadcast() {
        EventStream.job(activeTitle(), Math.max(0f, activeProgress()), active != null, paused);
    }
}
