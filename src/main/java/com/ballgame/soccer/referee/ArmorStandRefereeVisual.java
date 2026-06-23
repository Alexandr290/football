package com.ballgame.soccer.referee;

import com.ballgame.soccer.SoccerPlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.EulerAngle;

/**
 * Poseable armor-stand fallback when Mannequin entities are unavailable.
 */
final class ArmorStandRefereeVisual implements RefereeVisual {

    private static final Color KIT_BLACK = Color.fromRGB(20, 20, 20);
    private static final Color KIT_YELLOW = Color.fromRGB(255, 204, 0);

    private static final EulerAngle IDLE_RIGHT = new EulerAngle(-0.35, 0.0, 0.12);
    private static final EulerAngle IDLE_LEFT = new EulerAngle(-0.35, 0.0, -0.12);

    private final SoccerPlugin plugin;
    private final RefereeActor.Role role;
    private final String displayName;
    private final boolean carriesFlag;

    private ArmorStand stand;

    ArmorStandRefereeVisual(SoccerPlugin plugin, RefereeActor.Role role,
                            String displayName, boolean carriesFlag) {
        this.plugin = plugin;
        this.role = role;
        this.displayName = displayName;
        this.carriesFlag = carriesFlag;
    }

    @Override
    public void spawn(Location location) {
        remove();
        if (location == null || location.getWorld() == null) {
            return;
        }
        stand = location.getWorld().spawn(location, ArmorStand.class, entity -> {
            entity.setVisible(true);
            entity.setGravity(false);
            entity.setBasePlate(false);
            entity.setArms(true);
            entity.setCollidable(false);
            entity.setMarker(false);
            entity.setSmall(false);
            entity.setInvulnerable(true);
            entity.setCustomNameVisible(true);
            entity.setCustomName(displayName);
            entity.setPersistent(false);
            entity.setRemoveWhenFarAway(false);
        });
        RefereeMetadata.tag(plugin, stand, role.name());
        equip();
        showGesture(RefereeGesture.IDLE, 0);
    }

    @Override
    public void remove() {
        if (stand != null && stand.isValid()) {
            stand.remove();
        }
        stand = null;
    }

    @Override
    public Location getLocation() {
        return stand != null && stand.isValid() ? stand.getLocation() : null;
    }

    @Override
    public void setHidden(boolean hide) {
        if (stand != null && stand.isValid()) {
            stand.setInvisible(hide);
            stand.setCustomNameVisible(!hide);
        }
    }

    @Override
    public void teleport(Location location, float yaw) {
        if (stand == null || !stand.isValid() || location == null) {
            return;
        }
        Location at = location.clone();
        at.setYaw(yaw);
        at.setPitch(0f);
        stand.teleport(at);
    }

    @Override
    public void setLocomotion(boolean moving, boolean sprinting) {
    }

    @Override
    public void animateMovement(long tick, boolean sprinting) {
        if (stand == null) {
            return;
        }
        double phase = (tick % (sprinting ? 8 : 14)) / (sprinting ? 8.0 : 14.0) * Math.PI * 2.0;
        double swing = sprinting ? 0.72 : 0.48;
        stand.setRightLegPose(new EulerAngle(Math.sin(phase) * swing, 0.0, 0.0));
        stand.setLeftLegPose(new EulerAngle(Math.sin(phase + Math.PI) * swing, 0.0, 0.0));
        stand.setRightArmPose(new EulerAngle(Math.sin(phase + Math.PI) * (swing * 0.45), 0.05, 0.08));
        stand.setLeftArmPose(new EulerAngle(Math.sin(phase) * (swing * 0.45), -0.05, -0.08));
    }

    @Override
    public void showGesture(RefereeGesture gesture, long tick) {
        if (stand == null || !stand.isValid()) {
            return;
        }
        switch (gesture) {
            case WHISTLE -> {
                stand.setRightArmPose(new EulerAngle(-1.55, 0.15, 0.05));
                stand.setLeftArmPose(new EulerAngle(-0.25, 0.0, -0.12));
                stand.setRightLegPose(new EulerAngle(-0.05, 0.0, 0.0));
                stand.setLeftLegPose(new EulerAngle(0.05, 0.0, 0.0));
            }
            case FLAG -> {
                stand.setRightArmPose(new EulerAngle(-2.85, 0.05, 0.0));
                stand.setLeftArmPose(IDLE_LEFT);
                stand.setRightLegPose(new EulerAngle(-0.05, 0.0, 0.0));
                stand.setLeftLegPose(new EulerAngle(0.05, 0.0, 0.0));
            }
            case POINT -> {
                stand.setRightArmPose(new EulerAngle(-2.45, -0.35, 0.0));
                stand.setLeftArmPose(IDLE_LEFT);
                stand.setRightLegPose(new EulerAngle(-0.05, 0.0, 0.0));
                stand.setLeftLegPose(new EulerAngle(0.05, 0.0, 0.0));
            }
            default -> applyIdle();
        }
    }

    @Override
    public void swingArm(java.util.Collection<Player> viewers) {
    }

    @Override
    public boolean usesPlayerModel() {
        return false;
    }

    private void applyIdle() {
        if (stand == null) {
            return;
        }
        stand.setRightArmPose(IDLE_RIGHT);
        stand.setLeftArmPose(IDLE_LEFT);
        stand.setRightLegPose(new EulerAngle(-0.05, 0.0, 0.0));
        stand.setLeftLegPose(new EulerAngle(0.05, 0.0, 0.0));
    }

    private void equip() {
        if (stand == null) {
            return;
        }
        var eq = stand.getEquipment();
        eq.setHelmet(new ItemStack(Material.PLAYER_HEAD));
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, role == RefereeActor.Role.CENTER ? KIT_BLACK : KIT_YELLOW));
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
}
