package com.ballgame.soccer.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Built-in real player NPC backend — spawns genuine player-model entities to
 * clients using vanilla packets (no Citizens, no ProtocolLib).
 */
final class PlayerNpcSupport {

    private static final Logger LOG = Bukkit.getLogger();
    private static final boolean AVAILABLE;
    private static String unavailableReason = "unknown";

    private static Method craftServerGetServer;
    private static Method craftWorldGetHandle;
    private static Method craftPlayerGetHandle;

    private static Constructor<?> gameProfileCtor;
    private static Method gameProfileGetProperties;
    private static Constructor<?> propertyCtor;
    private static Method propertyMapPut;

    private static Class<?> nmsEntityClass;
    private static Class<?> serverPlayerClass;
    private static Constructor<?> serverPlayerCtor;
    private static Method clientInfoCreateDefault;
    private static Method entitySetPos;
    private static Method entityAbsMoveTo;
    private static Method entitySetYRot;
    private static Method entitySetXRot;
    private static Method entitySetYHeadRot;
    private static Method entitySetYBodyRot;
    private static Method entityGetId;
    private static Method entitySetId;
    private static Method entitySetSprinting;
    private static Method entityGetEntityData;
    private static Method synchedGetNonDefault;
    private static Method synchedPackDirty;
    private static Method entitySetPose;
    private static Class<?> poseClass;
    private static Method synchedSet;
    private static Method serverPlayerGetUuid;
    private static Method serverPlayerGetGameProfile;

    private static Object playerModeCustomisationAccessor;

    private static Method infoCreateInitializing;
    private static Constructor<?> playerInfoActionCtor;
    private static Constructor<?> playerInfoEnumSetCtor;
    private static Constructor<?> playerInfoEntryCtor;
    private static Object playerInfoAddPlayerAction;
    private static Object gameTypeSurvival;

    private static Class<?> serverEntityClassRef;
    private static Method getAddEntityPacketWithEntity;
    private static Method getAddEntityPacketNoArg;
    private static Constructor<?> addEntityCtor;
    private static Constructor<?> removeEntitiesCtor;
    private static Constructor<?> teleportEntityCtor;
    private static Constructor<?> movePosRotCtor;
    private static Constructor<?> positionMoveRotationCtor;
    private static Constructor<?> vec3Ctor;
    private static Object vec3Zero;
    private static Constructor<?> rotateHeadCtor;
    private static Constructor<?> setEntityDataCtor;
    private static Constructor<?> animateCtor;

    private static Method craftItemStackAsNms;
    private static Class<?> nmsItemStackClass;
    private static Class<?> equipmentSlotClass;
    private static Object slotHead;
    private static Object slotChest;
    private static Object slotLegs;
    private static Object slotFeet;
    private static Method pairOf;
    private static Constructor<?> setEquipmentCtor;
    private static Method entitySetItemSlot;

    private static Method connectionSend;

    static {
        AVAILABLE = init();
    }

