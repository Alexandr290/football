package com.ballgame.soccer.bot;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.TeamSide;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Visual-only body that represents a human participant in cutscenes (walkout,
 * line-up) while the real player is watching through the broadcast camera. It
 * wears the player's own skin and the team kit, and walks like a bot body.
 */
public final class StandInPlayer {

    private final BotVisual visual;
    private final double groundY;
    private Location location;
    private float headYaw;
    private float headPitch;

    private StandInPlayer(BotVisual visual, Location at) {
        this.visual = visual;
        this.location = at.clone();
        this.groundY = at.getY();
        this.headYaw = at.getYaw();
    }

    /**
     * @param kitOverride optional fixed kit (null = team colours)
     */
    public static StandInPlayer spawn(SoccerPlugin plugin, Game game, Player player, TeamSide team,
                                      int bodyIndex, Location at, ItemStack[] kitOverride) {
        if (plugin == null || player == null || at == null || at.getWorld() == null) {
            return null;
        }
        BotSkins.prime(player.getName(), PlayerNpcSupport.skinOf(player));
        TeamSide side = team != null ? team : TeamSide.RED;
        BotVisual visual = new BotVisual(plugin, plugin.settings(), player.getName(), side,
                50 + bodyIndex, player.getName(), game);
        if (kitOverride != null) {
            visual.setArmorOverride(kitOverride);
        }
        visual.spawn(at);
        return new StandInPlayer(visual, at);
    }

    public Location getLocation() {
        return location.clone();
    }

    /** Walks toward {@code target} at a fixed pace while looking at {@code lookAt}. */
    public void step(Location target, double blocksPerTick, Location lookAt) {
        if (target == null) {
            return;
        }
        Vector delta = target.toVector().subtract(location.toVector());
        delta.setY(0);
        double dist = delta.length();
        Vector velocity = new Vector();
        if (dist > 1.0e-3) {
            velocity = delta.multiply(Math.min(dist, blocksPerTick) / dist);
            location.add(velocity);
            location.setYaw(HeadLook.yaw(velocity));
        }
        location.setY(groundY);
        aim(lookAt, false);
        visual.move(location, blocksPerTick > 0.24, velocity, headYaw, headPitch);
    }

    public void place(Location at, Location lookAt) {
        if (at == null) {
            return;
        }
        location = at.clone();
        location.setY(groundY);
        aim(lookAt, true);
        visual.move(location, false, new Vector(), headYaw, headPitch);
    }

    public void remove() {
        visual.remove();
    }

    private void aim(Location lookAt, boolean snap) {
        float[] look = HeadLook.toward(location, lookAt, location.getYaw());
        headYaw = snap ? look[0] : HeadLook.approachYaw(headYaw, look[0], 14f);
        headPitch = look[1];
    }
}
