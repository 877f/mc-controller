package com.mccontroler.web;

import com.mccontroler.MCControler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The local control panel server.
 *
 * <p>Bound to loopback by default. Requests arrive on HTTP worker threads and must hop to the
 * client thread via {@link com.mccontroler.GameThread} before touching anything in the game.
 */
public final class WebServer {

    /** First port tried; if it is taken we walk upward rather than failing to start. */
    private static final int BASE_PORT = 7654;
    private static final int PORT_ATTEMPTS = 20;

    private final HttpServer http;
    private final ExecutorService workers;
    private final int port;

    public WebServer() throws IOException {
        HttpServer bound = null;
        int chosen = -1;
        IOException last = null;
        for (int p = BASE_PORT; p < BASE_PORT + PORT_ATTEMPTS; p++) {
            try {
                bound = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
                chosen = p;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (bound == null) {
            throw new IOException("no free port in [" + BASE_PORT + "," + (BASE_PORT + PORT_ATTEMPTS) + ")", last);
        }

        this.http = bound;
        this.port = chosen;
        // Virtual threads: handlers spend most of their time parked waiting on the client thread.
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        this.http.setExecutor(workers);

        this.http.createContext("/", guard(WebServer::serveStatic));
        this.http.createContext("/api/", guard(new ApiHandler()));
    }

    public void start() {
        http.start();
    }

    public void stop() {
        http.stop(0);
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int port() {
        return port;
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/";
    }

    /** Wraps a handler so an unexpected exception becomes a 500 instead of a dropped connection. */
    private static HttpHandler guard(HttpHandler inner) {
        return ex -> {
            try {
                inner.handle(ex);
            } catch (Exception e) {
                MCControler.LOGGER.error("[MC Controler] {} {} failed",
                        ex.getRequestMethod(), ex.getRequestURI(), e);
                try {
                    Http.sendError(ex, 500, String.valueOf(e.getMessage()));
                } catch (IOException ignored) {
                    // client already gone
                }
            } finally {
                ex.close();
            }
        };
    }

    /** Serves the panel's static files out of the mod jar under /web. */
    private static void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        // Refuse traversal outright; everything served here is a flat set of known files.
        if (path.contains("..") || path.contains("//")) {
            Http.sendError(ex, 400, "bad path");
            return;
        }

        String resource = "/web" + path;
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                Http.sendError(ex, 404, "not found: " + path);
                return;
            }
            Http.send(ex, 200, Http.guessContentType(path), in.readAllBytes());
        }
    }
}
