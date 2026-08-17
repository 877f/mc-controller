package com.mccontroler.web;

import java.util.Map;

/**
 * Minimal JSON writing helpers.
 *
 * <p>Minecraft bundles Gson, but the payloads here are small and fixed-shape, so emitting them
 * directly avoids reflection and keeps allocation down on the icon and status endpoints.
 */
public final class Json {

    private Json() {
    }

    /** Quotes and escapes a string as a JSON string literal, including the surrounding quotes. */
    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Builds a flat JSON object from alternating key/value pairs. Values are emitted raw. */
    public static String obj(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("expected key/value pairs");
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(String.valueOf(kv[i]))).append(':').append(raw(kv[i + 1]));
        }
        return sb.append('}').toString();
    }

    /** Renders a value: strings are quoted, numbers/booleans/null are literal. */
    public static String raw(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof Json.Literal lit) {
            return lit.text();
        }
        return quote(String.valueOf(v));
    }

    /** Wraps already-serialised JSON so {@link #obj} embeds it verbatim. */
    public record Literal(String text) {
    }

    public static Literal lit(String alreadyJson) {
        return new Literal(alreadyJson);
    }

    /**
     * Extremely small object parser for the flat, trusted request bodies the panel sends.
     * Handles string, number and boolean values one level deep; nested structures are not supported.
     */
    public static Map<String, String> parseFlat(String json) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        int i = 0;
        int n = json.length();
        while (i < n && json.charAt(i) != '{') {
            i++;
        }
        i++;
        while (i < n) {
            while (i < n && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= n || json.charAt(i) == '}') {
                break;
            }
            if (json.charAt(i) == ',') {
                i++;
                continue;
            }
            if (json.charAt(i) != '"') {
                break;
            }
            StringBuilder key = new StringBuilder();
            i = readString(json, i, key);
            while (i < n && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ':')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            StringBuilder val = new StringBuilder();
            if (json.charAt(i) == '"') {
                i = readString(json, i, val);
            } else {
                while (i < n && json.charAt(i) != ',' && json.charAt(i) != '}') {
                    val.append(json.charAt(i++));
                }
            }
            out.put(key.toString(), val.toString().trim());
        }
        return out;
    }

    /** Reads a quoted string starting at {@code i}; appends the decoded content to {@code sink}. */
    private static int readString(String json, int i, StringBuilder sink) {
        i++; // opening quote
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char esc = json.charAt(++i);
                switch (esc) {
                    case 'n' -> sink.append('\n');
                    case 'r' -> sink.append('\r');
                    case 't' -> sink.append('\t');
                    case 'b' -> sink.append('\b');
                    case 'f' -> sink.append('\f');
                    case 'u' -> {
                        sink.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> sink.append(esc);
                }
                i++;
            } else if (c == '"') {
                return i + 1;
            } else {
                sink.append(c);
                i++;
            }
        }
        return i;
    }
}
