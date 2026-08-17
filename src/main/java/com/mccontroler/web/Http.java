package com.mccontroler.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small helpers over {@link HttpExchange}: bodies, queries and responses. */
public final class Http {

    private Http() {
    }

    public static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        // The panel is same-origin, but this keeps a stale cached UI from surviving a mod update.
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    public static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    public static void sendText(HttpExchange ex, int status, String text) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends a JSON error body of the shape {"error":"..."}. */
    public static void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, "{\"error\":" + Json.quote(message) + "}");
    }

    public static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Parses a URL query string. Returns an empty map when there is no query. */
    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public static String guessContentType(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "png" -> "image/png";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }
}
