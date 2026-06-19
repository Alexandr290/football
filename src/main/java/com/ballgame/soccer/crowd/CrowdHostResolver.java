package com.ballgame.soccer.crowd;

import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves a host name or IP that Minecraft clients can use to download the crowd pack.
 */
final class CrowdHostResolver {

    private CrowdHostResolver() {
    }

    static String resolve(Player player, String configuredHost, String serverPropertyIp, Logger logger) {
        if (configuredHost != null && !configuredHost.isBlank()) {
            return configuredHost.trim();
        }

        if (player != null) {
            String fromConnection = connectionLocalHost(player, logger);
            if (isUsableHost(fromConnection)) {
                return fromConnection;
            }
        }

        if (serverPropertyIp != null && !serverPropertyIp.isBlank() && isUsableHost(serverPropertyIp.trim())) {
            return serverPropertyIp.trim();
        }

        String lan = firstLanIpv4();
        if (isUsableHost(lan)) {
            return lan;
        }

        logger.warning("Could not detect a reachable IP for the crowd pack. "
                + "Set crowd.resource-pack.public-host in config.yml (e.g. your LAN IP or server domain).");
        return "127.0.0.1";
    }

    static List<String> candidates(Player player, String configuredHost, String serverPropertyIp, Logger logger) {
        List<String> out = new ArrayList<>();
        addCandidate(out, configuredHost);
        if (player != null) {
            addCandidate(out, connectionLocalHost(player, logger));
        }
        addCandidate(out, serverPropertyIp);
        addCandidate(out, firstLanIpv4());
        addCandidate(out, "127.0.0.1");
        return out;
    }

    private static void addCandidate(List<String> out, String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        String trimmed = host.trim();
        if (!out.contains(trimmed)) {
            out.add(trimmed);
        }
    }

    private static boolean isUsableHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String value = host.trim();
        if ("0.0.0.0".equals(value) || "::".equals(value) || "0:0:0:0:0:0:0:0".equals(value)) {
            return false;
        }
        return !"127.0.0.1".equals(value) && !"localhost".equalsIgnoreCase(value);
    }

    private static String connectionLocalHost(Player player, Logger logger) {
        try {
            Method getHandle = player.getClass().getMethod("getHandle");
            Object serverPlayer = getHandle.invoke(player);

            Object listener = readField(serverPlayer, "connection", "f_8940_");
            if (listener == null) {
                return null;
            }

            Object networkManager = readField(listener, "connection", "f_9741_");
            if (networkManager == null) {
                return null;
            }

            Object channel = invoke(networkManager, "channel", "n");
            if (channel == null) {
                return null;
            }

            Object localAddress = invoke(channel, "localAddress", "localAddress");
            if (!(localAddress instanceof InetSocketAddress socket)) {
                return null;
            }

            InetAddress address = socket.getAddress();
            if (address == null) {
                return null;
            }
            return address.getHostAddress();
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.FINE, "Could not read player connection host via reflection", ex);
            return null;
        }
    }

    private static Object readField(Object target, String... names) throws ReflectiveOperationException {
        ReflectiveOperationException last = null;
        for (String name : names) {
            try {
                Field field = findField(target.getClass(), name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ex) {
                last = ex;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private static Object invoke(Object target, String... names) throws ReflectiveOperationException {
        ReflectiveOperationException last = null;
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (ReflectiveOperationException ex) {
                last = ex;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String firstLanIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address inet4 && !inet4.isLoopbackAddress() && inet4.isSiteLocalAddress()) {
                        return inet4.getHostAddress();
                    }
                }
            }
            interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address inet4 && !inet4.isLoopbackAddress()) {
                        return inet4.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