    private PlayerNpcSupport() {
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    static String unavailableReason() {
        return unavailableReason;
    }

    private static boolean init() {
        try {
            String craftPkg = Bukkit.getServer().getClass().getPackage().getName();
            Class<?> craftServer = Class.forName(craftPkg + ".CraftServer");
            Class<?> craftWorld = Class.forName(craftPkg + ".CraftWorld");
            Class<?> craftPlayer = Class.forName(craftPkg + ".entity.CraftPlayer");
            craftServerGetServer = craftServer.getMethod("getServer");
            craftWorldGetHandle = craftWorld.getMethod("getHandle");
            craftPlayerGetHandle = craftPlayer.getMethod("getHandle");

            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            gameProfileCtor = gameProfileClass.getConstructor(UUID.class, String.class);
            gameProfileGetProperties = gameProfileClass.getMethod("getProperties");
            propertyCtor = propertyClass.getConstructor(String.class, String.class, String.class);
            propertyMapPut = gameProfileGetProperties.getReturnType()
                    .getMethod("put", Object.class, Object.class);

            Class<?> minecraftServer = Class.forName("net.minecraft.server.MinecraftServer");
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> clientInfo = Class.forName("net.minecraft.server.level.ClientInformation");
            nmsEntityClass = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> synchedData = Class.forName("net.minecraft.network.syncher.SynchedEntityData");
            Class<?> dataAccessor = Class.forName("net.minecraft.network.syncher.EntityDataAccessor");
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.Packet");

            clientInfoCreateDefault = clientInfo.getMethod("createDefault");
            serverPlayerCtor = serverPlayerClass.getConstructor(
                    minecraftServer, serverLevel, gameProfileClass, clientInfo);

            entitySetPos = nmsEntityClass.getMethod("setPos", double.class, double.class, double.class);
            entityAbsMoveTo = findMethod(nmsEntityClass, "absMoveTo",
                    double.class, double.class, double.class, float.class, float.class);
            entitySetYRot = nmsEntityClass.getMethod("setYRot", float.class);
            entitySetXRot = nmsEntityClass.getMethod("setXRot", float.class);
            entitySetYHeadRot = nmsEntityClass.getMethod("setYHeadRot", float.class);
            entitySetYBodyRot = findMethod(nmsEntityClass, "setYBodyRot", float.class);
            entityGetId = nmsEntityClass.getMethod("getId");
            entitySetId = findMethod(nmsEntityClass, "setId", int.class);
            entitySetSprinting = nmsEntityClass.getMethod("setSprinting", boolean.class);
            entityGetEntityData = nmsEntityClass.getMethod("getEntityData");
            synchedGetNonDefault = synchedData.getMethod("getNonDefaultValues");
            synchedPackDirty = findMethod(synchedData, "packDirty");
            synchedSet = synchedData.getMethod("set", dataAccessor, Object.class);
            try {
                poseClass = Class.forName("net.minecraft.world.entity.Pose");
                entitySetPose = findMethod(nmsEntityClass, "setPose", poseClass);
            } catch (ClassNotFoundException ignored) {
                poseClass = null;
                entitySetPose = null;
            }
            serverPlayerGetUuid = serverPlayerClass.getMethod("getUUID");
            serverPlayerGetGameProfile = serverPlayerClass.getMethod("getGameProfile");

            playerModeCustomisationAccessor = resolveSkinLayerAccessor(dataAccessor);

            Class<?> infoUpdatePacket =
                    Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            try {
                infoCreateInitializing = infoUpdatePacket.getMethod(
                        "createPlayerInitializing", java.util.Collection.class);
            } catch (NoSuchMethodException ignored) {
                infoCreateInitializing = null;
            }
            resolvePlayerInfoConstructors(infoUpdatePacket);

            serverEntityClassRef = Class.forName("net.minecraft.server.level.ServerEntity");
            for (Method m : serverPlayerClass.getMethods()) {
                if (!"getAddEntityPacket".equals(m.getName())) {
                    continue;
                }
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(serverEntityClassRef)) {
                    getAddEntityPacketWithEntity = m;
                } else if (m.getParameterCount() == 0) {
                    getAddEntityPacketNoArg = m;
                }
            }

            Class<?> addEntityPacket =
                    Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
            addEntityCtor = findAddEntityCtor(addEntityPacket, serverEntityClassRef, nmsEntityClass);

            Class<?> removeEntities =
                    Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
            removeEntitiesCtor = findRemoveEntitiesCtor(removeEntities);

            Class<?> teleportPacket =
                    Class.forName("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket");
            resolveModernTeleport();
            teleportEntityCtor = findTeleportCtor(teleportPacket, nmsEntityClass);

            movePosRotCtor = findMovePosRotCtor(
                    Class.forName("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket"));

            Class<?> rotateHead =
                    Class.forName("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket");
            rotateHeadCtor = rotateHead.getConstructor(nmsEntityClass, byte.class);

            Class<?> setData =
                    Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
            setEntityDataCtor = setData.getConstructor(int.class, List.class);

            Class<?> animate =
                    Class.forName("net.minecraft.network.protocol.game.ClientboundAnimatePacket");
            animateCtor = animate.getConstructor(nmsEntityClass, int.class);

            Class<?> connectionClass =
                    Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            connectionSend = resolveConnectionSend(connectionClass, packetClass);

            resolveEquipmentReflection(craftPkg);

            boolean canSpawn = (getAddEntityPacketWithEntity != null || getAddEntityPacketNoArg != null
                    || addEntityCtor != null)
                    && connectionSend != null
                    && (infoCreateInitializing != null || playerInfoActionCtor != null
                    || playerInfoEnumSetCtor != null);
            if (!canSpawn) {
                unavailableReason = "required packet APIs not found on "
                        + Bukkit.getVersion();
            }
            return canSpawn;
        } catch (ReflectiveOperationException ex) {
            unavailableReason = ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + " — needs Paper 1.20.5+ (not vanilla Spigot). Server: "
                    + Bukkit.getVersion();
            return false;
        } catch (Exception ex) {
            unavailableReason = String.valueOf(ex.getMessage());
            return false;
        }
    }

    static NpcHandle spawn(World world, Location location, String name, BotSkins.Skin skin) {
        return spawn(world, location, name, skin, null);
    }

    static NpcHandle spawn(World world, Location location, String name, BotSkins.Skin skin,
                           ItemStack[] armor) {
        if (!AVAILABLE || world == null || location == null) {
            return null;
        }
        try {
            Object mcServer = craftServerGetServer.invoke(Bukkit.getServer());
            Object level = craftWorldGetHandle.invoke(world);

            UUID id = UUID.randomUUID();
            String profileName = sanitizeName(name, id);
            Object profile = gameProfileCtor.newInstance(id, profileName);
            applySkin(profile, skin);

            Object client = clientInfoCreateDefault.invoke(null);
            Object serverPlayer = serverPlayerCtor.newInstance(mcServer, level, profile, client);
            ensureEntityId(serverPlayer, level);

            NpcHandle handle = new NpcHandle(serverPlayer, level, id);
            handle.place(location);
            handle.applyArmor(armor);
            if (!handle.verifyPackets()) {
                unavailableReason = handle.lastError();
                LOG.warning("[AdvancedSoccer] Packet NPC could not build spawn packets for \""
                        + name + "\": " + handle.lastError());
                handle.despawn();
                return null;
            }
            handle.refreshViewers();
            return handle;
        } catch (ReflectiveOperationException ex) {
            LOG.log(Level.WARNING, "[AdvancedSoccer] Packet NPC spawn failed for \"" + name + "\"", ex);
            unavailableReason = "spawn failed: " + ex.getMessage();
            return null;
        }
    }

    private static void ensureEntityId(Object serverPlayer, Object level)
            throws ReflectiveOperationException {
        if (entitySetId == null) {
            return;
        }
        int id = (int) entityGetId.invoke(serverPlayer);
        if (id != 0) {
            return;
        }
        int next = nextEntityId(level);
        if (next != 0) {
            entitySetId.invoke(serverPlayer, next);
        }
    }

