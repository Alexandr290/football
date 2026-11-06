package com.ballgame.soccer.bot;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves real Minecraft skins (signed texture properties) by username via the
 * public Mojang API, so packet-based NPC bots wear genuine player skins instead
 * of a flat Steve. Lookups run off the main thread and are cached for the life of
 * the server; callers receive the result through a callback once it is ready.
 */
final class BotSkins {

    /** A signed skin texture pair from the Mojang session server. */
    record Skin(String value, String signature) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private static final Pattern UUID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Pattern VALUE_PATTERN =
            Pattern.compile("\"name\"\\s*:\\s*\"textures\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SIGNATURE_PATTERN =
            Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");

    private static final Map<String, Skin> CACHE = new ConcurrentHashMap<>();

    private BotSkins() {
    }

    /** Cached skin if already resolved, otherwise {@code null}. */
    static Skin cached(String username) {
        if (username == null) {
            return null;
        }
        return CACHE.get(username.toLowerCase());
    }

    /** Seeds the cache with a skin we already hold (e.g. a live player's own profile). */
    static void prime(String username, Skin skin) {
        if (username == null || skin == null || skin.value() == null) {
            return;
        }
        CACHE.put(username.toLowerCase(), skin);
    }

    /**
     * Resolves the skin for {@code username} asynchronously. If it is already
     * cached the callback fires immediately on the calling thread; otherwise the
     * lookup happens on a background thread and the callback receives the skin
     * when ready (never the main thread — callers must re-sync if needed).
     */
    static void resolve(String username, java.util.function.Consumer<Skin> callback) {
        if (username == null || username.isBlank()) {
            return;
        }
        String key = username.toLowerCase();
        Skin hit = CACHE.get(key);
        if (hit != null) {
            callback.accept(hit);
            return;
        }
        Thread worker = new Thread(() -> {
            Skin skin = fetch(username);
            if (skin != null) {
                CACHE.put(key, skin);
                callback.accept(skin);
            }
        }, "AdvancedSoccer-SkinFetch-" + username);
        worker.setDaemon(true);
        worker.start();
    }

    private static Skin fetch(String username) {
        try {
            String idBody = get("https://api.mojang.com/users/profiles/minecraft/" + username);
            if (idBody == null) {
                return null;
            }
            Matcher idMatch = UUID_PATTERN.matcher(idBody);
            if (!idMatch.find()) {
                return null;
            }
            String uuid = idMatch.group(1);
            String profile = get("https://sessionserver.mojang.com/session/minecraft/profile/"
                    + uuid + "?unsigned=false");
            if (profile == null) {
                return null;
            }
            Matcher valueMatch = VALUE_PATTERN.matcher(profile);
            if (!valueMatch.find()) {
                return null;
            }
            String value = valueMatch.group(1);
            Matcher sigMatch = SIGNATURE_PATTERN.matcher(profile);
            String signature = sigMatch.find() ? sigMatch.group(1) : null;
            return new Skin(value, signature);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return null;
            }
            try (InputStream in = response.body()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
