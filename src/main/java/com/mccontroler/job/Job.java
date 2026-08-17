package com.mccontroler.job;

/**
 * A unit of automation the player asked for.
 *
 * <p>Jobs are driven from the client tick, so every method here runs on the client thread and
 * must not block. A job advances a little per tick and reports what it is doing.
 */
public interface Job {

    /** What happened during a {@link #tick()}. */
    enum State {
        /** Still working; tick again next client tick. */
        RUNNING,
        /** Finished successfully. */
        DONE,
        /** Gave up. {@link #error()} explains why. */
        FAILED
    }

    /** Short human label shown in the panel's job dock, e.g. "Gathering 64 × Oak Log". */
    String title();

    /** Progress in 0..1, or a negative value when the total is not knowable yet. */
    float progress();

    /** Called once per client tick while this job is the active one. */
    State tick();

    /** Called when the job is cancelled or replaced, to release anything it grabbed. */
    void cancel();

    /** Failure reason; only meaningful after {@link #tick()} returns {@link State#FAILED}. */
    default String error() {
        return "";
    }
}
