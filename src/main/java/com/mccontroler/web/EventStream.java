package com.mccontroler.web;

import com.mccontroler.MCControler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-sent events: one-way push of job status and log lines to every open panel.
 *
 * <p>SSE rather than WebSockets because the traffic is one-directional and the JDK's bundled
 * HTTP server has no WebSocket support, which would otherwise mean shading a library.
 */
public final class EventStream {

    private static final Set<Subscriber> SUBSCRIBERS = ConcurrentHashMap.newKeySet();

    /**
     * Recent log lines, replayed to each new subscriber.
     *
     * <p>Without this, refreshing the panel throws away the entire history — including the
     * failure snapshot you reloaded in order to read.
     */
    private static final int HISTORY_LIMIT = 300;
    private static final Deque<String> HISTORY = new ArrayDeque<>();

    private EventStream() {
    }

    private record Subscriber(HttpExchange exchange, OutputStream out) {
    }

    /** Attaches an exchange to the stream. The exchange stays open until the client disconnects. */
    public static void subscribe(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        // Length 0 with SSE means "chunked, stays open".
        ex.sendResponseHeaders(200, 0);

        Subscriber sub = new Subscriber(ex, ex.getResponseBody());
        SUBSCRIBERS.add(sub);

        // A comment line flushes headers so the browser fires onopen immediately.
        write(sub, ": connected\n\n");

        // Replay what already happened, so a reload does not lose the log.
        List<String> replay;
        synchronized (HISTORY) {
            replay = new ArrayList<>(HISTORY);
        }
        for (String frame : replay) {
            write(sub, frame);
        }

        // Hold the handler thread; it is a virtual thread, so parking here is cheap.
        try {
            while (SUBSCRIBERS.contains(sub)) {
                Thread.sleep(15_000);
                // Heartbeat doubles as the disconnect check: it throws once the peer is gone.
                write(sub, ": ping\n\n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            SUBSCRIBERS.remove(sub);
        }
    }

    /** Pushes a log line to every connected panel. Level is one of "", "ok", "err". */
    public static void log(String line, String level) {
        MCControler.LOGGER.info("[MC Controler] {}", line);
        emit("log", Json.obj("line", line, "level", level));
    }

    public static void log(String line) {
        log(line, "");
    }

    /** Pushes the current job state: title, 0..1 progress, and whether anything is running. */
    public static void job(String title, float progress, boolean active) {
        emit("job", Json.obj("title", title, "progress", progress, "active", active));
    }

    private static void emit(String event, String jsonData) {
        String frame = "event: " + event + "\ndata: " + jsonData + "\n\n";

        // Only log lines are worth replaying; job status is a "current value", not history.
        if (event.equals("log")) {
            synchronized (HISTORY) {
                HISTORY.addLast(frame);
                while (HISTORY.size() > HISTORY_LIMIT) {
                    HISTORY.removeFirst();
                }
            }
        }

        for (Subscriber sub : SUBSCRIBERS) {
            write(sub, frame);
        }
    }

    private static void write(Subscriber sub, String frame) {
        try {
            sub.out().write(frame.getBytes(StandardCharsets.UTF_8));
            sub.out().flush();
        } catch (IOException e) {
            // Client closed the tab; drop it quietly.
            SUBSCRIBERS.remove(sub);
            try {
                sub.exchange().close();
            } catch (Exception ignored) {
                // already torn down
            }
        }
    }
}
