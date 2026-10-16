package dev.pandor.regionium;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pandor.regionium.core.RegioniumRegionizer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal loopback-only HTTP viewer for live Regionium topology. */
public final class DebugOverlayServer {
    private static final AtomicLong SERVER_TICKS = new AtomicLong();
    private static volatile byte[] state = "{\"dimensions\":[]}".getBytes(StandardCharsets.UTF_8);

    private static HttpServer http;
    private static ExecutorService requests;

    private DebugOverlayServer() {}

    public static void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (SERVER_TICKS.incrementAndGet() % 5 == 0) {
                state = buildState(server).getBytes(StandardCharsets.UTF_8);
            }
        });
    }

    public static synchronized void start(MinecraftServer server) {
        if (http != null) {
            return;
        }

        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 3434), 0);
            requests = Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "Regionium-Debug-HTTP");
                thread.setDaemon(true);
                return thread;
            });
            http.setExecutor(requests);
            http.createContext("/", DebugOverlayServer::handle);
            state = buildState(server).getBytes(StandardCharsets.UTF_8);
            http.start();
            Regionium.LOGGER.info("Regionium debug overlay available at http://127.0.0.1:{}/", http.getAddress().getPort());
        } catch (IOException exception) {
            http = null;
            if (requests != null) {
                requests.shutdownNow();
                requests = null;
            }
            Regionium.LOGGER.warn("Could not start the Regionium debug overlay on loopback", exception);
        }
    }

    public static synchronized void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (requests != null) {
            requests.shutdownNow();
            requests = null;
        }
        state = "{\"dimensions\":[]}".getBytes(StandardCharsets.UTF_8);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/state".equals(path)) {
            send(exchange, 200, "application/json; charset=utf-8", state);
            return;
        }
        String resourcePath;
        String contentType;
        switch (path) {
            case "/", "/index.html", "/site/index.html" -> {
                resourcePath = "/site/index.html";
                contentType = "text/html; charset=utf-8";
            }
            case "/style.css", "/site/style.css" -> {
                resourcePath = "/site/style.css";
                contentType = "text/css; charset=utf-8";
            }
            case "/script.js", "/site/script.js" -> {
                resourcePath = "/site/script.js";
                contentType = "text/javascript; charset=utf-8";
            }
            default -> {
                sendText(exchange, 404, "Not found");
                return;
            }
        }

        try (InputStream input = DebugOverlayServer.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                sendText(exchange, 404, "Debug overlay resource not found.");
                return;
            }
            send(exchange, 200, contentType, input.readAllBytes());
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String buildState(MinecraftServer server) {
        RegioniumRegionizer regionizer = Regionium.scheduler().regionizer();
        StringBuilder json = new StringBuilder(8192);
        json.append("{\"gameTime\":").append(server.overworld().getGameTime()).append(",\"dimensions\":[");
        boolean firstLevel = true;

        for (ServerLevel level : server.getAllLevels()) {
            if (!firstLevel) json.append(',');
            firstLevel = false;
            json.append("{\"id\":").append(quote(level.dimension().toString())).append(",\"chunks\":[");
            List<RegioniumRegionizer.DebugChunk> chunks = regionizer.debugChunks(level);
            Map<Long, Long> chunkOwners = new LinkedHashMap<>();
            boolean firstChunk = true;
            Map<String, Section> sections = new LinkedHashMap<>();
            for (RegioniumRegionizer.DebugChunk chunk : chunks) {
                if (!firstChunk) json.append(',');
                firstChunk = false;
                json.append("{\"x\":").append(chunk.x())
                    .append(",\"z\":").append(chunk.z())
                    .append(",\"sectionX\":").append(chunk.sectionX())
                    .append(",\"sectionZ\":").append(chunk.sectionZ())
                    .append(",\"regionId\":").append(chunk.regionId() == null ? "null" : chunk.regionId())
                    .append(",\"ticking\":").append(chunk.ticking())
                    .append(",\"entityTicking\":").append(chunk.entityTicking()).append('}');
                if (chunk.regionId() != null) {
                    chunkOwners.put(net.minecraft.world.level.ChunkPos.pack(chunk.x(), chunk.z()), chunk.regionId());
                }

                String sectionKey = chunk.sectionX() + "," + chunk.sectionZ();
                Section section = sections.computeIfAbsent(sectionKey, ignored ->
                    new Section(chunk.sectionX(), chunk.sectionZ()));
                section.chunkCount++;
                if (chunk.ticking()) section.tickingCount++;
                if (chunk.entityTicking()) section.entityTickingCount++;
                if (chunk.regionId() != null) section.regionIds.add(chunk.regionId());
            }

            json.append("],\"sections\":[");
            boolean firstSection = true;
            List<Section> sortedSections = new ArrayList<>(sections.values());
            sortedSections.sort(Comparator.comparingInt((Section section) -> section.x).thenComparingInt(section -> section.z));
            for (Section section : sortedSections) {
                if (!firstSection) json.append(',');
                firstSection = false;
                json.append("{\"x\":").append(section.x)
                    .append(",\"z\":").append(section.z)
                    .append(",\"chunkCount\":").append(section.chunkCount)
                    .append(",\"tickingCount\":").append(section.tickingCount)
                    .append(",\"entityTickingCount\":").append(section.entityTickingCount)
                    .append(",\"regionIds\":[");
                boolean firstRegion = true;
                for (Long regionId : section.regionIds) {
                    if (!firstRegion) json.append(',');
                    firstRegion = false;
                    json.append(regionId);
                }
                json.append("]}");
            }

            json.append("],\"players\":[");
            boolean firstPlayer = true;
            List<ServerPlayer> players = level.players().stream()
                .sorted(Comparator.comparing(player -> player.getGameProfile().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
            for (ServerPlayer player : players) {
                if (!firstPlayer) json.append(',');
                firstPlayer = false;
                int chunkX = player.blockPosition().getX() >> 4;
                int chunkZ = player.blockPosition().getZ() >> 4;
                Long regionId = chunkOwners.get(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
                json.append("{\"name\":").append(quote(player.getGameProfile().name()))
                    .append(",\"uuid\":").append(quote(player.getStringUUID()))
                    .append(",\"x\":").append(player.getX())
                    .append(",\"y\":").append(player.getY())
                    .append(",\"z\":").append(player.getZ())
                    .append(",\"chunkX\":").append(chunkX)
                    .append(",\"chunkZ\":").append(chunkZ)
                    .append(",\"regionId\":").append(regionId == null ? "null" : regionId).append('}');
            }
            json.append("]}");
        }

        return json.append("]}").toString();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    private static final class Section {
        private final int x;
        private final int z;
        private int chunkCount;
        private int tickingCount;
        private int entityTickingCount;
        private final java.util.Set<Long> regionIds = new java.util.TreeSet<>();

        private Section(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}
