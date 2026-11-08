package com.ballgame.soccer.crowd;

import com.ballgame.soccer.SoccerPlugin;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds one required AdvancedSoccer pack: stadium audio plus the FIFA-style UI.
 */
public final class CrowdResourcePack {

    private static final String[] SOUND_FILES = {
            "ambience_low", "ambience_medium", "ambience_high", "chant",
            "goal_cheer", "applause", "gasp", "stinger", "kickoff"
    };

    private static final String PACK_PATH = "/advanced-soccer-pack.zip";
    private static final String INSTALL_NAME = "AdvancedSoccer-UI.zip";

    private final SoccerPlugin plugin;
    private final Set<UUID> accepted = ConcurrentHashMap.newKeySet();

    private File packFile;
    private File installCopy;
    private byte[] sha1Hash;
    private String configuredUrl;
    private int activePort;
    private HttpServer httpServer;

    public CrowdResourcePack(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        accepted.clear();
        stopHttpServer();
        configuredUrl = null;
        activePort = 0;
        installCopy = null;

        try {
            packFile = buildPack();
            installCopy = copyForManualInstall(packFile);
            sha1Hash = sha1(packFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not build crowd resource pack", ex);
            return;
        }

        CrowdPackMode mode = plugin.settings().crowdPackMode;
        plugin.getLogger().info("Crowd pack mode: " + mode.name().toLowerCase());
        plugin.getLogger().info("Manual install file: " + installCopy.getAbsolutePath());

        switch (mode) {
            case MANUAL -> {
                plugin.getLogger().warning("Manual crowd-pack mode is overridden because "
                        + "the AdvancedSoccer UI pack is required.");
                startRequiredDistribution();
            }
            case URL -> startUrlMode();
            case DOWNLOAD -> startDownloadMode();
            default -> {
            }
        }
    }

    public void stop() {
        stopHttpServer();
    }

    public void offer(Player player) {
        if (!isReady()) {
            return;
        }

        String url = downloadUrlFor(player);
        if (url == null || url.isBlank()) {
            plugin.getLogger().warning("No crowd pack URL available for " + player.getName());
            return;
        }

        plugin.getLogger().info("Offering required AdvancedSoccer UI pack to "
                + player.getName() + ": " + url);
        byte[] hash = plugin.settings().crowdPackUseHash && sha1Hash != null
                ? sha1Hash : new byte[20];
        try {
            // Spigot 1.20+ required-pack overload. Reflection keeps older
            // compatible servers able to load the plugin.
            Player.class.getMethod("setResourcePack",
                            String.class, byte[].class, String.class, boolean.class)
                    .invoke(player, url, hash,
                            "Пакет звуков стадиона AdvancedSoccer. Игра доступна и без него.",
                            false);
        } catch (ReflectiveOperationException ignored) {
            if (plugin.settings().crowdPackUseHash && sha1Hash != null) {
                player.setResourcePack(url, sha1Hash);
            } else {
                player.setResourcePack(url);
            }
        }
    }

    public boolean isReady() {
        return packFile != null && packFile.isFile();
    }

    public boolean useRealSounds() {
        return plugin.settings().crowdRealSounds;
    }

    public boolean canPlay(Player player) {
        return canPlayCustom(player);
    }

    /**
     * Whether to layer real stadium recordings on top of vanilla fallbacks.
     * Requires the client to have the crowd resource pack (manual install or server download).
     */
    public boolean canPlayCustom(Player player) {
        if (!useRealSounds() || !isReady()) {
            return false;
        }
        CrowdPackMode mode = plugin.settings().crowdPackMode;
        if (mode == CrowdPackMode.MANUAL) {
            // Server cannot detect a locally installed pack; players opt in via /soccer crowdpack ack
            // or by accepting a server-offered download.
            return accepted.contains(player.getUniqueId());
        }
        return accepted.contains(player.getUniqueId());
    }

    public CrowdPackMode mode() {
        return plugin.settings().crowdPackMode;
    }

    public void markAccepted(Player player) {
        accepted.add(player.getUniqueId());
    }

    public boolean hasAccepted(Player player) {
        return player != null && accepted.contains(player.getUniqueId());
    }

    public void markDeclined(Player player) {
        accepted.remove(player.getUniqueId());
    }

    public void forget(Player player) {
        accepted.remove(player.getUniqueId());
    }

    public String downloadUrlFor(Player player) {
        if (configuredUrl != null) {
            return configuredUrl;
        }
        if (activePort <= 0) {
            return null;
        }
        String host = CrowdHostResolver.resolve(
                player,
                plugin.settings().crowdPackPublicHost,
                Bukkit.getServer().getIp(),
                plugin.getLogger());
        return buildHttpUrl(host, activePort);
    }

    public String sampleDownloadUrl(Player player) {
        return downloadUrlFor(player);
    }

    public List<String> hostCandidates(Player player) {
        return CrowdHostResolver.candidates(
                player,
                plugin.settings().crowdPackPublicHost,
                Bukkit.getServer().getIp(),
                plugin.getLogger());
    }

    public File packFile() {
        return packFile;
    }

    public File installFile() {
        return installCopy != null ? installCopy : packFile;
    }

    public int activePort() {
        return activePort;
    }

    private void startUrlMode() {
        String externalUrl = plugin.settings().crowdPackUrl;
        if (externalUrl == null || externalUrl.isBlank()) {
            plugin.getLogger().warning("Resource-pack URL is empty; serving the required UI pack locally.");
            startHttpServer();
            return;
        }
        configuredUrl = externalUrl.trim();
        plugin.getLogger().info("Crowd resource pack URL: " + configuredUrl);
    }

    private void startDownloadMode() {
        String externalUrl = plugin.settings().crowdPackUrl;
        if (externalUrl != null && !externalUrl.isBlank()) {
            configuredUrl = externalUrl.trim();
            plugin.getLogger().info("Crowd resource pack URL: " + configuredUrl);
            return;
        }
        startHttpServer();
    }

    private void startRequiredDistribution() {
        String externalUrl = plugin.settings().crowdPackUrl;
        if (externalUrl != null && !externalUrl.isBlank()) {
            configuredUrl = externalUrl.trim();
            plugin.getLogger().info("Required UI pack URL: " + configuredUrl);
            return;
        }
        startHttpServer();
    }

    private void startHttpServer() {
        int preferred = plugin.settings().crowdPackPort;
        int attempts = 10;
        for (int i = 0; i < attempts; i++) {
            int port = preferred + i;
            try {
                httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                httpServer.createContext(PACK_PATH, this::servePack);
                httpServer.setExecutor(null);
                httpServer.start();
                activePort = port;
                plugin.getLogger().info("Crowd pack HTTP server on 0.0.0.0:" + port + PACK_PATH);
                if (port != preferred) {
                    plugin.getLogger().warning("Port " + preferred + " busy; using " + port);
                }
                logDownloadHints();
                return;
            } catch (IOException ex) {
                plugin.getLogger().fine("Crowd pack port " + port + " unavailable: " + ex.getMessage());
            }
        }
        plugin.getLogger().log(Level.WARNING, "Could not start crowd pack HTTP server.");
    }

    private void logDownloadHints() {
        String lan = CrowdHostResolver.candidates(null, plugin.settings().crowdPackPublicHost,
                Bukkit.getServer().getIp(), plugin.getLogger()).stream()
                .filter(host -> !"127.0.0.1".equals(host))
                .findFirst()
                .orElse("YOUR_SERVER_IP");
        plugin.getLogger().info("Example download URL: http://" + lan + ":" + activePort + PACK_PATH);
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        activePort = 0;
    }

    private void servePack(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        byte[] data = Files.readAllBytes(packFile.toPath());
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"ballgame-crowd-pack.zip\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");

        if ("HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    private static String buildHttpUrl(String host, int port) {
        if (host.contains(":")) {
            return "http://[" + host + "]:" + port + PACK_PATH;
        }
        return "http://" + host + ":" + port + PACK_PATH;
    }

    private File copyForManualInstall(File source) throws IOException {
        File target = new File(plugin.getDataFolder(), INSTALL_NAME);
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private File buildPack() throws IOException {
        File out = new File(plugin.getDataFolder(), "advanced-soccer-pack.zip");
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out.toPath()))) {
            putResource(zip, "crowd-sounds/pack.mcmeta", "pack.mcmeta");
            putResource(zip, "ui-pack/assets/minecraft/font/default.json",
                    "assets/minecraft/font/default.json");
            putResource(zip, "ui-pack/assets/minecraft/models/item/paper.json",
                    "assets/minecraft/models/item/paper.json");
            putResource(zip, "ui-pack/assets/ballgame/textures/font/fifa_dashboard.png",
                    "assets/ballgame/textures/font/fifa_dashboard.png");
            putResource(zip, "ui-pack/assets/ballgame/textures/item/ui_icons.png",
                    "assets/ballgame/textures/item/ui_icons.png");
            putResource(zip, "ui-pack/assets/ballgame/textures/item/transparent.png",
                    "assets/ballgame/textures/item/transparent.png");
            for (String name : new String[] {"button", "blank"}) {
                putResource(zip, "ui-pack/assets/ballgame/models/item/" + name + ".json",
                        "assets/ballgame/models/item/" + name + ".json");
            }
            if (plugin.settings().crowdRealSounds) {
                putResource(zip, "crowd-sounds/assets/ballgame/sounds.json",
                        "assets/ballgame/sounds.json");
                for (String name : SOUND_FILES) {
                    putResource(zip,
                            "crowd-sounds/ogg/" + name + ".ogg",
                            "assets/ballgame/sounds/crowd/" + name + ".ogg");
                }
            }
        }
        return out;
    }

    private void putResource(ZipOutputStream zip, String resourcePath, String zipPath) throws IOException {
        InputStream in = plugin.getResource(resourcePath);
        if (in == null) {
            throw new IOException("Missing bundled crowd resource: " + resourcePath);
        }
        zip.putNextEntry(new ZipEntry(zipPath));
        in.transferTo(zip);
        zip.closeEntry();
        in.close();
    }

    private static byte[] sha1(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(Files.readAllBytes(file.toPath()));
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 unavailable", ex);
        }
    }
}
