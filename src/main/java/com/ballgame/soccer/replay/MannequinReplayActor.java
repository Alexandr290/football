package com.ballgame.soccer.replay;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.SoccerPlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Full-body replay actor using the Mannequin entity when the server supports it.
 * Falls back gracefully if reflection fails.
 */
public final class MannequinReplayActor implements ReplayActor {

    private final SoccerPlugin plugin;
    private final SoccerPlayer source;
    private Entity entity;
    private ArmorStandReplayActor fallback;

    public MannequinReplayActor(SoccerPlugin plugin, SoccerPlayer source) {
        this.plugin = plugin;
        this.source = source;
    }

    @Override
    public UUID sourceId() {
        return source.getUuid();
    }

    @Override
    public void spawn(Location location) {
        if (location.getWorld() == null || !MannequinSupport.isAvailable()) {
            spawnFallback(location);
            return;
        }
        try {
            entity = location.getWorld().spawnEntity(location, MannequinSupport.entityType());
            configureMannequin(entity);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Mannequin spawn failed, using armor stand.", ex);
            spawnFallback(location);
        }
    }

    @Override
    public void applyPose(PlayerPose pose, World world) {
        if (fallback != null) {
            fallback.applyPose(pose, world);
            return;
        }
        if (entity == null || !entity.isValid()) {
            return;
        }
        entity.teleport(pose.toLocation(world));
        if (entity instanceof LivingEntity living) {
            living.setCollidable(false);
            living.setGravity(false);
            living.setSilent(true);
            living.setInvulnerable(true);
            living.setCustomNameVisible(true);
            living.setCustomName(source.getName());
            applyLivingState(living, pose);
        }
    }

    @Override
    public void playSwingAnimation(Collection<Player> viewers) {
        if (fallback != null) {
            fallback.playSwingAnimation(viewers);
            return;
        }
        if (entity != null && entity.isValid()) {
            ReplayAnimationPackets.swingMainHand(entity, viewers);
            try {
                Method swing = entity.getClass().getMethod("swingMainHand");
                swing.invoke(entity);
            } catch (ReflectiveOperationException ignored) {
                // Bukkit API when available.
            }
        }
    }

    @Override
    public void setGlowing(boolean glowing) {
        if (fallback != null) {
            fallback.setGlowing(glowing);
            return;
        }
        if (entity instanceof LivingEntity living) {
            living.setGlowing(glowing);
        }
    }

    @Override
    public void remove() {
        if (fallback != null) {
            fallback.remove();
            fallback = null;
        }
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        entity = null;
    }

    private void spawnFallback(Location location) {
        fallback = new ArmorStandReplayActor(source);
        fallback.spawn(location);
    }

    private void configureMannequin(Entity spawned) throws ReflectiveOperationException {
        Player player = source.getBukkitPlayer();
        if (player == null) {
            return;
        }
        spawned.setPersistent(false);
        Class<?> mannequinClass = Class.forName("org.bukkit.entity.Mannequin");
        if (!mannequinClass.isInstance(spawned)) {
            throw new IllegalStateException("Spawned entity is not a Mannequin.");
        }
        Method setProfile = findMethod(mannequinClass, "setProfile", org.bukkit.profile.PlayerProfile.class);
        if (setProfile == null) {
            setProfile = findMethod(mannequinClass, "setPlayerProfile", org.bukkit.profile.PlayerProfile.class);
        }
        if (setProfile == null) {
            throw new NoSuchMethodException("Mannequin profile setter not found.");
        }
        setProfile.invoke(spawned, player.getPlayerProfile());
        if (spawned instanceof LivingEntity living) {
            living.setCollidable(false);
            living.setGravity(false);
            living.setSilent(true);
            living.setInvulnerable(true);
            living.setCustomNameVisible(true);
            living.setCustomName(source.getName());
        }
    }

    private void applyLivingState(LivingEntity living, PlayerPose pose) {
        try {
            Method setSneaking = living.getClass().getMethod("setSneaking", boolean.class);
            setSneaking.invoke(living, pose.sneaking);
        } catch (ReflectiveOperationException ignored) {
            // Not available on all entity types.
        }
        try {
            Method setSprinting = living.getClass().getMethod("setSprinting", boolean.class);
            setSprinting.invoke(living, pose.sprinting);
        } catch (ReflectiveOperationException ignored) {
            // Not available on all entity types.
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }
}
