package com.ballgame.soccer.referee;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * A single match official — delegates rendering to a Mannequin (player body) or
 * armor-stand fallback depending on server version and config.
 */
public final class RefereeActor {

    public enum Role {
        CENTER, ASSISTANT_LEFT, ASSISTANT_RIGHT
    }

    private final Role role;
    private final RefereeVisual visual;
    private final Supplier<Collection<Player>> viewers;

    private RefereeGesture gesture = RefereeGesture.IDLE;
    private int gestureTicks;
    private boolean hidden;

    public RefereeActor(SoccerPlugin plugin, SoccerSettings settings, Role role,
                        String displayName, boolean carriesFlag,
                        Supplier<Collection<Player>> viewers) {
        this.role = role;
        this.viewers = viewers;
        this.visual = RefereeVisualFactory.create(plugin, settings, role, displayName, carriesFlag, viewers);
    }

    public Role getRole() {
        return role;
    }

    public boolean isSpawned() {
        return visual.getLocation() != null;
    }

    public Location getLocation() {
        return visual.getLocation();
    }

    public void spawn(Location location) {
        visual.spawn(location);
    }

    public void remove() {
        visual.remove();
        hidden = false;
        gestureTicks = 0;
    }

    public void setHidden(boolean hide) {
        hidden = hide;
        visual.setHidden(hide);
    }

    public void moveTo(Location target, float yaw, boolean moving, boolean sprinting, long tick) {
        if (target == null || hidden) {
            return;
        }
        visual.teleport(target, yaw);

        if (gestureTicks > 0) {
            gestureTicks--;
            visual.showGesture(gesture, tick);
            if (tick % 12 == 0) {
                visual.swingArm(safeViewers());
            }
            visual.setLocomotion(false, false);
            return;
        }

        gesture = RefereeGesture.IDLE;
        if (moving) {
            visual.setLocomotion(true, sprinting);
            if (!visual.usesPlayerModel()) {
                visual.animateMovement(tick, sprinting);
            }
        } else {
            visual.setLocomotion(false, false);
            visual.showGesture(RefereeGesture.IDLE, tick);
        }
    }

    public void playWhistle(int ticks) {
        gesture = RefereeGesture.WHISTLE;
        gestureTicks = Math.max(ticks, 1);
        visual.swingArm(safeViewers());
    }

    public void playFlag(int ticks) {
        gesture = RefereeGesture.FLAG;
        gestureTicks = Math.max(ticks, 1);
        visual.swingArm(safeViewers());
    }

    public void playPoint(int ticks) {
        gesture = RefereeGesture.POINT;
        gestureTicks = Math.max(ticks, 1);
        visual.swingArm(safeViewers());
    }

    private Collection<Player> safeViewers() {
        Collection<Player> list = viewers.get();
        return list != null ? list : new ArrayList<>();
    }
}
