package com.mccontroler;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bridges HTTP worker threads onto the Minecraft client thread.
 *
 * <p>Anything that reads or mutates game state has to run on the client thread. HTTP handlers
 * call {@link #get} and block briefly for the answer.
 */
public final class GameThread {

    /** Long enough to survive a lag spike, short enough that the panel does not appear hung. */
    private static final long TIMEOUT_SECONDS = 10;

    private GameThread() {
    }

    /** Runs {@code work} on the client thread and returns its result. */
    public static <T> T get(Supplier<T> work) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            return work.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting on the client thread", e);
        } catch (Exception e) {
            throw new IllegalStateException("client thread call failed: " + e.getMessage(), e);
        }
    }

    /** Runs {@code work} on the client thread without waiting for it. */
    public static void run(Runnable work) {
        Minecraft.getInstance().execute(work);
    }
}
