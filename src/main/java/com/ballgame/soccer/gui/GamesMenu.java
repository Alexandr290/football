package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.history.MatchRecord;
import com.ballgame.soccer.history.ParticipantRecord;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class GamesMenu {

    private static final int PAGE_SIZE = 28;
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());

    private GamesMenu() {
    }

    public static void open(SoccerPlugin plugin, Player player) {
        new ListMenu(plugin, player, 0).open();
    }

    private static final class ListMenu extends AbstractMenu {

        private final int page;

        ListMenu(SoccerPlugin plugin, Player viewer, int page) {
            super(plugin, viewer, plugin.messages().raw("games-gui-list-title"), 6);
            this.page = page;
        }

        @Override
        protected void render() {
            fillBorder(Material.BLACK_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();

            inventory.setItem(4, Items.named(Material.BOOK,
                    msg.raw("games-gui-list-header"),
                    msg.raw("games-gui-list-header-lore")));

            List<MatchRecord> all = plugin.matchHistory().listRecent();
            int from = page * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, all.size());

            int slot = 10;
            for (int i = from; i < to; i++) {
                if (slot > 43) {
                    break;
                }
                if (slot % 9 == 0 || slot % 9 == 8) {
                    slot++;
                }
                MatchRecord match = all.get(i);
                String date = DATE_FMT.format(new Date(match.getFinishedAt()));
                String score = match.getRedScore() + " : " + match.getBlueScore();
                Material icon = match.getWinner().equals("DRAW")
                        ? Material.PAPER
                        : Material.SNOWBALL;
                ItemStack item = Items.named(icon,
                        "&e&l" + match.getArenaName(),
                        "&7" + date,
                        "&f" + score,
                        "&7" + winnerLabel(msg, match),
                        "",
                        msg.raw("games-gui-match-click"));
                final String matchId = match.getId();
                inventory.setItem(slot, item);
                registerClick(slot, () -> new DetailMenu(plugin, viewer, matchId).open());
                slot++;
            }

            if (all.isEmpty()) {
                inventory.setItem(22, Items.named(Material.BARRIER,
                        msg.raw("games-gui-no-matches"),
                        msg.raw("games-gui-no-matches-lore")));
            }

            if (page > 0) {
                backButton(45, () -> new ListMenu(plugin, viewer, page - 1).open());
                inventory.setItem(45, Items.named(Material.ARROW,
                        msg.raw("games-gui-prev-page"), "&7«"));
                registerClick(45, () -> new ListMenu(plugin, viewer, page - 1).open());
            }
            if (to < all.size()) {
                inventory.setItem(53, Items.named(Material.ARROW,
                        msg.raw("games-gui-next-page"), "&7»"));
                registerClick(53, () -> new ListMenu(plugin, viewer, page + 1).open());
            }
        }

        @Override
        public void handleClick(int slot, ClickType type) {
            Runnable action = MenuClicks.get(Integer.toHexString(System.identityHashCode(inventory)), slot);
            if (action != null) {
                clickSound();
                action.run();
            }
        }
    }

    private static final class DetailMenu extends AbstractMenu {

        private final String matchId;

        DetailMenu(SoccerPlugin plugin, Player viewer, String matchId) {
            super(plugin, viewer, plugin.messages().raw("games-gui-detail-title"), 6);
            this.matchId = matchId;
        }

        @Override
        protected void render() {
            fillBorder(Material.GRAY_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();
            MatchRecord match = plugin.matchHistory().get(matchId);
            if (match == null) {
                inventory.setItem(22, Items.named(Material.BARRIER, "&c" + msg.raw("games-gui-not-found")));
                backButton(49, () -> GamesMenu.open(plugin, viewer));
                return;
            }

            String date = DATE_FMT.format(new Date(match.getFinishedAt()));
            inventory.setItem(4, Items.named(Material.NETHER_STAR,
                    "&6&l" + match.getArenaName(),
                    "&7" + date,
                    "&f" + match.getRedScore() + " : " + match.getBlueScore(),
                    "&7" + winnerLabel(msg, match)));

            inventory.setItem(20, Items.named(Material.RED_WOOL,
                    msg.raw("games-gui-team-red"),
                    teamStatsLore(msg, match, "RED")));
            inventory.setItem(24, Items.named(Material.BLUE_WOOL,
                    msg.raw("games-gui-team-blue"),
                    teamStatsLore(msg, match, "BLUE")));

            List<ParticipantRecord> participants = new ArrayList<>(match.getParticipants());
            participants.sort(Comparator
                    .comparing((ParticipantRecord p) -> p.getTeam())
                    .thenComparing(ParticipantRecord::getName, String.CASE_INSENSITIVE_ORDER));

            int slot = 27;
            for (ParticipantRecord p : participants) {
                if (slot > 44) {
                    break;
                }
                Material mat = p.isBot() ? Material.IRON_HELMET : Material.PLAYER_HEAD;
                String tag = p.isBot() ? msg.raw("games-gui-bot-tag") : "";
                String teamColor = p.getTeam().equals("RED") ? "&c" : "&9";
                ItemStack item = Items.named(mat,
                        teamColor + p.getName() + tag,
                        msg.format("games-gui-player-summary",
                                "goals", String.valueOf(p.getGoals()),
                                "shots", String.valueOf(p.getShots()),
                                "rating", String.format(Locale.US, "%.1f", p.getRating())),
                        "",
                        msg.raw("games-gui-player-click"));
                final String pid = p.getUuid().toString();
                inventory.setItem(slot, item);
                registerClick(slot, () -> new PlayerMenu(plugin, viewer, matchId, pid).open());
                slot++;
            }

            backButton(49, () -> GamesMenu.open(plugin, viewer));
        }

        @Override
        public void handleClick(int slot, ClickType type) {
            Runnable action = MenuClicks.get(Integer.toHexString(System.identityHashCode(inventory)), slot);
            if (action != null) {
                clickSound();
                action.run();
            }
        }
    }

    private static final class PlayerMenu extends AbstractMenu {

        private final String matchId;
        private final String participantId;

        PlayerMenu(SoccerPlugin plugin, Player viewer, String matchId, String participantId) {
            super(plugin, viewer, plugin.messages().raw("games-gui-player-title"), 4);
            this.matchId = matchId;
            this.participantId = participantId;
        }

        @Override
        protected void render() {
            fillBorder(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();
            MatchRecord match = plugin.matchHistory().get(matchId);
            ParticipantRecord p = match != null ? match.findParticipant(participantId) : null;
            if (p == null) {
                inventory.setItem(13, Items.named(Material.BARRIER, "&c" + msg.raw("games-gui-not-found")));
                backButton(31, () -> {
                    if (match != null) {
                        new DetailMenu(plugin, viewer, matchId).open();
                    } else {
                        GamesMenu.open(plugin, viewer);
                    }
                });
                return;
            }

            String teamColor = p.getTeam().equals("RED") ? "&c" : "&9";
            String botTag = p.isBot() ? " " + msg.raw("games-gui-bot-tag") : "";
            Material mat = p.isBot() ? Material.IRON_HELMET : Material.GOLDEN_HELMET;

            inventory.setItem(4, Items.named(mat,
                    teamColor + "&l" + p.getName() + botTag,
                    msg.format("games-gui-player-team",
                            "team", p.getTeam().equals("RED")
                                    ? msg.raw("games-gui-team-red-short")
                                    : msg.raw("games-gui-team-blue-short"))));

            inventory.setItem(11, statItem(msg, "games-gui-stat-possession",
                    p.getPossessionPct() + "%"));
            inventory.setItem(12, statItem(msg, "games-gui-stat-shots",
                    String.valueOf(p.getShots())));
            inventory.setItem(13, statItem(msg, "games-gui-stat-goals",
                    String.valueOf(p.getGoals())));
            inventory.setItem(14, statItem(msg, "games-gui-stat-fouls",
                    String.valueOf(p.getFouls())));
            inventory.setItem(15, statItem(msg, "games-gui-stat-rating",
                    String.format(Locale.US, "%.1f", p.getRating()) + " / 10"));

            inventory.setItem(20, statItem(msg, "games-gui-stat-pass-accuracy",
                    p.getPassAccuracyPct() + "%"));
            inventory.setItem(21, statItem(msg, "games-gui-stat-passes",
                    String.valueOf(p.getPasses())));
            inventory.setItem(22, statItem(msg, "games-gui-stat-passes-received",
                    String.valueOf(p.getPassesReceived())));
            inventory.setItem(23, statItem(msg, "games-gui-stat-assists",
                    String.valueOf(p.getAssists())));

            backButton(31, () -> new DetailMenu(plugin, viewer, matchId).open());
        }

        @Override
        public void handleClick(int slot, ClickType type) {
            Runnable action = MenuClicks.get(Integer.toHexString(System.identityHashCode(inventory)), slot);
            if (action != null) {
                clickSound();
                action.run();
            }
        }
    }

    private static ItemStack statItem(Messages msg, String key, String value) {
        return Items.named(Material.PAPER,
                msg.raw(key),
                "&f" + value);
    }

    private static String[] teamStatsLore(Messages msg, MatchRecord match, String team) {
        boolean red = team.equals("RED");
        return new String[] {
                msg.format("games-gui-stat-possession-line",
                        "value", (red ? match.getRedPossessionPct() : match.getBluePossessionPct()) + "%"),
                msg.format("games-gui-stat-shots-line",
                        "value", String.valueOf(red ? match.getRedShots() : match.getBlueShots())),
                msg.format("games-gui-stat-goals-line",
                        "value", String.valueOf(red ? match.getRedGoals() : match.getBlueGoals())),
                msg.format("games-gui-stat-fouls-line",
                        "value", String.valueOf(red ? match.getRedFouls() : match.getBlueFouls())),
                msg.format("games-gui-stat-passes-line",
                        "value", String.valueOf(red ? match.getRedPasses() : match.getBluePasses())),
                "",
                msg.raw("games-gui-team-players-hint")
        };
    }

    private static String winnerLabel(Messages msg, MatchRecord match) {
        return switch (match.getWinner()) {
            case "RED" -> msg.raw("games-gui-winner-red");
            case "BLUE" -> msg.raw("games-gui-winner-blue");
            default -> msg.raw("games-gui-winner-draw");
        };
    }
}
