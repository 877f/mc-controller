package com.mccontroler.web;

import com.mccontroler.BotSettings;
import com.mccontroler.GameThread;
import com.mccontroler.job.AcquireJob;
import com.mccontroler.job.Diagnostics;
import com.mccontroler.job.ExcavateJob;
import com.mccontroler.job.Job;
import com.mccontroler.job.JobManager;
import com.mccontroler.inv.FuelConfig;
import com.mccontroler.inv.HomeChest;
import com.mccontroler.inv.InventoryHelper;
import com.mccontroler.inv.Stations;
import com.mccontroler.job.BuildJob;
import com.mccontroler.job.DepositJob;
import com.mccontroler.job.TravelJob;
import com.mccontroler.job.TreeJob;
import com.mccontroler.place.Portals;
import com.mccontroler.place.Waypoints;
import com.mccontroler.plan.CraftPlanner;
import com.mccontroler.plan.Kits;
import com.mccontroler.plan.MaterialPolicy;
import com.mccontroler.plan.PlanStep;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** REST endpoints under /api/. */
public final class ApiHandler implements HttpHandler {

    /**
     * The catalogue changes only on resource reload, and building it walks every registered item,
     * so it is cached after the first request.
     */
    private volatile String cachedCatalogue;

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api".length());
        String method = ex.getRequestMethod();

        // Icons carry the item id in the path, so they are matched by prefix, not exactly.
        if (path.startsWith("/icon/")) {
            serveIcon(ex, path);
            return;
        }

