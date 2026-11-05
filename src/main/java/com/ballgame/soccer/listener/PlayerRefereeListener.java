package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.bot.MatchBot;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.gui.RefereeDecisionMenu;
import com.ballgame.soccer.referee.RefereeItems;
import com.ballgame.soccer.util.InteractUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Human referee tools: whistle, decision menu, VAR, start/end match and stoppage time.
 */
public final class PlayerRefereeListener implements Listener {

    private static final int DEFAULT_ADDED_SECONDS = 60;
    private static final int VAR_CLIP_SECONDS = 5;

    private final SoccerPlugin plugin;

    public PlayerRefereeListener(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!InteractUtil.isMainHand(event)) {
            return;
        }
        Player player = event.getPlayer();
        Game game = plugin.games().getGame(player);
        if (game == null || !game.isHumanReferee(player.getUniqueId())) {
            return;
        }

        String type = RefereeItems.readType(plugin, resolveItem(event, player));
        if (type == null) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> handleTool(game, player, type));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player referee = event.getPlayer();
        Game game = plugin.games().getGame(referee);
        if (game == null || !game.isHumanReferee(referee.getUniqueId())) {
            return;
        }

        String type = RefereeItems.readType(plugin, referee.getInventory().getItem(event.getHand()));
        if (!RefereeItems.DECISION.equals(type)) {
            return;
        }

        SoccerPlayer offender = resolveOffender(game, event.getRightClicked());
        if (offender == null) {
            plugin.messages().send(referee, "referee-card-no-target");
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            ensureStopped(game, referee);
            if (game.isAwaitingRefereeDecision()) {
                RefereeDecisionMenu.openWithOffender(plugin, referee, game, offender.getUuid());
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Game game = plugin.games().getGame(player);
        if (game != null && game.isHumanReferee(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private ItemStack resolveItem(PlayerInteractEvent event, Player player) {
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            item = player.getInventory().getItemInMainHand();
        }
        return item;
    }

    private void handleTool(Game game, Player referee, String type) {
        switch (type) {
            case RefereeItems.WHISTLE -> game.toggleRefereeWhistle(referee);
            case RefereeItems.DECISION -> openDecisionMenu(game, referee);
            case RefereeItems.VAR -> game.beginVarReview(referee, VAR_CLIP_SECONDS);
            case RefereeItems.START -> game.refereeStartMatch(referee);
            case RefereeItems.END -> game.refereeEndMatch(referee);
            case RefereeItems.ADD_TIME -> {
                int seconds = referee.isSneaking() ? 30 : DEFAULT_ADDED_SECONDS;
                game.refereeAddStoppageTime(referee, seconds);
            }
            case RefereeItems.RESUME -> game.resumeRefereePlay();
            default -> {
            }
        }
    }

    private void openDecisionMenu(Game game, Player referee) {
        ensureStopped(game, referee);
        if (game.isAwaitingRefereeDecision()) {
            RefereeDecisionMenu.open(plugin, referee, game);
        }
    }

    private void ensureStopped(Game game, Player referee) {
        if (!game.isAwaitingRefereeDecision()) {
            game.toggleRefereeWhistle(referee);
        }
    }

    private SoccerPlayer resolveOffender(Game game, Entity clicked) {
        if (clicked instanceof Player target) {
            SoccerPlayer sp = game.getPlayer(target.getUniqueId());
            if (sp != null && !sp.isReferee() && !sp.isSentOff() && sp.getTeam() != null) {
                return sp;
            }
            return null;
        }
        for (MatchBot bot : game.getBots()) {
            if (bot.ownsEntity(clicked)) {
                SoccerPlayer profile = bot.getProfile();
                if (profile != null && profile.getTeam() != null) {
                    return profile;
                }
            }
        }
        return null;
    }
}
