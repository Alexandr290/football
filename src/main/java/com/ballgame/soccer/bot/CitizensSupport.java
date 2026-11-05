package com.ballgame.soccer.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional Citizens2 integration via reflection so the plugin builds without
 * Citizens on the classpath.
 */
public final class CitizensSupport {

    private static final Logger LOG = Bukkit.getLogger();

    private static Boolean available;
    private static Class<?> citizensApiClass;
    private static Class<?> npcClass;
    private static Class<?> skinTraitClass;
    private static Class<?> navigatorClass;
    private static Class<?> navigatorParametersClass;
    private static Method getRegistry;
    private static Method createNpc;
    private static Method spawn;
    private static Method destroy;
    private static Method getEntity;
    private static Method getOrAddTrait;
    private static Method setSkinName;
    private static Method getNavigator;
    private static Method cancelNavigation;
    private static Method setProtected;
    private static Method navigatorSetTargetLocation;
    private static Method navigatorSetStraightLineTarget;
    private static Method navigatorIsNavigating;
    private static Method navigatorGetDefaultParameters;
    private static Method navigatorGetLocalParameters;
    private static Method navigatorParametersSpeedModifier;
    private static Method navigatorParametersDistanceMargin;
    private static Method navigatorParametersAvoidWater;
    private static Method navigatorParametersStationaryTicks;