        switch (path) {
            case "/status" -> requireGet(ex, method, this::status);
            case "/items" -> requireGet(ex, method, this::catalogue);
            case "/events" -> {
                if (method.equals("GET")) {
                    EventStream.subscribe(ex);
                } else {
                    Http.sendError(ex, 405, "use GET");
                }
            }
            case "/job" -> {
                if (method.equals("POST")) {
                    submitJob(ex);
                } else {
                    Http.sendError(ex, 405, "use POST");
                }
            }
            case "/screenshot/latest" -> {
                if (!method.equals("GET")) {
                    Http.sendError(ex, 405, "use GET");
                    return;
                }
                java.io.File shot = GameThread.get(Diagnostics::latestScreenshot);
                if (shot == null || !shot.isFile()) {
                    Http.sendError(ex, 404, "no screenshots yet");
                    return;
                }
                Http.send(ex, 200, "image/png", java.nio.file.Files.readAllBytes(shot.toPath()));
            }
            case "/screenshot/take" -> {
                if (!method.equals("POST")) {
                    Http.sendError(ex, 405, "use POST");
                    return;
                }
                GameThread.get(() -> {
                    Diagnostics.captureScreenshot();
                    return null;
                });
                Http.sendJson(ex, 200, Json.obj("taken", true));
            }
            case "/diagnostics" -> {
                if (!method.equals("GET")) {
                    Http.sendError(ex, 405, "use GET");
                    return;
                }
                List<String> lines = GameThread.get(() -> Diagnostics.snapshot(
                        JobManager.get().activeTitle(), "(requested manually)"));
                // Also push into the log so it lands in the panel next to everything else.
                for (String line : lines) {
                    EventStream.log("  " + line);
                }
                Http.sendJson(ex, 200, Json.obj("lines",
                        Json.lit("[" + lines.stream().map(Json::quote)
                                .collect(java.util.stream.Collectors.joining(",")) + "]")));
            }
            case "/plan" -> {
                if (!method.equals("GET")) {
                    Http.sendError(ex, 405, "use GET");
                    return;
                }
                Map<String, String> q = Http.query(ex);
                String item = q.get("item");
                if (item == null || item.isBlank()) {
                    Http.sendError(ex, 400, "missing 'item'");
                    return;
                }
                int count = 1;
                try {
                    count = Math.max(1, Integer.parseInt(q.getOrDefault("count", "1")));
                } catch (NumberFormatException ignored) {
                    // fall back to 1
                }
                Http.sendJson(ex, 200, planFor(item, count));
            }
            case "/kits" -> {
                switch (method) {
                    case "GET" -> {
                        List<String> entries = new ArrayList<>();
                        for (var kit : Kits.all().entrySet()) {
                            entries.add(Json.obj(
                                    "name", kit.getKey(),
                                    "items", Kits.format(kit.getValue()),
                                    "count", kit.getValue().size()));
                        }
                        Http.sendJson(ex, 200, "{\"kits\":["
                                + String.join(",", entries) + "]}");
                    }
                    case "POST" -> {
                        Map<String, String> body = Json.parseFlat(Http.readBody(ex));
                        String name = body.get("name");
                        String items = body.get("items");
                        if (name == null || name.isBlank() || items == null || items.isBlank()) {
                            Http.sendError(ex, 400, "need 'name' and 'items'");
                            return;
                        }
                        List<Kits.Entry> parsed = Kits.parse(items);
                        if (parsed.isEmpty()) {
                            Http.sendError(ex, 400, "no usable entries in 'items'");
                            return;
                        }
                        Kits.save(name, parsed);
                        Http.sendJson(ex, 201, Json.obj("saved", name, "count", parsed.size()));
                    }
                    default -> Http.sendError(ex, 405, "use GET or POST");
                }
            }
            case "/kits/delete" -> {
                if (!method.equals("POST")) {
                    Http.sendError(ex, 405, "use POST");
                    return;
                }
                String name = Json.parseFlat(Http.readBody(ex)).get("name");
                boolean removed = name != null && Kits.delete(name);
                Http.sendJson(ex, removed ? 200 : 404, Json.obj("deleted", removed));
            }
            case "/job/batch" -> {
                if (!method.equals("POST")) {
                    Http.sendError(ex, 405, "use POST");
                    return;
                }
                Map<String, String> body = Json.parseFlat(Http.readBody(ex));
                String spec = body.get("items");
                // A kit name is an alternative to spelling the items out.
                if ((spec == null || spec.isBlank()) && body.get("kit") != null) {
                    List<Kits.Entry> kit = Kits.get(body.get("kit"));
                    if (kit == null) {
                        Http.sendError(ex, 404, "no kit called '" + body.get("kit") + "'");
                        return;
                    }
                    spec = Kits.format(kit);
                }
                List<Kits.Entry> entries = Kits.parse(spec);
                if (entries.isEmpty()) {
                    Http.sendError(ex, 400, "nothing to queue");
                    return;
                }
                if (!GameThread.get(() -> Minecraft.getInstance().player != null)) {
                    Http.sendError(ex, 409, "not in a world");
                    return;
                }
                // Each line is planned separately, so a later item can use what an earlier
                // one produced — and one failure does not discard the rest of the list.
                for (Kits.Entry entry : entries) {
                    JobManager.get().submit(new AcquireJob(entry.itemId(), entry.count()));
                }
                EventStream.log("queued a list of " + entries.size() + " item(s)", "ok");
                Http.sendJson(ex, 202, Json.obj("queued", entries.size()));
            }
            case "/stations" -> {
                switch (method) {
                    case "GET" -> {
                        List<String> entries = new ArrayList<>();
                        for (var station : Stations.all().entrySet()) {
                            BlockPos at = station.getValue();
                            entries.add(Json.quote(station.getKey()) + ":"
                                    + Json.obj("x", at.getX(), "y", at.getY(), "z", at.getZ()));
                        }
                        Http.sendJson(ex, 200, "{" + String.join(",", entries) + "}");
                    }
                    case "POST" -> {
                        Map<String, String> body = Json.parseFlat(Http.readBody(ex));
                        String kind = body.getOrDefault("kind", "");
                        if (!kind.equals(Stations.TABLE) && !kind.equals(Stations.FURNACE)) {
                            Http.sendError(ex, 400, "'kind' must be table or furnace");
                            return;
                        }
                        // Clearing removes the restriction and lets the bot place its own again.
                        if (Boolean.parseBoolean(body.getOrDefault("clear", "false"))) {
                            boolean cleared = GameThread.get(() -> Stations.clear(kind));
                            EventStream.log("assigned " + kind + " cleared", "ok");
                            Http.sendJson(ex, 200, Json.obj("cleared", cleared));
                            return;
                        }
                        String found = GameThread.get(() -> {
                            BlockPos at = Stations.findNearby(kind, 6);
                            if (at == null) {
                                return "";
                            }
                            Stations.set(kind, at);
                            return at.getX() + "," + at.getY() + "," + at.getZ();
                        });
                        if (found.isEmpty()) {
                            Http.sendError(ex, 404, "no " + kind + " within 6 blocks of the bot");
                        } else {
                            EventStream.log("assigned " + kind + " at " + found
                                    + " — the bot will use only this one", "ok");
                            Http.sendJson(ex, 200, Json.obj("kind", kind, "at", found));
                        }
                    }
                    default -> Http.sendError(ex, 405, "use GET or POST");
                }
            }
            case "/chest" -> {
                switch (method) {
                    case "GET" -> {
                        BlockPos at = HomeChest.position();
                        Http.sendJson(ex, 200, at == null
                                ? Json.obj("set", false)
                                : Json.obj("set", true, "x", at.getX(), "y", at.getY(),
                                "z", at.getZ(), "dimension", HomeChest.dimension()));
                    }
                    case "POST" -> {
                        // Finds the nearest chest rather than asking for coordinates.
                        String result = GameThread.get(() -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc.player == null || mc.level == null) {
                                return null;
                            }
                            BlockPos found = HomeChest.findNearby(6);
                            if (found == null) {
                                return "";
                            }
                            HomeChest.set(found, mc.level.dimension().identifier().toString());
                            return found.getX() + "," + found.getY() + "," + found.getZ();
                        });
                        if (result == null) {
                            Http.sendError(ex, 409, "not in a world");
                        } else if (result.isEmpty()) {
                            Http.sendError(ex, 404, "no chest within 6 blocks of the bot");
                        } else {
                            EventStream.log("home chest set to " + result, "ok");
                            Http.sendJson(ex, 200, Json.obj("set", true, "at", result));
                        }
                    }
                    default -> Http.sendError(ex, 405, "use GET or POST");
                }
            }
            case "/inventory/drop" -> {
                if (!method.equals("POST")) {
                    Http.sendError(ex, 405, "use POST");
                    return;
                }
                Map<String, String> body = Json.parseFlat(Http.readBody(ex));
                List<String> ids = splitCsv(body.get("items"));
                if (ids.isEmpty()) {
                    ids = InventoryHelper.DEFAULT_JUNK;
                }
                List<String> finalIds = ids;
                int dropped = GameThread.get(() -> InventoryHelper.dropMatching(finalIds));
                int free = GameThread.get(InventoryHelper::freeSlots);
                EventStream.log("dropped " + dropped + " stack(s) of junk — "
                        + free + " free slots", "ok");
                Http.sendJson(ex, 200, Json.obj("dropped", dropped, "freeSlots", free));
            }
            case "/settings" -> {
                switch (method) {
                    case "GET" -> {
                        List<String> entries = new ArrayList<>();
                        for (var toggle : BotSettings.all().entrySet()) {
                            entries.add(Json.quote(toggle.getKey()) + ":" + toggle.getValue());
                        }
                        Http.sendJson(ex, 200, "{" + String.join(",", entries) + "}");
                    }
                    case "POST" -> {
                        Map<String, String> body = Json.parseFlat(Http.readBody(ex));
                        for (var entry : body.entrySet()) {
                            BotSettings.set(entry.getKey(), Boolean.parseBoolean(entry.getValue()));
                        }
                        EventStream.log("settings updated", "ok");
                        Http.sendJson(ex, 200, Json.obj("saved", true));
                    }
                    default -> Http.sendError(ex, 405, "use GET or POST");
                }
            }
            case "/materials" -> {
                switch (method) {
                    case "GET" -> Http.sendJson(ex, 200, Json.obj(
                            "preferred", String.join(",", MaterialPolicy.preferred()),
                            "banned", String.join(",", MaterialPolicy.banned())));
                    case "POST" -> {
                        Map<String, String> body = Json.parseFlat(Http.readBody(ex));
                        MaterialPolicy.save(splitCsv(body.get("preferred")),
                                splitCsv(body.get("banned")));
                        EventStream.log("material preferences saved", "ok");
                        Http.sendJson(ex, 200, Json.obj("saved", true));
                    }
                    default -> Http.sendError(ex, 405, "use GET or POST");
                }
            }
            case "/fuels" -> {
                switch (method) {
                    case "GET" -> Http.sendJson(ex, 200, fuels());
                    case "POST" -> {
                        Map<String, String> body = Json.parseFlat(Http.readBody(ex));
                        List<String> blockedIds = splitCsv(body.get("blocked"));
                        List<String> preferredIds = splitCsv(body.get("preferred"));
                        GameThread.get(() -> {
                            FuelConfig.save(blockedIds, preferredIds);
                            return null;
                        });
                        Http.sendJson(ex, 200, Json.obj("saved", true,
                                "blocked", blockedIds.size()));
                    }
                    default -> Http.sendError(ex, 405, "use GET or POST");
                }
            }
            case "/waypoints" -> {
                switch (method) {
                    case "GET" -> Http.sendJson(ex, 200, waypoints());
                    case "POST" -> saveWaypoint(ex);
                    default -> Http.sendError(ex, 405, "use GET or POST");
                }
            }
            case "/waypoints/delete" -> {
                if (!method.equals("POST")) {
                    Http.sendError(ex, 405, "use POST");
                    return;
                }
                String name = Json.parseFlat(Http.readBody(ex)).get("name");
                if (name == null || name.isBlank()) {
                    Http.sendError(ex, 400, "missing 'name'");
                    return;
                }
                boolean removed = Waypoints.delete(name);
                Http.sendJson(ex, removed ? 200 : 404, Json.obj("deleted", removed));
            }
            case "/queue" -> {
                if (!method.equals("GET")) {
                    Http.sendError(ex, 405, "use GET");
                    return;
                }
                String json = GameThread.get(() -> {
                    JobManager jobs = JobManager.get();
                    List<String> waiting = new ArrayList<>();
                    for (String title : jobs.pendingTitles()) {
                        waiting.add(Json.quote(title));
                    }
                    return Json.obj(
                            "active", jobs.isBusy() ? jobs.activeTitle() : null,
                            "waiting", Json.lit("[" + String.join(",", waiting) + "]"));
                });
                Http.sendJson(ex, 200, json);
            }
            case "/queue/remove" -> {
                if (!method.equals("POST")) {
                    Http.sendError(ex, 405, "use POST");
                    return;
                }
                Map<String, String> body = Json.parseFlat(Http.readBody(ex));
                int index;
                try {
                    index = Integer.parseInt(body.getOrDefault("index", "-1"));
                } catch (NumberFormatException e) {
                    Http.sendError(ex, 400, "'index' must be a number");
                    return;
                }
                boolean removed = GameThread.get(() -> JobManager.get().removePending(index));
                Http.sendJson(ex, removed ? 200 : 404, Json.obj("removed", removed));
            }
            case "/job/stop" -> {
                if (method.equals("POST")) {
                    JobManager.get().stopAll();
                    Http.sendJson(ex, 200, Json.obj("stopped", true));
                } else {
                    Http.sendError(ex, 405, "use POST");
                }
            }
            default -> Http.sendError(ex, 404, "no such endpoint: " + path);
        }
    }

    /** Turns a panel request into a queued {@link Job}. */
    private void submitJob(HttpExchange ex) throws IOException {
        Map<String, String> body = Json.parseFlat(Http.readBody(ex));
        String type = body.get("type");
        if (type == null) {
            Http.sendError(ex, 400, "missing 'type'");
            return;
        }

        boolean inWorld = GameThread.get(() -> Minecraft.getInstance().player != null);
        if (!inWorld) {
            Http.sendError(ex, 409, "not in a world");
            return;
        }

        Job job;
        try {
            job = build(type, body);
        } catch (IllegalArgumentException bad) {
            Http.sendError(ex, 400, bad.getMessage());
            return;
        }

        JobManager.get().submit(job);
        Http.sendJson(ex, 202, Json.obj("queued", true, "title", job.title()));
    }

    private Job build(String type, Map<String, String> body) {
        switch (type) {
            case "acquire" -> {
                String item = required(body, "item");
                return new AcquireJob(item, positiveInt(body, "count", 1));
            }
            case "tree" -> {
                String logId = required(body, "log");
                TreeJob.Mode mode = "count".equals(body.get("mode"))
                        ? TreeJob.Mode.COUNT
                        : TreeJob.Mode.LOOP;
                return new TreeJob(logId, mode, positiveInt(body, "count", 128));
            }
            case "excavate" -> {
                BlockPos a = new BlockPos(intOf(body, "ax"), intOf(body, "ay"), intOf(body, "az"));
                BlockPos b = new BlockPos(intOf(body, "bx"), intOf(body, "by"), intOf(body, "bz"));
                return new ExcavateJob(a, b);
            }
            case "surface" -> {
                return TravelJob.toSurface();
            }
            case "deposit" -> {
                return new DepositJob();
            }
            case "portal" -> {
                BlockPos portal = Portals.findNearest();
                if (portal == null) {
                    throw new IllegalArgumentException(
                            "no nether portal in the loaded world — fly or walk nearer to one,"
                            + " or save it as a place");
                }
                // Stand beside the frame rather than inside it, so arriving does not teleport
                // the bot through before you have decided to go.
                return TravelJob.to(portal.above(), "the nether portal");
            }
            case "build" -> {
                BlockPos a = new BlockPos(intOf(body, "ax"), intOf(body, "ay"), intOf(body, "az"));
                BlockPos b = new BlockPos(intOf(body, "bx"), intOf(body, "by"), intOf(body, "bz"));
                String block = required(body, "block");
                BuildJob.Shape shape;
                try {
                    shape = BuildJob.Shape.valueOf(
                            body.getOrDefault("shape", "fill").toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException bad) {
                    throw new IllegalArgumentException("shape must be fill, hollow or walls");
                }
                return new BuildJob(a, b, block, shape);
            }
            case "travel" -> {
                // Either a saved waypoint by name, or raw coordinates.
                String name = body.get("name");
                if (name != null && !name.isBlank()) {
                    Waypoints.Waypoint wp = Waypoints.byName(name);
                    if (wp == null) {
                        throw new IllegalArgumentException("no waypoint called '" + name + "'");
                    }
                    return TravelJob.to(new BlockPos(wp.x(), wp.y(), wp.z()), wp.name());
                }
                BlockPos pos = new BlockPos(intOf(body, "x"), intOf(body, "y"), intOf(body, "z"));
                return TravelJob.to(pos, pos.toShortString());
            }
            default -> throw new IllegalArgumentException("unknown job type: " + type);
        }
    }

    /** Resolves the full mine/smelt/craft chain for an item. */
    private String planFor(String itemId, int count) {
        return GameThread.get(() -> {
            try {
                List<PlanStep> steps = CraftPlanner.plan(itemId, count);
                List<String> json = new ArrayList<>();
                for (PlanStep s : steps) {
                    json.add(Json.obj(
                            "kind", s.kind().name().toLowerCase(java.util.Locale.ROOT),
                            "item", s.itemId(),
                            "name", s.displayName(),
                            "count", s.count(),
                            "text", s.toString()));
                }
                return Json.obj("ok", true, "steps", Json.lit("[" + String.join(",", json) + "]"));
            } catch (CraftPlanner.NoRouteException e) {
                return Json.obj("ok", false, "error", e.getMessage());
            }
        });
    }

    /** {@code /api/icon/<namespace>/<path>.png} — the item's texture from the resource packs. */
    private void serveIcon(HttpExchange ex, String path) throws IOException {
        String spec = path.substring("/icon/".length());
        if (!spec.endsWith(".png")) {
            Http.sendError(ex, 400, "icons are .png");
            return;
        }
        spec = spec.substring(0, spec.length() - 4);

        int slash = spec.indexOf('/');
        if (slash <= 0 || slash == spec.length() - 1) {
            Http.sendError(ex, 400, "expected /icon/<namespace>/<item>.png");
            return;
        }

        byte[] png = Icons.get(spec.substring(0, slash), spec.substring(slash + 1));
        if (png == null) {
            Http.sendError(ex, 404, "no texture for " + spec);
            return;
        }

        // Textures are stable for the session, so let the browser keep them.
        ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.sendResponseHeaders(200, png.length);
        try (var out = ex.getResponseBody()) {
            out.write(png);
        }
    }

    /** Everything the game treats as fuel, with the player's allow/block choices applied. */
    private String fuels() {
        return GameThread.get(() -> {
            List<String> entries = new ArrayList<>();
            for (FuelConfig.Fuel fuel : FuelConfig.catalogue()) {
                entries.add(Json.obj(
                        "id", fuel.id(),
                        "name", fuel.name(),
                        // Burn time in items smelted: one item takes 200 ticks.
                        "smelts", Math.round(fuel.burnTicks() / 200.0 * 10) / 10.0,
                        "have", fuel.have(),
                        "blocked", fuel.blocked()));
            }
            return "{\"fuels\":[" + String.join(",", entries) + "]}";
        });
    }

    private static List<String> splitCsv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** All saved waypoints. */
    private String waypoints() {
        List<String> entries = new ArrayList<>();
        for (Waypoints.Waypoint w : Waypoints.all()) {
            entries.add(Json.obj(
                    "name", w.name(),
                    "dimension", w.dimension(),
                    "x", w.x(), "y", w.y(), "z", w.z()));
        }
        return "{\"waypoints\":[" + String.join(",", entries) + "]}";
    }

    /** Saves the player's current position under a name. */
    private void saveWaypoint(HttpExchange ex) throws IOException {
        Map<String, String> body = Json.parseFlat(Http.readBody(ex));
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            Http.sendError(ex, 400, "missing 'name'");
            return;
        }

        // Coordinates are read on the client thread so they match what the player sees.
        String[] captured = GameThread.get(() -> {
            Minecraft mc = Minecraft.getInstance();
            var player = mc.player;
            if (player == null || mc.level == null) {
                return null;
            }
            return new String[]{
                    mc.level.dimension().identifier().toString(),
                    String.valueOf((int) Math.floor(player.getX())),
                    String.valueOf((int) Math.floor(player.getY())),
                    String.valueOf((int) Math.floor(player.getZ()))};
        });

        if (captured == null) {
            Http.sendError(ex, 409, "not in a world");
            return;
        }

        Waypoints.Waypoint wp = Waypoints.save(name, captured[0],
                Integer.parseInt(captured[1]), Integer.parseInt(captured[2]),
                Integer.parseInt(captured[3]));
        Http.sendJson(ex, 201, Json.obj(
                "name", wp.name(), "dimension", wp.dimension(),
                "x", wp.x(), "y", wp.y(), "z", wp.z()));
    }

    private static String required(Map<String, String> body, String key) {
        String v = body.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return v;
    }

    private static int intOf(Map<String, String> body, String key) {
        try {
            return Integer.parseInt(required(body, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' must be a whole number");
        }
    }

    private static int positiveInt(Map<String, String> body, String key, int fallback) {
        String raw = body.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' must be a whole number");
        }
    }

    private interface Producer {
        String produce();
    }

    private void requireGet(HttpExchange ex, String method, Producer body) throws IOException {
        if (!method.equals("GET")) {
            Http.sendError(ex, 405, "use GET");
            return;
        }
        Http.sendJson(ex, 200, body.produce());
    }

    /** Current client state: whether we are in a world, and where the player is. */
    private String status() {
        return GameThread.get(() -> {
            Minecraft mc = Minecraft.getInstance();
            var player = mc.player;
            if (player == null || mc.level == null) {
                return Json.obj("inWorld", false);
            }
            return Json.obj(
                    "inWorld", true,
                    "player", player.getName().getString(),
                    "dimension", mc.level.dimension().identifier().toString(),
                    "x", (int) Math.floor(player.getX()),
                    "y", (int) Math.floor(player.getY()),
                    "z", (int) Math.floor(player.getZ()),
                    "health", Math.round(player.getHealth()));
        });
    }

    /** Every registered item, with a flag for the ones that place a block. */
    private String catalogue() {
        String cached = cachedCatalogue;
        if (cached != null) {
            return cached;
        }
        String built = GameThread.get(() -> {
            List<String> entries = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (id == null) {
                    continue;
                }
                String name = Component.translatable(item.getDescriptionId()).getString();
                entries.add(Json.obj(
                        "id", id.toString(),
                        "name", name,
                        "block", item instanceof BlockItem));
            }
            return "{\"items\":[" + String.join(",", entries) + "]}";
        });
        cachedCatalogue = built;
        return built;
    }
}
