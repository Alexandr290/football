package com.ballgame.soccer.referee;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.replay.MannequinSupport;
import com.ballgame.soccer.replay.ReplayAnimationPackets;
import com.ballgame.soccer.util.Items;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Full-body referee using the Mannequin entity (1.21.4+) with vanilla sprint
 * animations. Falls back to an armor stand if spawn fails.
 */
final class MannequinRefereeVisual implements RefereeVisual {

    private static final Color KIT_BLACK = Color.fromRGB(20, 20, 20);
    private static final Color KIT_YELLOW = Color.fromRGB(255, 204, 0);

    private final SoccerPlugin plugin;
    private final SoccerSettings settings;
    private final RefereeActor.Role role;
    private final String displayName;
    private final boolean carriesFlag;
    private final Supplier<Collection<Player>> viewers;

    private Entity entity;
    private ArmorStandRefereeVisual fallback;
    private Location lastPos;
    private boolean sprinting;

    MannequinRefereeVisual(SoccerPlugin plugin, SoccerSettings settings, RefereeActor.Role role,
                           String displayName, boolean carriesFlag,
                           Supplier<Collection<Player>> viewers) {
        this.plugin = plugin;
        this.settings = settings;
        this.role = role;
        this.displayName = displayName;
        this.carriesFlag = carriesFlag;
        this.viewers = viewers;
    }

    @Override
    public void spawn(Location location) {
        remove();
        if (location == null || location.getWorld() == null || !MannequinSupport.isAvailable()) {
            spawnFallback(location);
            return;
        }
        try {
            entity = location.getWorld().spawnEntity(location, MannequinSupport.entityType());
            configure(entity);
            lastPos = location.clone();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Referee mannequin spawn failed, using armor stand.", ex);
            spawnFallback(location);
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
        lastPos = null;
    }

    @Override
    public Location getLocation() {
        if (fallback != null) {
            return fallback.getLocation();
        }
        return entity != null && entity.isValid() ? entity.getLocation() : null;
    }

    @Override
    public void setHidden(boolean hide) {
        if (fallback != null) {
            fallback.setHidden(hide);
            return;
        }
        if (entity != null && entity.isValid()) {
            if (entity instanceof LivingEntity living) {
                living.setInvisible(hide);
                living.setCustomNameVisible(!hide);
            }
        }
    }

    @Override
    public void teleport(Location location, float yaw) {
        if (fallback != null) {
            fallback.teleport(location, yaw);
            return;
        }
        if (entity == null || !entity.isValid() || location == null) {
            return;
        }
        Location at = location.clone();
        at.setYaw(yaw);
        at.setPitch(0f);
        entity.teleport(at);

        if (entity instanceof LivingEntity living && lastPos != null && sprinting) {
            Vector vel = at.toVector().subtract(lastPos.toVector());
            vel.setY(0);
            if (vel.lengthSquared() > 1.0e-4) {
                vel.normalize().multiply(0.28);
                living.setVelocity(vel);
            }
        }
        lastPos = at.clone();
    }

    @Override
    public void setLocomotion(boolean moving, boolean sprinting) {
        if (fallback != null) {
            fallback.setLocomotion(moving, sprinting);
            return;
        }
        this.sprinting = moving && sprinting;
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        invokeBoolean(living, "setSprinting", moving && sprinting);
        invokeBoolean(living, "setSneaking", false);
    }

    @Override
    public void animateMovement(long tick, boolean sprinting) {
        if (fallback != null) {
            fallback.animateMovement(tick, sprinting);
        }
    }

    @Override
    public void showGesture(RefereeGesture gesture, long tick) {
        if (fallback != null) {
            fallback.showGesture(gesture, tick);
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        invokeBoolean(living, "setSprinting", false);
        switch (gesture) {
            case WHISTLE, FLAG, POINT -> {
                if (tick % 10 == 0) {
                    swingArm(viewers.get());
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void swingArm(Collection<Player> viewers) {
        if (fallback != null) {
            fallback.swingArm(viewers);
            return;
        }
        if (entity == null || !entity.isValid()) {
            return;
        }
        ReplayAnimationPackets.swingMainHand(entity, viewers);
        try {
            Method swing = entity.getClass().getMethod("swingMainHand");
            swing.invoke(entity);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Override
    public boolean usesPlayerModel() {
        return fallback == null && entity != null && entity.isValid();
    }

    private void spawnFallback(Location location) {
        fallback = new ArmorStandRefereeVisual(plugin, role, displayName, carriesFlag);
        fallback.spawn(location);
    }

    private void configure(Entity spawned) throws ReflectiveOperationException {
        UUID id = UUID.nameUUIDFromBytes(
                ("advancedsoccer-ref-" + role.name()).getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Items.profileFromBase64(
                settings.refereeSkinTexture, id, displayName);
        spawned.setPersistent(false);
        RefereeMetadata.tag(plugin, spawned, role.name());

        Class<?> mannequinClass = Class.forName("org.bukkit.entity.Mannequin");
        if (!mannequinClass.isInstance(spawned)) {
            throw new IllegalStateException("Spawned entity is not a Mannequin.");
        }
        Method setProfile = findMethod(mannequinClass, "setProfile", PlayerProfile.class);
        if (setProfile == null) {
            setProfile = findMethod(mannequinClass, "setPlayerProfile", PlayerProfile.class);
        }
        if (setProfile == null) {
            throw new NoSuchMethodException("Mannequin profile setter not found.");
        }
        setProfile.invoke(spawned, profile);

        if (spawned instanceof LivingEntity living) {
            living.setCollidable(false);
            living.setGravity(false);
            living.setSilent(true);
            living.setInvulnerable(true);
            living.setCustomNameVisible(true);
            living.setCustomName(displayName);
            living.setRemoveWhenFarAway(false);
            equip(living);
        }
    }

    private void equip(LivingEntity living) {
        var eq = living.getEquipment();
        if (eq == null) {
            return;
        }
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE,
                role == RefereeActor.Role.CENTER ? KIT_BLACK : KIT_YELLOW));
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, KIT_BLACK));
        eq.setBoots(dyed(Material.LEATHER_BOOTS, KIT_BLACK));
        if (carriesFlag) {
            eq.setItemInMainHand(new ItemStack(Material.YELLOW_BANNER));
        } else {
            eq.setItemInMainHand(new ItemStack(Material.IRON_NUGGET));
        }
    }

    private ItemStack dyed(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static void invokeBoolean(LivingEntity living, String method, boolean value) {
        try {
            Method m = living.getClass().getMethod(method, boolean.class);
            m.invoke(living, value);
        } catch (ReflectiveOperationException ignored) {
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
