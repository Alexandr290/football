package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.util.InteractUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates raw player input into soccer actions:
 * <ul>
 *   <li>Left-click / arm swing — shoot (power scales with charge).</li>
 *   <li>Right-click — pass (ground roll).</li>
 *   <li>Sneak — charge a shot; sneak while sprinting — slide tackle.</li>
 * </ul>
 *
 * <p>On 1.21+ left-click often does not fire {@link PlayerInteractEvent}; we also
 * listen for {@link PlayerAnimationEvent} (arm swing).</p>
 */
public final class PlayerActionListener implements Listener {

    private static final long SHOOT_DEBOUNCE_MS = 100L;

    private final SoccerPlugin plugin;
    private final Map<UUID, Long> lastShootMs = new ConcurrentHashMap<>();

    public PlayerActionListener(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!InteractUtil.isMainHand(event)) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (tryShoot(event.getPlayer())) {
                event.setCancelled(true);
            }
            return;
        }
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (tryPass(event.getPlayer())) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * 1.21+ (and some Paper builds) deliver left-click as an arm swing instead of
     * {@link Action#LEFT_CLICK_AIR}.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Game game = plugin.games().getGame(event.getPlayer());
        if (game != null) {
            game.markReplayArmSwing(event.getPlayer());
        }
        tryShoot(event.getPlayer());
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Game game = plugin.games().getGame(event.getPlayer());
        if (game == null || game.blocksPlayerInput() || !game.getState().acceptsPlayerKicks()) {
            return;
        }
        game.setCharging(event.getPlayer(), event.isSneaking());
    }

    private boolean tryShoot(Player player) {
        Game game = plugin.games().getGame(player);
        if (game == null || game.blocksPlayerInput() || !game.getState().acceptsPlayerKicks()) {
            return false;
        }
        if (debounceShoot(player)) {
            return false;
        }
        game.shoot(player);
        return true;
    }

    private boolean tryPass(Player player) {
        Game game = plugin.games().getGame(player);
        if (game == null || game.blocksPlayerInput() || !game.getState().acceptsPlayerKicks()) {
            return false;
        }
        game.pass(player);
        return true;
    }

    private boolean debounceShoot(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        Long last = lastShootMs.get(id);
        if (last != null && now - last < SHOOT_DEBOUNCE_MS) {
            return true;
        }
        lastShootMs.put(id, now);
        return false;
    }
}
