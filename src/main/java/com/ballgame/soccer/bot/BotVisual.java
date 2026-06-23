package com.ballgame.soccer.bot;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.replay.MannequinSupport;
import com.ballgame.soccer.util.Items;
import com.ballgame.soccer.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Visible body for a match bot — Citizens player NPC when available, otherwise
 * Mannequin (1.21.4+) or team-colored armor stand.
 */
final class BotVisual {

    private enum Backend {
        CITIZENS, MANNEQUIN, ARMOR_STAND
    }

    private final SoccerPlugin plugin;
    private final SoccerSettings settings;
    private final String displayName;
    private final TeamSide team;
    private final int squadIndex;

    private Backend backend = Backend.ARMOR_STAND;
    private CitizensSupport.CitizensNpc citizensNpc;
    private Entity entity;
    private ArmorStand fallback;
    private Location lastPos;

    BotVisual(SoccerPlugin plugin, SoccerSettings settings, String displayName,
              TeamSide team, int squadIndex) {
        this.plugin = plugin;
        this.settings = settings;
        this.displayName = displayName;
        this.team = team;
        this.squadIndex = squadIndex;
    }

    boolean usesCitizensNavigation() {
        return backend == Backend.CITIZENS && citizensNpc != null;
    }

    void spawn(Location location) {
        remove();
        if (location == null || location.getWorld() == null) {
            return;
        }
        String mode = settings.botVisualType == null ? "auto" : settings.botVisualType.toLowerCase();
        if (shouldUseCitizens(mode) && trySpawnCitizens(location)) {
            return;
        }
        if (shouldUseMannequin(mode) && trySpawnMannequin(location)) {
            return;
        }
        spawnArmorStand(location);
    }

    private boolean shouldUseCitizens(String mode) {
        return switch (mode) {
            case "citizens" -> true;
            case "mannequin", "armor_stand", "armorstand" -> false;
            default -> CitizensSupport.isAvailable();
        };
    }

    private boolean shouldUseMannequin(String mode) {
        return switch (mode) {
            case "mannequin" -> MannequinSupport.isAvailable();
            case "citizens", "armor_stand", "armorstand" -> false;
            default -> !CitizensSupport.isAvailable() && MannequinSupport.isAvailable();
        };
    }

    private boolean trySpawnCitizens(Location location) {
        if (!CitizensSupport.isAvailable()) {
            return false;
        }
        citizensNpc = CitizensSupport.spawnNpc(
                Text.color(teamColor() + displayName),
                location,
                resolveSkinName());
        if (citizensNpc == null) {
            return false;
        }
        Entity ent = citizensNpc.getEntity();
        if (ent instanceof LivingEntity living) {
            equip(living);
        }
        entity = ent;
        backend = Backend.CITIZENS;
        lastPos = location.clone();
        return entity != null && entity.isValid();
    }

    private boolean trySpawnMannequin(Location location) {
        if (!MannequinSupport.isAvailable()) {
            return false;
        }
        try {
            entity = location.getWorld().spawnEntity(location, MannequinSupport.entityType());
            configureMannequin(entity);
            backend = Backend.MANNEQUIN;
            lastPos = location.clone();
            return true;
        } catch (Exception ignored) {
            entity = null;
            return false;
        }
    }