    private static int nextEntityId(Object level) {
        try {
            Method direct = findMethod(level.getClass(), "getNextEntityId");
            if (direct != null) {
                return (int) direct.invoke(level);
            }
            Method getEntities = level.getClass().getMethod("getEntities");
            Object tracker = getEntities.invoke(level);
            Method next = findMethod(tracker.getClass(), "getNextEntityId");
            if (next != null) {
                return (int) next.invoke(tracker);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return 0;
    }

    /** Signed texture property of an online player, so a stand-in body can wear their real skin. */
    static BotSkins.Skin skinOf(Player player) {
        if (!AVAILABLE || player == null) {
            return null;
        }
        try {
            Object handle = craftPlayerGetHandle.invoke(player);
            Object profile = serverPlayerGetGameProfile.invoke(handle);
            Object properties = gameProfileGetProperties.invoke(profile);
            Method get = properties.getClass().getMethod("get", Object.class);
            Object textures = get.invoke(properties, "textures");
            if (textures instanceof Iterable<?> iterable) {
                for (Object property : iterable) {
                    Object value = invokeFirst(property, "value", "getValue");
                    Object signature = invokeFirst(property, "signature", "getSignature");
                    if (value instanceof String text && !text.isBlank()) {
                        return new BotSkins.Skin(text, signature instanceof String sig ? sig : null);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static Object invokeFirst(Object target, String... names) {
        for (String name : names) {
            try {
                return target.getClass().getMethod(name).invoke(target);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static void applySkin(Object profile, BotSkins.Skin skin) throws ReflectiveOperationException {
        if (skin == null || skin.value() == null) {
            return;
        }
        Object property = propertyCtor.newInstance("textures", skin.value(),
                skin.signature() == null ? "" : skin.signature());
        Object properties = gameProfileGetProperties.invoke(profile);
        propertyMapPut.invoke(properties, "textures", property);
    }

    static final class NpcHandle {
        private final Object serverPlayer;
        private final Object level;
        private final UUID id;
        private final int entityId;
        private final Set<UUID> viewers = new HashSet<>();
        private Location location;
        private float pitch;
        private double lastSentX;
        private double lastSentY;
        private double lastSentZ;
        private float lastSentYaw;
        private float lastHeadYaw = 720f;
        private boolean sendHeadThisTick;
        private boolean hasSentPosition;
        private boolean sprinting;
        private boolean dataDirty = true;
        private String lastError = "";
        private List<Object> equipmentPairs = Collections.emptyList();

        private NpcHandle(Object serverPlayer, Object level, UUID id) throws ReflectiveOperationException {
            this.serverPlayer = serverPlayer;
            this.level = level;
            this.id = id;
            this.entityId = (int) entityGetId.invoke(serverPlayer);
            enableSkinLayers();
        }

        String lastError() {
            return lastError;
        }

        boolean verifyPackets() {
            try {
                Object info = createPlayerInfoPacket();
                Object add = createAddEntityPacket();
                if (info == null) {
                    lastError = "player-info packet could not be built";
                    return false;
                }
                if (add == null) {
                    lastError = "add-entity packet could not be built";
                    return false;
                }
                return true;
            } catch (ReflectiveOperationException ex) {
                lastError = describeReflectiveFailure(ex);
                LOG.log(Level.FINE, "[AdvancedSoccer] NPC packet verification failed", ex);
                return false;
            }
        }

        private void enableSkinLayers() {
            if (playerModeCustomisationAccessor == null) {
                return;
            }
            try {
                Object data = entityGetEntityData.invoke(serverPlayer);
                synchedSet.invoke(data, playerModeCustomisationAccessor, (byte) 0x7F);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        private void place(Location loc) {
            this.location = loc.clone();
            lastSentX = loc.getX();
            lastSentY = loc.getY();
            lastSentZ = loc.getZ();
            hasSentPosition = false;
            try {
                if (entityAbsMoveTo != null) {
                    entityAbsMoveTo.invoke(serverPlayer,
                            loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), 0f);
                } else {
                    entitySetPos.invoke(serverPlayer, loc.getX(), loc.getY(), loc.getZ());
                    entitySetYRot.invoke(serverPlayer, loc.getYaw());
                    entitySetXRot.invoke(serverPlayer, 0f);
                }
                entitySetYHeadRot.invoke(serverPlayer, loc.getYaw());
                if (entitySetYBodyRot != null) {
                    entitySetYBodyRot.invoke(serverPlayer, loc.getYaw());
                }
            } catch (ReflectiveOperationException ex) {
                LOG.fine("[AdvancedSoccer] NPC place failed: " + ex.getMessage());
            }
        }

        void update(Location loc, boolean sprint, float yaw) {
            update(loc, sprint, yaw, 0f);
        }

        /**
         * Moves the NPC and aims its head. {@code yaw} is the view direction: the
         * client turns the body toward the direction of travel on its own (within
         * ~50° of the head), so a bot can run one way while watching the ball.
         */
        void update(Location loc, boolean sprint, float yaw, float headPitch) {
            if (loc == null) {
                return;
            }
            this.location = loc.clone();
            this.location.setYaw(yaw);
            this.location.setPitch(headPitch);
            this.pitch = headPitch;
            try {
                if (entityAbsMoveTo != null) {
                    entityAbsMoveTo.invoke(serverPlayer,
                            loc.getX(), loc.getY(), loc.getZ(), yaw, headPitch);
                } else {
                    entitySetPos.invoke(serverPlayer, loc.getX(), loc.getY(), loc.getZ());
                    entitySetYRot.invoke(serverPlayer, yaw);
                    entitySetXRot.invoke(serverPlayer, headPitch);
                }
                entitySetYHeadRot.invoke(serverPlayer, yaw);
                if (entitySetYBodyRot != null) {
                    entitySetYBodyRot.invoke(serverPlayer, yaw);
                }
                if (sprint != sprinting) {
                    sprinting = sprint;
                    entitySetSprinting.invoke(serverPlayer, sprint);
                    dataDirty = true;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            broadcast(yaw);
        }

        void faceYaw(float yaw) {
            if (location != null) {
                update(location, sprinting, yaw, pitch);
            }
        }

        /** Body pose by vanilla name (STANDING, CROUCHING, SWIMMING...); ignored when unsupported. */
        void setPose(String name) {
            if (entitySetPose == null || poseClass == null || name == null) {
                return;
            }
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object pose = Enum.valueOf((Class<Enum>) poseClass.asSubclass(Enum.class), name);
                entitySetPose.invoke(serverPlayer, pose);
                dataDirty = true;
            } catch (IllegalArgumentException | ReflectiveOperationException ignored) {
            }
        }

        private void broadcast(float yaw) {
            List<Player> nearby = viewersInRange();
            Set<UUID> stillVisible = new HashSet<>();
            Object metadata = dataDirty ? buildMetadataPacket() : null;
            dataDirty = false;
            boolean moved = false;
            sendHeadThisTick = Math.abs(wrapYaw(yaw - lastHeadYaw)) >= 4f;
            boolean snap = hasSentPosition && location != null && (
                    (location.getX() - lastSentX) * (location.getX() - lastSentX)
                            + (location.getY() - lastSentY) * (location.getY() - lastSentY)
                            + (location.getZ() - lastSentZ) * (location.getZ() - lastSentZ) > 16.0);
            if (snap) {
                for (Player viewer : nearby) {
                    stillVisible.add(viewer.getUniqueId());
                    if (viewers.contains(viewer.getUniqueId())) {
                        sendDespawn(viewer);
                    } else {
                        viewers.add(viewer.getUniqueId());
                    }
                    sendSpawn(viewer);
                }
                lastSentX = location.getX();
                lastSentY = location.getY();
                lastSentZ = location.getZ();
                lastSentYaw = yaw;
                lastHeadYaw = yaw;
                hasSentPosition = true;
            }
            for (Player viewer : nearby) {
                if (snap) {
                    continue;
                }
                stillVisible.add(viewer.getUniqueId());
                if (!viewers.contains(viewer.getUniqueId())) {
                    sendSpawn(viewer);
                    viewers.add(viewer.getUniqueId());
                } else if (sendMove(viewer, yaw)) {
                    moved = true;
                }
                if (metadata != null && viewers.contains(viewer.getUniqueId())) {
                    try {
                        send(viewer, metadata);
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
            if (moved && location != null) {
                lastSentX = location.getX();
                lastSentY = location.getY();
                lastSentZ = location.getZ();
                lastSentYaw = yaw;
                if (sendHeadThisTick) {
                    lastHeadYaw = yaw;
                }
                hasSentPosition = true;
            }
            if (!viewers.isEmpty()) {
                List<UUID> gone = new ArrayList<>();
                for (UUID v : viewers) {
                    if (!stillVisible.contains(v)) {
                        gone.add(v);
                    }
                }
                for (UUID v : gone) {
                    Player p = Bukkit.getPlayer(v);
                    if (p != null) {
                        sendDespawn(p);
                    }
                    viewers.remove(v);
                }
            }
        }

        void refreshViewers() {
            broadcast(location != null ? location.getYaw() : 0f);
        }

        private List<Player> viewersInRange() {
            if (location == null || location.getWorld() == null) {
                return Collections.emptyList();
            }
            List<Player> result = new ArrayList<>();
            for (Player p : location.getWorld().getPlayers()) {
                if (p.isOnline() && p.getLocation().distanceSquared(location) <= 96.0 * 96.0) {
                    result.add(p);
                }
            }
            return result;
        }

        void applyArmor(ItemStack[] armor) {
            if (armor == null || armor.length == 0) {
                return;
            }
            try {
                equipmentPairs = buildEquipmentPairs(armor);
                applyEquipmentToEntity(armor);
            } catch (ReflectiveOperationException ex) {
                LOG.fine("[AdvancedSoccer] NPC armor apply failed: " + ex.getMessage());
            }
        }

        private void applyEquipmentToEntity(ItemStack[] armor) throws ReflectiveOperationException {
            if (entitySetItemSlot == null) {
                return;
            }
            Object[] slots = {slotHead, slotChest, slotLegs, slotFeet};
            int limit = Math.min(armor.length, slots.length);
            for (int i = 0; i < limit; i++) {
                if (slots[i] == null || armor[i] == null || armor[i].getType().isAir()) {
                    continue;
                }
                Object nmsItem = toNmsItem(armor[i]);
                entitySetItemSlot.invoke(serverPlayer, slots[i], nmsItem);
            }
            dataDirty = true;
        }

        private void sendSpawn(Player viewer) {
            try {
                Object info = createPlayerInfoPacket();
                Object addPacket = createAddEntityPacket();
                if (info == null || addPacket == null) {
                    LOG.warning("[AdvancedSoccer] NPC spawn packets missing for viewer "
                            + viewer.getName() + " (info=" + (info != null) + ", add="
                            + (addPacket != null) + ")");
                    return;
                }
                send(viewer, info);
                send(viewer, addPacket);
                sendEquipment(viewer);
                sendMetadata(viewer);
                sendHeadRotation(viewer, location != null ? location.getYaw() : 0f);
            } catch (ReflectiveOperationException ex) {
                LOG.log(Level.WARNING, "[AdvancedSoccer] NPC sendSpawn failed for "
                        + viewer.getName(), ex);
            }
        }

        private void sendEquipment(Player viewer) {
            if (setEquipmentCtor == null || equipmentPairs.isEmpty()) {
                return;
            }
            try {
                Object packet = createEquipmentPacket();
                if (packet != null) {
                    send(viewer, packet);
                }
            } catch (ReflectiveOperationException ex) {
                LOG.fine("[AdvancedSoccer] NPC equipment packet failed: " + ex.getMessage());
            }
        }

        private Object createEquipmentPacket() throws ReflectiveOperationException {
            Class<?>[] params = setEquipmentCtor.getParameterTypes();
            if (params.length == 2 && params[0] == int.class && List.class.isAssignableFrom(params[1])) {
                return setEquipmentCtor.newInstance(entityId, equipmentPairs);
            }
            if (params.length == 2 && nmsEntityClass.isAssignableFrom(params[0])
                    && List.class.isAssignableFrom(params[1])) {
                return setEquipmentCtor.newInstance(serverPlayer, equipmentPairs);
            }
            if (params.length == 1 && List.class.isAssignableFrom(params[0])) {
                return setEquipmentCtor.newInstance(equipmentPairs);
            }
            return null;
        }

        private Object createPlayerInfoPacket() throws ReflectiveOperationException {
            ReflectiveOperationException last = null;

            if (playerInfoEnumSetCtor != null && playerInfoEntryCtor != null
                    && playerInfoAddPlayerAction != null) {
                try {
                    Object entry = buildPlayerInfoEntry();
                    if (entry != null) {
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        EnumSet actions = EnumSet.of((Enum) playerInfoAddPlayerAction);
                        return playerInfoEnumSetCtor.newInstance(actions, List.of(entry));
                    }
                } catch (ReflectiveOperationException ex) {
                    last = ex;
                }
            }

            if (playerInfoActionCtor != null && playerInfoAddPlayerAction != null) {
                try {
                    Class<?>[] params = playerInfoActionCtor.getParameterTypes();
                    if (params.length == 2 && params[1].isInstance(serverPlayer)) {
                        return playerInfoActionCtor.newInstance(playerInfoAddPlayerAction, serverPlayer);
                    }
                    if (params.length == 2 && java.util.Collection.class.isAssignableFrom(params[1])) {
                        return playerInfoActionCtor.newInstance(
                                playerInfoAddPlayerAction, Collections.singletonList(serverPlayer));
                    }
                } catch (ReflectiveOperationException ex) {
                    last = ex;
                }
            }

            if (infoCreateInitializing != null) {
                try {
                    return infoCreateInitializing.invoke(null, Collections.singletonList(serverPlayer));
                } catch (ReflectiveOperationException ex) {
                    last = ex;
                }
            }

            if (last != null) {
                throw last;
            }
            return null;
        }

        private Object buildPlayerInfoEntry() throws ReflectiveOperationException {
            if (playerInfoEntryCtor == null) {
                return null;
            }
            UUID uuid = (UUID) serverPlayerGetUuid.invoke(serverPlayer);
            Object profile = serverPlayerGetGameProfile.invoke(serverPlayer);
            Class<?>[] params = playerInfoEntryCtor.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Class<?> type = params[i];
                if (type == UUID.class) {
                    args[i] = uuid;
                } else if (type.isInstance(profile) || type.getName().contains("GameProfile")) {
                    args[i] = profile;
                } else if (type == boolean.class) {
                    args[i] = true;
                } else if (type == int.class) {
                    args[i] = 0;
                } else if (type.isEnum() && gameTypeSurvival != null && type.isInstance(gameTypeSurvival)) {
                    args[i] = gameTypeSurvival;
                } else {
                    args[i] = null;
                }
            }
            return playerInfoEntryCtor.newInstance(args);
        }

        private Object createAddEntityPacket() throws ReflectiveOperationException {
            if (getAddEntityPacketWithEntity != null) {
                Object serverEntity = newServerEntity(level, serverPlayer);
                return getAddEntityPacketWithEntity.invoke(serverPlayer, serverEntity);
            }
            if (getAddEntityPacketNoArg != null) {
                return getAddEntityPacketNoArg.invoke(serverPlayer);
            }
            if (addEntityCtor != null) {
                Object serverEntity = newServerEntity(level, serverPlayer);
                if (addEntityCtor.getParameterCount() == 2) {
                    return addEntityCtor.newInstance(serverPlayer, serverEntity);
                }
                return addEntityCtor.newInstance(serverPlayer);
            }
            return null;
        }

        /**
         * Relative move when the step is small — a teleport every tick makes a full
         * squad look like it is stuttering. Returns false when nothing was sent.
         */
        private boolean sendMove(Player viewer, float yaw) {
            try {
                if (location == null) {
                    return false;
                }
                double dx = location.getX() - lastSentX;
                double dy = location.getY() - lastSentY;
                double dz = location.getZ() - lastSentZ;
                float yawDelta = Math.abs(wrapYaw(yaw - lastSentYaw));
                boolean tiny = dx * dx + dy * dy + dz * dz < 0.0008 && yawDelta < 8f;
                if (hasSentPosition && tiny) {
                    return false;
                }
                boolean relative = hasSentPosition && movePosRotCtor != null
                        && Math.abs(dx) <= 7.5 && Math.abs(dy) <= 7.5 && Math.abs(dz) <= 7.5;
                if (relative) {
                    sendRelativeMove(viewer, yaw);
                } else if (!sendPositionPacket(viewer, yaw)) {
                    return false;
                }
                if (sendHeadThisTick) {
                    sendHeadRotation(viewer, yaw);
                }
                return true;
            } catch (ReflectiveOperationException ex) {
                LOG.fine("[AdvancedSoccer] NPC move packet failed: " + ex.getMessage());
                return false;
            }
        }

        private static float wrapYaw(float delta) {
            while (delta > 180f) {
                delta -= 360f;
            }
            while (delta < -180f) {
                delta += 360f;
            }
            return delta;
        }

        private boolean sendPositionPacket(Player viewer, float yaw) throws ReflectiveOperationException {
            if (teleportEntityCtor == null || location == null) {
                return false;
            }
            Object[] args = buildTeleportArgs(yaw);
            if (args == null) {
                return false;
            }
            Object tp = teleportEntityCtor.newInstance(args);
            send(viewer, tp);
            return true;
        }

        private void sendRelativeMove(Player viewer, float yaw) throws ReflectiveOperationException {
            double dx = location.getX() - lastSentX;
            double dy = location.getY() - lastSentY;
            double dz = location.getZ() - lastSentZ;
            if (Math.abs(dx) > 8.0 || Math.abs(dy) > 8.0 || Math.abs(dz) > 8.0) {
                return;
            }
            short sdx = (short) Math.round(dx * 4096.0);
            short sdy = (short) Math.round(dy * 4096.0);
            short sdz = (short) Math.round(dz * 4096.0);
            byte yRot = (byte) (yaw * 256.0f / 360.0f);
            byte xRot = (byte) (pitch * 256.0f / 360.0f);
            Object packet = movePosRotCtor.newInstance(entityId, sdx, sdy, sdz, yRot, xRot, true);
            send(viewer, packet);
        }

        private Object[] buildTeleportArgs(float yaw) throws ReflectiveOperationException {
            Class<?>[] params = teleportEntityCtor.getParameterTypes();
            if (params.length == 1) {
                if (params[0].isInstance(serverPlayer) || nmsEntityClass.isAssignableFrom(params[0])) {
                    return new Object[]{serverPlayer};
                }
                if (params[0] == int.class) {
                    return new Object[]{entityId};
                }
            }
            // Paper 1.21.2+: (int id, PositionMoveRotation, Set<Relative>, boolean onGround)
            if (params.length == 4 && params[0] == int.class && positionMoveRotationCtor != null
                    && params[1].isAssignableFrom(positionMoveRotationCtor.getDeclaringClass())) {
                Object position = vec3Ctor.newInstance(location.getX(), location.getY(), location.getZ());
                Object delta = vec3Zero != null ? vec3Zero : vec3Ctor.newInstance(0.0, 0.0, 0.0);
                Object change = positionMoveRotationCtor.newInstance(position, delta, yaw, pitch);
                return new Object[]{entityId, change, Collections.emptySet(), true};
            }
            if (params.length >= 4 && params[0] == int.class && params[1] == double.class) {
                Object[] args = new Object[params.length];
                args[0] = entityId;
                args[1] = location.getX();
                args[2] = location.getY();
                args[3] = location.getZ();
                if (params.length >= 5 && params[4] == byte.class) {
                    args[4] = (byte) (yaw * 256.0f / 360.0f);
                }
                if (params.length >= 6 && params[5] == byte.class) {
                    args[5] = (byte) (pitch * 256.0f / 360.0f);
                }
                if (params.length >= 7 && params[6] == boolean.class) {
                    args[6] = true;
                }
                return args;
            }
            return new Object[]{serverPlayer};
        }

        private void sendHeadRotation(Player viewer, float yaw) throws ReflectiveOperationException {
            byte angle = (byte) (yaw * 256.0f / 360.0f);
            Object packet = rotateHeadCtor.newInstance(serverPlayer, angle);
            send(viewer, packet);
        }

        private void sendMetadata(Player viewer) {
            try {
                Object data = entityGetEntityData.invoke(serverPlayer);
                Object values = synchedGetNonDefault.invoke(data);
                if (values instanceof List<?> list && !list.isEmpty()) {
                    Object packet = setEntityDataCtor.newInstance(entityId, list);
                    send(viewer, packet);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

        /**
         * Changed entity data for viewers that already see the NPC. Dirty values
         * include resets back to defaults (end of sprint, standing up again),
         * which the non-default snapshot would silently drop.
         */
        private Object buildMetadataPacket() {
            try {
                Object data = entityGetEntityData.invoke(serverPlayer);
                Object values = synchedPackDirty != null
                        ? synchedPackDirty.invoke(data)
                        : synchedGetNonDefault.invoke(data);
                if (values instanceof List<?> list && !list.isEmpty()) {
                    return setEntityDataCtor.newInstance(entityId, list);
                }
            } catch (ReflectiveOperationException ignored) {
            }
            return null;
        }

        void swing() {
            try {
                Object packet = animateCtor.newInstance(serverPlayer, 0);
                for (Player viewer : viewersInRange()) {
                    send(viewer, packet);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

        private void sendDespawn(Player viewer) {
            try {
                Object packet = removeEntitiesCtor.newInstance((Object) buildRemoveIds());
                send(viewer, packet);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        private int[] buildRemoveIds() {
            return new int[]{entityId};
        }

        void despawn() {
            for (UUID v : new ArrayList<>(viewers)) {
                Player p = Bukkit.getPlayer(v);
                if (p != null) {
                    sendDespawn(p);
                }
            }
            viewers.clear();
        }

        Location getLocation() {
            return location == null ? null : location.clone();
        }

        private void send(Player viewer, Object packet) throws ReflectiveOperationException {
            Object handle = craftPlayerGetHandle.invoke(viewer);
            Object connection = resolveViewerConnection(handle);
            if (connection != null) {
                connectionSend.invoke(connection, packet);
            }
        }
    }

    private static Object resolveViewerConnection(Object serverPlayerHandle)
            throws ReflectiveOperationException {
        try {
            Field field = serverPlayerHandle.getClass().getField("connection");
            return field.get(serverPlayerHandle);
        } catch (NoSuchFieldException ex) {
            try {
                Method method = serverPlayerHandle.getClass().getMethod("connection");
                return method.invoke(serverPlayerHandle);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
    }

    private static void resolvePlayerInfoConstructors(Class<?> infoUpdatePacket) {
        try {
            Class<?> actionEnum = Class.forName(infoUpdatePacket.getName() + "$Action");
            for (Object constant : actionEnum.getEnumConstants()) {
                if ("ADD_PLAYER".equals(constant.toString())) {
                    playerInfoAddPlayerAction = constant;
                    break;
                }
            }
            Class<?> entryClass = Class.forName(infoUpdatePacket.getName() + "$Entry");
            for (Constructor<?> c : entryClass.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length >= 2 && p[0] == UUID.class) {
                    c.setAccessible(true);
                    playerInfoEntryCtor = c;
                    break;
                }
            }
            // The public (EnumSet, Collection<ServerPlayer>) constructor reads
            // ServerPlayer.connection.latency(), which is null for packet-only
            // NPCs. Prefer the private/decompiled (EnumSet, List<Entry>) path.
            for (Constructor<?> c : infoUpdatePacket.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && EnumSet.class.isAssignableFrom(p[0])
                        && List.class.isAssignableFrom(p[1])) {
                    c.setAccessible(true);
                    playerInfoEnumSetCtor = c;
                    break;
                }
            }
            for (Constructor<?> c : infoUpdatePacket.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[0].isEnum()) {
                    playerInfoActionCtor = c;
                }
            }
            Class<?> gameType = Class.forName("net.minecraft.world.level.GameType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object survival = Enum.valueOf((Class<Enum>) gameType, "SURVIVAL");
            gameTypeSurvival = survival;
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Object newServerEntity(Object level, Object entity)
            throws ReflectiveOperationException {
        ReflectiveOperationException lastFailure = null;
        for (Constructor<?> c : serverEntityClassRef.getConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length < 5 || !params[0].isInstance(level) || !params[1].isInstance(entity)) {
                continue;
            }
            Object[] args = buildServerEntityArgs(params, level, entity);
            if (args == null) {
                continue;
            }
            try {
                return c.newInstance(args);
            } catch (ReflectiveOperationException ex) {
                lastFailure = ex;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new NoSuchMethodException("No compatible ServerEntity constructor on "
                + Bukkit.getVersion());
    }

    private static Object[] buildServerEntityArgs(Class<?>[] params, Object level, Object entity) {
        Object[] args = new Object[params.length];
        args[0] = level;
        args[1] = entity;
        int index = 2;
        if (index < params.length && params[index] == int.class) {
            args[index++] = 0;
        }
        if (index < params.length && params[index] == boolean.class) {
            args[index++] = false;
        }
        while (index < params.length) {
            Class<?> type = params[index];
            if (Consumer.class.isAssignableFrom(type)) {
                args[index] = noopFor(type);
            } else if (BiConsumer.class.isAssignableFrom(type)) {
                args[index] = noopFor(type);
            } else if (Set.class.isAssignableFrom(type)) {
                args[index] = Collections.emptySet();
            } else if (type.isInterface()) {
                args[index] = noopFor(type);
            } else {
                return null;
            }
            index++;
        }
        return args;
    }

    private static Object noopFor(Class<?> functionalInterface) {
        return java.lang.reflect.Proxy.newProxyInstance(
                functionalInterface.getClassLoader(),
                new Class<?>[]{functionalInterface},
                (proxy, method, methodArgs) -> {
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) {
                        return false;
                    }
                    if (ret == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static String describeReflectiveFailure(ReflectiveOperationException ex) {
        Throwable cause = ex instanceof java.lang.reflect.InvocationTargetException ite
                && ite.getTargetException() != null
                ? ite.getTargetException() : ex;
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }
        if (cause.getCause() != null && cause.getCause().getMessage() != null) {
            msg = msg + " (" + cause.getCause().getMessage() + ")";
        }
        return msg;
    }

    private static Object resolveSkinLayerAccessor(Class<?> dataAccessorClass) {
        try {
            Class<?> playerClass = Class.forName("net.minecraft.world.entity.player.Player");
            for (Field f : playerClass.getDeclaredFields()) {
                if (dataAccessorClass.isAssignableFrom(f.getType())
                        && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && f.getName().toUpperCase().contains("CUSTOMIS")) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Constructor<?> findAddEntityCtor(Class<?> addEntityPacket,
                                                    Class<?> serverEntityClass,
                                                    Class<?> entityClass) {
        for (Constructor<?> c : addEntityPacket.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0].isAssignableFrom(entityClass) && p[1].equals(serverEntityClass)) {
                return c;
            }
        }
        for (Constructor<?> c : addEntityPacket.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && p[0].isAssignableFrom(entityClass)) {
                return c;
            }
        }
        return null;
    }

    private static Constructor<?> findRemoveEntitiesCtor(Class<?> removeEntities) {
        try {
            return removeEntities.getConstructor(int[].class);
        } catch (NoSuchMethodException ex) {
            for (Constructor<?> c : removeEntities.getConstructors()) {
                if (c.getParameterCount() == 1) {
                    return c;
                }
            }
            return null;
        }
    }

    private static void resolveModernTeleport() {
        try {
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            vec3Ctor = vec3.getConstructor(double.class, double.class, double.class);
            try {
                vec3Zero = vec3.getField("ZERO").get(null);
            } catch (NoSuchFieldException ignored) {
                vec3Zero = null;
            }
            Class<?> change = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            positionMoveRotationCtor = change.getConstructor(vec3, vec3, float.class, float.class);
        } catch (ReflectiveOperationException ex) {
            positionMoveRotationCtor = null;
            LOG.fine("[AdvancedSoccer] Modern teleport packet unavailable: " + ex.getMessage());
        }
    }

    private static Constructor<?> findTeleportCtor(Class<?> teleportPacket, Class<?> entityClass) {
        Constructor<?> modernCtor = null;
        Constructor<?> coordCtor = null;
        Constructor<?> entityCtor = null;
        for (Constructor<?> c : teleportPacket.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 4 && p[0] == int.class && positionMoveRotationCtor != null
                    && p[1].isAssignableFrom(positionMoveRotationCtor.getDeclaringClass())
                    && Set.class.isAssignableFrom(p[2]) && p[3] == boolean.class) {
                modernCtor = c;
            } else if (p.length >= 4 && p[0] == int.class && p[1] == double.class) {
                coordCtor = c;
            } else if (p.length == 1 && entityClass.isAssignableFrom(p[0])) {
                entityCtor = c;
            }
        }
        if (modernCtor != null) {
            return modernCtor;
        }
        if (coordCtor != null) {
            return coordCtor;
        }
        if (entityCtor != null) {
            return entityCtor;
        }
        for (Constructor<?> c : teleportPacket.getConstructors()) {
            if (c.getParameterCount() == 1) {
                return c;
            }
        }
        return null;
    }

    private static Constructor<?> findMovePosRotCtor(Class<?> movePacket) {
        for (Class<?> inner : movePacket.getDeclaredClasses()) {
            if (!"PosRot".equals(inner.getSimpleName())) {
                continue;
            }
            for (Constructor<?> c : inner.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 7 && p[0] == int.class && p[1] == short.class) {
                    return c;
                }
            }
        }
        return null;
    }

    private static Method resolveConnectionSend(Class<?> connectionClass, Class<?> packetClass) {
        try {
            return connectionClass.getMethod("send", packetClass);
        } catch (NoSuchMethodException ex) {
            for (Method m : connectionClass.getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterCount() == 1) {
                    return m;
                }
            }
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private static void resolveEquipmentReflection(String craftPkg) {
        try {
            Class<?> craftItemStack = Class.forName(craftPkg + ".inventory.CraftItemStack");
            craftItemStackAsNms = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");

            equipmentSlotClass = Class.forName("net.minecraft.world.entity.EquipmentSlot");
            slotHead = equipmentSlotConstant("HEAD");
            slotChest = equipmentSlotConstant("CHEST");
            slotLegs = equipmentSlotConstant("LEGS");
            slotFeet = equipmentSlotConstant("FEET");

            entitySetItemSlot = findMethod(serverPlayerClass, "setItemSlot", equipmentSlotClass, nmsItemStackClass);
            if (entitySetItemSlot == null) {
                Class<?> livingEntity = Class.forName("net.minecraft.world.entity.LivingEntity");
                entitySetItemSlot = findMethod(livingEntity, "setItemSlot", equipmentSlotClass, nmsItemStackClass);
            }

            Class<?> pairClass = Class.forName("com.mojang.datafixers.util.Pair");
            pairOf = pairClass.getMethod("of", Object.class, Object.class);

            Class<?> setEquipmentPacket = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket");
            for (Constructor<?> c : setEquipmentPacket.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && (p[0] == int.class || nmsEntityClass.isAssignableFrom(p[0]))
                        && List.class.isAssignableFrom(p[1])) {
                    setEquipmentCtor = c;
                    break;
                }
            }
        } catch (ReflectiveOperationException ex) {
            LOG.fine("[AdvancedSoccer] NPC equipment reflection unavailable: " + ex.getMessage());
        }
    }

    private static Object equipmentSlotConstant(String name) {
        if (equipmentSlotClass == null) {
            return null;
        }
        for (Object constant : equipmentSlotClass.getEnumConstants()) {
            if (name.equals(constant.toString())) {
                return constant;
            }
        }
        return null;
    }

    private static List<Object> buildEquipmentPairs(ItemStack[] armor) throws ReflectiveOperationException {
        if (pairOf == null || craftItemStackAsNms == null) {
            return Collections.emptyList();
        }
        Object[] slots = {slotHead, slotChest, slotLegs, slotFeet};
        List<Object> pairs = new ArrayList<>();
        int limit = Math.min(armor.length, slots.length);
        for (int i = 0; i < limit; i++) {
            if (slots[i] == null || armor[i] == null || armor[i].getType().isAir()) {
                continue;
            }
            Object nmsItem = toNmsItem(armor[i]);
            pairs.add(pairOf.invoke(null, slots[i], nmsItem));
        }
        return pairs;
    }

    private static Object toNmsItem(ItemStack item) throws ReflectiveOperationException {
        return craftItemStackAsNms.invoke(null, item);
    }

    private static String sanitizeName(String name, UUID id) {
        String base = name == null ? "" : name.replaceAll("[^A-Za-z0-9_]", "");
        if (base.length() > 16) {
            base = base.substring(0, 16);
        }
        if (base.isBlank()) {
            base = "Bot" + Integer.toHexString(id.hashCode()).substring(0, 5);
        }
        return base;
    }
}
