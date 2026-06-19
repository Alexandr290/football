package com.ballgame.soccer.replay;

import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.EulerAngle;

import java.util.Collection;
import java.util.UUID;

/**
 * Poseable replay actor using an armor stand with the player's skin and team kit.
 */
public final class ArmorStandReplayActor implements ReplayActor {

    private static final EulerAngle IDLE_RIGHT_ARM = new EulerAngle(-0.35, 0.0, 0.12);
    private static final EulerAngle IDLE_LEFT_ARM = new EulerAngle(-0.35, 0.0, -0.12);
    private static final EulerAngle IDLE_RIGHT_LEG = new EulerAngle(-0.08, 0.0, 0.0);
    private static final EulerAngle IDLE_LEFT_LEG = new EulerAngle(0.08, 0.0, 0.0);

    private final SoccerPlayer source;
    private ArmorStand stand;

    public ArmorStandReplayActor(SoccerPlayer source) {
        this.source = source;
    }

    @Override
    public UUID sourceId() {
        return source.getUuid();
    }

    @Override
    public void spawn(Location location) {
        if (location.getWorld() == null) {
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
            entity.setCustomName(source.getName());
            entity.setPersistent(false);
            entity.setRemoveWhenFarAway(false);
        });
        equipStand();
        applyLimbs(new PlayerPose(0, 0, 0, 0, 0, false, false, false, false));
    }

    @Override
    public void applyPose(PlayerPose pose, World world) {
        if (stand == null || !stand.isValid()) {
            return;
        }
        stand.teleport(pose.toLocation(world));
        applyLimbs(pose);
    }

    @Override
    public void setGlowing(boolean glowing) {
        if (stand != null && stand.isValid()) {
            stand.setGlowing(glowing);
        }
    }

    @Override
    public void playSwingAnimation(Collection<Player> viewers) {
        if (stand != null && stand.isValid()) {
            ReplayAnimationPackets.swingMainHand(stand, viewers);
        }
    }

    @Override
    public void remove() {
        if (stand != null && stand.isValid()) {
            stand.remove();
        }
        stand = null;
    }

    private void equipStand() {
        if (stand == null) {
            return;
        }
        var player = source.getBukkitPlayer();
        if (player == null) {
            return;
        }
        Color color = source.getTeam() == TeamSide.RED ? Color.RED : Color.BLUE;
        stand.getEquipment().setHelmet(playerHead(player));
        stand.getEquipment().setChestplate(dyed(Material.LEATHER_CHESTPLATE, color));
        stand.getEquipment().setLeggings(dyed(Material.LEATHER_LEGGINGS, color));
        stand.getEquipment().setBoots(dyed(Material.LEATHER_BOOTS, color));
    }

    private ItemStack playerHead(org.bukkit.entity.Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwnerProfile(player.getPlayerProfile());
            head.setItemMeta(meta);
        }
        return head;
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

    private void applyLimbs(PlayerPose pose) {
        if (stand == null) {
            return;
        }
        if (pose.armSwing) {
            stand.setRightArmPose(new EulerAngle(-2.05, -0.25, 0.05));
            stand.setLeftArmPose(new EulerAngle(-0.25, 0.0, -0.15));
        } else if (pose.interacting) {
            stand.setRightArmPose(new EulerAngle(-0.45, 0.05, 0.15));
            stand.setLeftArmPose(new EulerAngle(-1.55, 0.35, -0.05));
        } else {
            stand.setRightArmPose(IDLE_RIGHT_ARM);
            stand.setLeftArmPose(IDLE_LEFT_ARM);
        }

        if (pose.sprinting) {
            stand.setLeftLegPose(new EulerAngle(-0.65, 0.05, 0.0));
            stand.setRightLegPose(new EulerAngle(0.55, -0.05, 0.0));
        } else if (pose.sneaking) {
            stand.setLeftLegPose(new EulerAngle(-0.35, 0.0, -0.08));
            stand.setRightLegPose(new EulerAngle(0.25, 0.0, 0.08));
        } else {
            stand.setLeftLegPose(IDLE_LEFT_LEG);
            stand.setRightLegPose(IDLE_RIGHT_LEG);
        }
        stand.setSmall(pose.sneaking);
    }
}
