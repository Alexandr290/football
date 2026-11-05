package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.bot.MatchBot;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.referee.RefereeRestartType;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Referee decision panel: pick offender, then one-click restart for each team.
 */
public final class RefereeDecisionMenu extends AbstractMenu {

    private enum Step {
        PICK_OFFENDER,
        PICK_RESTART
    }

    private final Game game;
    private Step step = Step.PICK_OFFENDER;
    private UUID offenderId;

    private RefereeDecisionMenu(SoccerPlugin plugin, Player viewer, Game game) {
        super(plugin, viewer, plugin.messages().raw("referee-menu-title"), 6);
        this.game = game;
    }

    public static void open(SoccerPlugin plugin, Player referee, Game game) {
        new RefereeDecisionMenu(plugin, referee, game).open();
    }

    public static void openWithOffender(SoccerPlugin plugin, Player referee, Game game, UUID offenderId) {
        RefereeDecisionMenu menu = new RefereeDecisionMenu(plugin, referee, game);
        menu.offenderId = offenderId;
        menu.step = Step.PICK_RESTART;
        menu.open();
    }

    @Override
    protected void render() {
        fillBorder(Material.WHITE_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        if (step == Step.PICK_OFFENDER) {
            renderOffenderStep(msg);
        } else {
            renderRestartStep(msg);
        }
    }

    private void renderOffenderStep(Messages msg) {
        inventory.setItem(4, Items.named(Material.PAPER,
                msg.raw("referee-menu-step-offender"),
                msg.raw("referee-menu-step-offender-hint")));
        List<SoccerPlayer> list = activePlayers();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int idx = 0;
        for (SoccerPlayer sp : list) {
            if (idx >= slots.length) {
                break;
            }
            int slot = slots[idx++];
            TeamSide team = sp.getTeam();
            String color = team == TeamSide.RED
                    ? plugin.settings().redChatColor
                    : plugin.settings().blueChatColor;
            Material icon = team == TeamSide.RED ? Material.RED_WOOL : Material.BLUE_WOOL;
            if (!sp.isBot()) {
                inventory.setItem(slot, Items.named(icon,
                        color + "&l" + sp.getName(),
                        "&e▶ Click to select"));
            } else {
                inventory.setItem(slot, Items.named(Material.ZOMBIE_HEAD,
                        color + "&l" + sp.getName() + " &7(Bot)",
                        "&e▶ Click to select"));
            }
            UUID pick = sp.getUuid();
            registerClick(slot, () -> {
                offenderId = pick;
                step = Step.PICK_RESTART;
                open();
            });
        }
        inventory.setItem(49, Items.named(Material.BARRIER, "&c&lCancel", "&7Close without decision"));
        registerClick(49, viewer::closeInventory);
    }

    private void renderRestartStep(Messages msg) {
        SoccerSettings settings = plugin.settings();
        String offender = offenderName();

        inventory.setItem(4, Items.named(Material.WRITABLE_BOOK,
                msg.raw("referee-menu-step-restart"),
                "&7" + offender,
                msg.raw("referee-menu-step-restart-hint")));

        registerDecision(20, TeamSide.RED, RefereeRestartType.FREE_KICK,
                Material.OAK_SIGN, settings.redChatColor + "&l" + msg.raw("referee-restart-freekick"),
                settings.redName);
        registerDecision(21, TeamSide.RED, RefereeRestartType.CORNER_KICK,
                Material.ORANGE_BANNER, settings.redChatColor + "&l" + msg.raw("referee-restart-corner"),
                settings.redName);

        registerDecision(23, TeamSide.BLUE, RefereeRestartType.FREE_KICK,
                Material.OAK_SIGN, settings.blueChatColor + "&l" + msg.raw("referee-restart-freekick"),
                settings.blueName);
        registerDecision(24, TeamSide.BLUE, RefereeRestartType.CORNER_KICK,
                Material.ORANGE_BANNER, settings.blueChatColor + "&l" + msg.raw("referee-restart-corner"),
                settings.blueName);

        backButton(48, () -> {
            step = Step.PICK_OFFENDER;
            open();
        });
        inventory.setItem(49, Items.named(Material.BARRIER, "&c&lCancel", "&7Close without decision"));
        registerClick(49, viewer::closeInventory);
    }

    private void registerDecision(int slot, TeamSide team, RefereeRestartType restart,
                                  Material icon, String label, String teamName) {
        inventory.setItem(slot, Items.named(icon, label,
                "&7" + teamName,
                "&a▶ Confirm"));
        registerClick(slot, () -> confirm(restart, team));
    }

    private void confirm(RefereeRestartType restart, TeamSide team) {
        if (offenderId == null) {
            return;
        }
        viewer.closeInventory();
        viewer.playSound(viewer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        viewer.spawnParticle(Particle.TOTEM_OF_UNDYING, viewer.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        game.applyRefereeDecision(viewer, offenderId, restart, team);
    }

    private List<SoccerPlayer> activePlayers() {
        List<SoccerPlayer> list = new ArrayList<>();
        for (SoccerPlayer sp : game.getParticipants()) {
            if (sp.isReferee() || sp.isSentOff() || sp.getTeam() == null || sp.isBot()) {
                continue;
            }
            if (sp.getBukkitPlayer() != null && sp.getBukkitPlayer().isOnline()) {
                list.add(sp);
            }
        }
        for (MatchBot bot : game.getBots()) {
            SoccerPlayer profile = bot.getProfile();
            if (profile != null && profile.getTeam() != null) {
                list.add(profile);
            }
        }
        return list;
    }

    private String offenderName() {
        if (offenderId == null) {
            return "?";
        }
        SoccerPlayer sp = game.getPlayer(offenderId);
        return sp != null ? sp.getName() : "?";
    }
}