    private void configureMannequin(Entity spawned) throws ReflectiveOperationException {
        UUID id = UUID.nameUUIDFromBytes(
                ("advancedsoccer-bot-" + team.name() + "-" + squadIndex)
                        .getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Items.profileFromBase64(null, id, displayName);
        spawned.setPersistent(false);

        Class<?> mannequinClass = Class.forName("org.bukkit.entity.Mannequin");
        Method setProfile = findMethod(mannequinClass, "setProfile", PlayerProfile.class);
        if (setProfile == null) {
            setProfile = findMethod(mannequinClass, "setPlayerProfile", PlayerProfile.class);
        }
        if (setProfile != null) {
            setProfile.invoke(spawned, profile);
        }

        if (spawned instanceof LivingEntity living) {
            living.setCustomNameVisible(true);
            living.setCustomName(Text.color(teamColor() + displayName));
            living.setCollidable(false);
            living.setGravity(false);
            living.setSilent(true);
            living.setInvulnerable(true);
            living.setAI(false);
            living.setRemoveWhenFarAway(false);
            equip(living);
        }
    }

    private void spawnArmorStand(Location location) {
        fallback = location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(true);
            stand.setSmall(false);
            stand.setMarker(false);
            stand.setCollidable(false);
            stand.setCustomNameVisible(true);
            stand.setCustomName(Text.color(teamColor() + displayName));
            stand.setCanPickupItems(false);
            equip(stand);
        });
        entity = fallback;
        backend = Backend.ARMOR_STAND;
        lastPos = location.clone();
    }

    private void equip(LivingEntity living) {
        Color color = team == TeamSide.RED ? Color.fromRGB(210, 45, 45) : Color.fromRGB(45, 85, 210);
        var eq = living.getEquipment();
        if (eq == null) {
            return;
        }
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, color));
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, color));
        eq.setBoots(dyed(Material.LEATHER_BOOTS, color));
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

    private String resolveSkinName() {
        List<String> skins = settings.botSkinNames;
        if (skins == null || skins.isEmpty()) {
            return displayName;
        }
        return skins.get(Math.floorMod(squadIndex, skins.size()));
    }

    private String teamColor() {
        return team == TeamSide.RED ? settings.redChatColor : settings.blueChatColor;
    }

    Location navigate(Location target, boolean sprinting, Vector velocity) {
        if (target == null) {
            return null;
        }
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            float speedMod = sprinting ? 1.38f : 1.05f;
            citizensNpc.navigateTo(target, sprinting, speedMod);
            citizensNpc.applyLocomotion(sprinting, velocity);
            Location synced = citizensNpc.readLocation();
            if (synced != null) {
                lastPos = synced.clone();
            }
            return synced;
        }
        move(target, sprinting, velocity);
        return target;
    }

    Location readSyncedLocation() {
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            Location synced = citizensNpc.readLocation();
            if (synced != null) {
                lastPos = synced.clone();
            }
            return synced;
        }
        if (entity != null && entity.isValid()) {
            Location at = entity.getLocation();
            at.setPitch(0f);
            lastPos = at.clone();
            return at;
        }
        return null;
    }

    void applyFacing(float yaw, boolean sprinting, Vector velocity) {
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            citizensNpc.face(yaw);
            citizensNpc.applyLocomotion(sprinting, velocity);
            return;
        }
        if (entity == null || !entity.isValid()) {
            return;
        }
        Location at = entity.getLocation().clone();
        at.setYaw(yaw);
        at.setPitch(0f);
        entity.teleport(at);
        if (entity instanceof LivingEntity living) {
            invokeBoolean(living, "setSprinting", sprinting);
            applyVelocity(living, velocity, sprinting);
        }
    }

    void move(Location location, boolean sprinting, Vector velocity) {
        if (location == null) {
            return;
        }
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            citizensNpc.teleport(location, sprinting, new CitizensSupport.VectorHelper(velocity));
            lastPos = location.clone();
            return;
        }
        if (entity == null || !entity.isValid()) {
            return;
        }
        Location at = location.clone();
        at.setPitch(0f);
        entity.teleport(at);
        if (entity instanceof LivingEntity living) {
            invokeBoolean(living, "setSprinting", sprinting);
            applyVelocity(living, velocity, sprinting);
        }
        lastPos = at.clone();
    }

    private void applyVelocity(LivingEntity living, Vector velocity, boolean sprinting) {
        if (velocity == null) {
            return;
        }
        Vector flat = velocity.clone();
        flat.setY(0);
        if (flat.lengthSquared() < 1.0e-4) {
            if (sprinting && lastPos != null) {
                Vector step = living.getLocation().toVector().subtract(lastPos.toVector());
                step.setY(0);
                if (step.lengthSquared() > 1.0e-4) {
                    flat = step.normalize().multiply(Math.min(0.32, step.length() * 1.15));
                }
            }
        }
        if (flat.lengthSquared() > 1.0e-4) {
            living.setVelocity(flat);
        }
    }

    void swingArm() {
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            citizensNpc.swingArm();
            return;
        }
        if (entity instanceof LivingEntity living) {
            try {
                Method swing = living.getClass().getMethod("swingMainHand");
                swing.invoke(living);
            } catch (ReflectiveOperationException ignored) {
            }
            if (living instanceof Player player) {
                player.swingMainHand();
            }
        }
    }

    void remove() {
        if (citizensNpc != null) {
            citizensNpc.destroy();
            citizensNpc = null;
        }
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        entity = null;
        fallback = null;
        lastPos = null;
        backend = Backend.ARMOR_STAND;
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
