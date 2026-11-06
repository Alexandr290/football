package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.util.InteractUtil;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates raw player input into soccer actions:
 * <ul>
 *   <li>Left-click / arm swing — shoot (power scales with charge).</li>
 *   <li>Right-click — pass (ground roll).</li>
 *   <li>F (swap hands) — lofted pass (5–7 blocks high).</li>
 *   <li>Q (drop item) — low cross (exactly 3 blocks high, 15–20 blocks far).</li>
 *   <li>Sneak — charge a shot; sneak while sprinting — slide tackle.</li>
 * </ul>
 *
 * <p>On 1.21+ left-click often does not fire {@link PlayerInteractEvent}; we also
 * listen for {@link PlayerAnimationEvent} (arm swing).</p>
 */
public final class PlayerActionListener implements Listener {

    private static final long SHOOT_DEBOUNCE_MS = 100L;
    private static final long LOFT_PASS_DEBOUNCE_MS = 250L;
    private static final long CROSS_PASS_DEBOUNCE_MS = 250L;

    private final SoccerPlugin plugin;
    private final Map<UUID, Long> lastShootMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLoftPassMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCrossPassMs = new ConcurrentHashMap<>();

    public PlayerActionListener(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!InteractUtil.isMainHand(event)) {
            return;
        }
        Player player = event.getPlayer();
        Game game = plugin.games().getGame(player);
        if (game != null && game.isHumanReferee(player.getUniqueId())) {
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
        if (game != null && game.isHumanReferee(event.getPlayer().getUniqueId())) {
            return;
        }
        if (game != null) {
            game.markReplayArmSwing(event.getPlayer());
        }
        tryShoot(event.getPlayer());
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Game game = plugin.games().getGame(event.getPlayer());
        if (game != null && game.isIntroRunning()) {
            if (event.isSneaking()) {
                game.voteSkipIntro(event.getPlayer());
            }
            return;
        }
        if (game == null || blocksInput(game, event.getPlayer())) {
            return;
        }
        if (event.isSneaking() && game.isPenaltyKeeper(event.getPlayer().getUniqueId())
                && (game.isPenaltyRestart() || game.isPenaltySaveWindow())) {
            game.penaltyKeeperDive(event.getPlayer());
            return;
        }
        if (!game.getState().acceptsPlayerKicks()) {
            return;
        }
        game.setCharging(event.getPlayer(), event.isSneaking());
    }

    /**
     * F (swap offhand) performs a lofted pass. The swap is cancelled so inventory
     * does not change during a match.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (tryLoftPass(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Q (drop item) performs a low cross. The drop is cancelled so kit stays in hand.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!plugin.games().isPlaying(player)) {
            return;
        }
        event.setCancelled(true);
        tryCrossPass(player);
    }

    private boolean blocksInput(Game game, Player player) {
        SoccerPlayer sp = game.getPlayer(player.getUniqueId());
        return game.blocksPlayerInput(sp);
    }

    private boolean tryShoot(Player player) {
        Game game = plugin.games().getGame(player);
        if (game == null || blocksInput(game, player) || !game.getState().acceptsPlayerKicks()) {
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
        if (game == null || blocksInput(game, player) || !game.getState().acceptsPlayerKicks()) {
            return false;
        }
        game.pass(player);
        return true;
    }

    private boolean tryLoftPass(Player player) {
        Game game = plugin.games().getGame(player);
        if (game == null || blocksInput(game, player) || !game.getState().acceptsPlayerKicks()) {
            return false;
        }
        if (debounceLoftPass(player)) {
            return false;
        }
        game.pass(player, true);
        return true;
    }

    private boolean tryCrossPass(Player player) {
        Game game = plugin.games().getGame(player);
        if (game == null || blocksInput(game, player) || !game.getState().acceptsPlayerKicks()) {
            return false;
        }
        if (game.isHumanReferee(player.getUniqueId())) {
            return false;
        }
        if (debounceCrossPass(player)) {
            return false;
        }
        if (!game.crossPass(player)) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
            return false;
        }
        return true;
    }

    private boolean debounceShoot(Player player) {
        return debounce(player, lastShootMs, SHOOT_DEBOUNCE_MS);
    }

    private boolean debounceLoftPass(Player player) {
        return debounce(player, lastLoftPassMs, LOFT_PASS_DEBOUNCE_MS);
    }

    private boolean debounceCrossPass(Player player) {
        return debounce(player, lastCrossPassMs, CROSS_PASS_DEBOUNCE_MS);
    }

    private static boolean debounce(Player player, Map<UUID, Long> lastMs, long windowMs) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        Long last = lastMs.get(id);
        if (last != null && now - last < windowMs) {
            return true;
        }
        lastMs.put(id, now);
        return false;
    }
}