    private CitizensSupport() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = probe();
        }
        return available;
    }

    private static boolean probe() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Citizens");
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        try {
            citizensApiClass = Class.forName("net.citizensnpcs.api.CitizensAPI");
            npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
            skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            navigatorClass = Class.forName("net.citizensnpcs.api.ai.Navigator");
            navigatorParametersClass = Class.forName("net.citizensnpcs.api.ai.NavigatorParameters");
            getRegistry = citizensApiClass.getMethod("getNPCRegistry");
            createNpc = getRegistry.getReturnType().getMethod(
                    "createNPC", org.bukkit.entity.EntityType.class, String.class);
            spawn = npcClass.getMethod("spawn", Location.class);
            destroy = npcClass.getMethod("destroy");
            getEntity = npcClass.getMethod("getEntity");
            getOrAddTrait = npcClass.getMethod("getOrAddTrait", Class.class);
            setSkinName = skinTraitClass.getMethod("setSkinName", String.class);
            getNavigator = npcClass.getMethod("getNavigator");
            cancelNavigation = getNavigator.getReturnType().getMethod("cancelNavigation");
            setProtected = npcClass.getMethod("setProtected", boolean.class);
            navigatorSetTargetLocation = navigatorClass.getMethod("setTarget", Location.class);
            try {
                navigatorSetStraightLineTarget = navigatorClass.getMethod("setStraightLineTarget", Location.class);
            } catch (NoSuchMethodException ignored) {
                navigatorSetStraightLineTarget = null;
            }
            navigatorIsNavigating = navigatorClass.getMethod("isNavigating");
            navigatorGetDefaultParameters = navigatorClass.getMethod("getDefaultParameters");
            navigatorGetLocalParameters = navigatorClass.getMethod("getLocalParameters");
            navigatorParametersSpeedModifier = navigatorParametersClass.getMethod("speedModifier", float.class);
            navigatorParametersDistanceMargin = navigatorParametersClass.getMethod("distanceMargin", float.class);
            navigatorParametersAvoidWater = navigatorParametersClass.getMethod("avoidWater", boolean.class);
            navigatorParametersStationaryTicks = navigatorParametersClass.getMethod("stationaryTicks", int.class);
            return true;
        } catch (ReflectiveOperationException ex) {
            LOG.log(Level.FINE, "Citizens API unavailable.", ex);
            return false;
        }
    }

    public static CitizensNpc spawnNpc(String displayName, Location location, String skinName) {
        if (!isAvailable() || location == null || location.getWorld() == null) {
            return null;
        }
        try {
            Object registry = getRegistry.invoke(null);
            Object npc = createNpc.invoke(registry, org.bukkit.entity.EntityType.PLAYER, displayName);
            if (skinName != null && !skinName.isEmpty()) {
                Object skinTrait = getOrAddTrait.invoke(npc, skinTraitClass);
                setSkinName.invoke(skinTrait, skinName);
            }
            setProtected.invoke(npc, true);
            spawn.invoke(npc, location);
            Object navigator = getNavigator.invoke(npc);
            cancelNavigation.invoke(navigator);
            configureNavigatorDefaults(navigator);
            Entity entity = (Entity) getEntity.invoke(npc);
            if (entity instanceof LivingEntity living) {
                living.setCollidable(false);
                living.setSilent(true);
                living.setRemoveWhenFarAway(false);
                living.setPersistent(false);
            }
            return new CitizensNpc(npc, entity);
        } catch (ReflectiveOperationException ex) {
            LOG.log(Level.FINE, "Failed to spawn Citizens bot NPC.", ex);
            return null;
        }
    }

    private static void configureNavigatorDefaults(Object navigator) throws ReflectiveOperationException {
        Object defaults = navigatorGetDefaultParameters.invoke(navigator);
        navigatorParametersDistanceMargin.invoke(defaults, 0.35f);
        navigatorParametersSpeedModifier.invoke(defaults, 1.12f);
        navigatorParametersAvoidWater.invoke(defaults, false);
        navigatorParametersStationaryTicks.invoke(defaults, 8);
    }

    /** Handle to a temporary match NPC. */
    public static final class CitizensNpc {
        private final Object npc;
        private Entity entity;
        private Location lastNavTarget;
        private boolean lastNavSprint;

        private CitizensNpc(Object npc, Entity entity) {
            this.npc = npc;
            this.entity = entity;
        }

        public Entity getEntity() {
            if (entity != null && entity.isValid()) {
                return entity;
            }
            try {
                entity = (Entity) getEntity.invoke(npc);
            } catch (ReflectiveOperationException ignored) {
                entity = null;
            }
            return entity;
        }

        public boolean navigateTo(Location target, boolean sprint, float speedModifier) {
            if (target == null || target.getWorld() == null) {
                return false;
            }
            try {
                Object navigator = getNavigator.invoke(npc);
                if (shouldRefreshTarget(target, sprint)) {
                    Location at = target.clone();
                    at.setPitch(0f);
                    if (navigatorSetStraightLineTarget != null) {
                        navigatorSetStraightLineTarget.invoke(navigator, at);
                    } else {
                        navigatorSetTargetLocation.invoke(navigator, at);
                    }
                    Object local = navigatorGetLocalParameters.invoke(navigator);
                    navigatorParametersSpeedModifier.invoke(local, speedModifier);
                    lastNavTarget = at.clone();
                    lastNavSprint = sprint;
                } else {
                    Object local = navigatorGetLocalParameters.invoke(navigator);
                    navigatorParametersSpeedModifier.invoke(local, speedModifier);
                    lastNavSprint = sprint;
                }
                return true;
            } catch (ReflectiveOperationException ex) {
                LOG.log(Level.FINE, "Citizens navigation failed.", ex);
                return false;
            }
        }

        public void cancelNavigation() {
            try {
                Object navigator = getNavigator.invoke(npc);
                cancelNavigation.invoke(navigator);
            } catch (ReflectiveOperationException ignored) {
            }
            lastNavTarget = null;
        }

        public boolean isNavigating() {
            try {
                Object navigator = getNavigator.invoke(npc);
                return (boolean) navigatorIsNavigating.invoke(navigator);
            } catch (ReflectiveOperationException ex) {
                return false;
            }
        }

        public Location readLocation() {
            Entity ent = getEntity();
            if (ent == null) {
                return null;
            }
            Location at = ent.getLocation();
            at.setPitch(0f);
            return at;
        }

        public void applyLocomotion(boolean sprinting, Vector velocityHint) {
            Entity ent = getEntity();
            if (ent == null) {
                return;
            }
            if (ent instanceof LivingEntity living) {
                living.setCollidable(false);
                invokeBoolean(living, "setSprinting", sprinting);
                invokeBoolean(living, "setSneaking", false);
                if (velocityHint != null) {
                    Vector flat = velocityHint.clone();
                    flat.setY(0);
                    if (flat.lengthSquared() > 1.0e-4) {
                        living.setVelocity(flat);
                    }
                }
            }
        }

        public void face(float yaw) {
            Entity ent = getEntity();
            if (ent == null) {
                return;
            }
            Location at = ent.getLocation().clone();
            at.setYaw(yaw);
            at.setPitch(0f);
            ent.teleport(at);
        }

        public void teleport(Location location, boolean sprinting, VectorHelper velocityHint) {
            Entity ent = getEntity();
            if (ent == null || location == null) {
                return;
            }
            cancelNavigation();
            Location at = location.clone();
            at.setPitch(0f);
            ent.teleport(at);
            applyLocomotion(sprinting, velocityHint != null ? velocityHint.flatVelocity() : null);
        }

        public void swingArm() {
            Entity ent = getEntity();
            if (ent == null) {
                return;
            }
            try {
                Method swing = ent.getClass().getMethod("swingMainHand");
                swing.invoke(ent);
            } catch (ReflectiveOperationException ignored) {
            }
            if (ent instanceof Player player) {
                player.swingMainHand();
            }
        }

        public void applySneak(boolean sneak) {
            Entity ent = getEntity();
            if (ent instanceof LivingEntity living) {
                invokeBoolean(living, "setSneaking", sneak);
                invokeBoolean(living, "setSwimming", sneak);
            }
        }

        public void destroy() {
            try {
                destroy.invoke(npc);
            } catch (ReflectiveOperationException ex) {
                Entity ent = getEntity();
                if (ent != null && ent.isValid()) {
                    ent.remove();
                }
            }
            entity = null;
            lastNavTarget = null;
        }

        private boolean shouldRefreshTarget(Location target, boolean sprint) {
            if (lastNavTarget == null || lastNavTarget.getWorld() != target.getWorld()) {
                return true;
            }
            if (sprint != lastNavSprint) {
                return true;
            }
            double dx = target.getX() - lastNavTarget.getX();
            double dz = target.getZ() - lastNavTarget.getZ();
            return dx * dx + dz * dz > 1.44;
        }
    }

    /** Small helper so CitizensNpc does not depend on MatchBot. */
    public static final class VectorHelper {
        private final Vector velocity;

        public VectorHelper(Vector velocity) {
            this.velocity = velocity;
        }

        Vector flatVelocity() {
            if (velocity == null) {
                return new Vector();
            }
            Vector flat = velocity.clone();
            flat.setY(0);
            return flat;
        }
    }

    private static void invokeBoolean(LivingEntity living, String method, boolean value) {
        try {
            Method m = living.getClass().getMethod(method, boolean.class);
            m.invoke(living, value);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
