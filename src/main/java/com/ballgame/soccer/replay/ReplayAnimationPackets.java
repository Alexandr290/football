package com.ballgame.soccer.replay;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Sends vanilla swing animations to replay viewers via NMS packets (no ProtocolLib).
 * Reflection keeps compilation working across 1.20.x–1.21.x.
 */
public final class ReplayAnimationPackets {

    private static final boolean AVAILABLE;
    private static Method craftEntityGetHandle;
    private static Method craftPlayerGetHandle;
    private static Constructor<?> animatePacketCtor;
    private static Method connectionSend;

    static {
        boolean ok = false;
        try {
            Class<?> craftEntity = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity");
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            Class<?> nmsEntity = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundAnimatePacket");
            Class<?> nmsPacket = Class.forName("net.minecraft.network.protocol.Packet");
            Class<?> connection = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");

            craftEntityGetHandle = craftEntity.getMethod("getHandle");
            craftPlayerGetHandle = craftPlayer.getMethod("getHandle");
            animatePacketCtor = packet.getConstructor(nmsEntity, int.class);
            connectionSend = connection.getMethod("send", nmsPacket);
            ok = true;
        } catch (ReflectiveOperationException ignored) {
            // Pose animation still applies when packets are unavailable.
        }
        AVAILABLE = ok;
    }

    private ReplayAnimationPackets() {
    }

    /** Animation id 0 = swing main hand. */
    public static void swingMainHand(Entity entity, Collection<Player> viewers) {
        if (!AVAILABLE || entity == null || viewers.isEmpty()) {
            return;
        }
        try {
            Object nmsEntity = craftEntityGetHandle.invoke(entity);
            Object packet = animatePacketCtor.newInstance(nmsEntity, 0);
            for (Player viewer : viewers) {
                if (viewer == null || !viewer.isOnline()) {
                    continue;
                }
                Object handle = craftPlayerGetHandle.invoke(viewer);
                Object connection = resolveConnection(handle);
                if (connection != null) {
                    connectionSend.invoke(connection, packet);
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Best-effort.
        }
    }

    private static Object resolveConnection(Object serverPlayer) throws ReflectiveOperationException {
        try {
            Field field = serverPlayer.getClass().getField("connection");
            return field.get(serverPlayer);
        } catch (NoSuchFieldException ex) {
            Method method = serverPlayer.getClass().getMethod("connection");
            return method.invoke(serverPlayer);
        }
    }
}
