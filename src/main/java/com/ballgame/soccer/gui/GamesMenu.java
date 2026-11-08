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

            List<MatchRecord> all =
                    plugin.matchHistory().listRecentForParticipant(viewer.getUniqueId(), 20);
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
            } else {
                backButton(45, () -> MainHubMenu.open(plugin, viewer));
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

            int placed = 0;
            for (int slot = 27; slot <= 53 && placed < participants.size(); slot++) {
                if (slot == 49) {
                    continue;
                }
                ParticipantRecord p = participants.get(placed++);
                Material mat = p.isBot() ? Material.IRON_HELMET : Material.PLAYER_HEAD;
                String tag = p.isBot() ? msg.raw("games-gui-bot-tag") : "";
                String teamColor = p.getTeam().equals("RED") ? "&c" : "&9";
                ItemStack item = Items.named(mat,
                        teamColor + p.getName() + tag,
                        msg.format("games-gui-player-summary",
                                "goals", String.valueOf(p.getGoals()),
                                "assists", String.valueOf(p.getAssists()),
                                "rating", String.format(Locale.US, "%.1f", p.getRating())),
                        "",
                        msg.raw("games-gui-player-click"));
                final String pid = p.getUuid().toString();
                inventory.setItem(slot, item);
                registerClick(slot, () -> new PlayerMenu(plugin, viewer, matchId, pid).open());
            }

            backButton(49, () -> GamesMenu.open(plugin, viewer));
            inventory.setItem(49, Items.named(Material.ARROW,
                    msg.raw("games-gui-back"), msg.raw("games-gui-back-lore")));
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
            super(plugin, viewer, plugin.messages().raw("games-gui-player-title"), 6);
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
                inventory.setItem(22, Items.named(Material.BARRIER, "&c" + msg.raw("games-gui-not-found")));
                backButton(49, () -> {
                    if (match != null) {
                        new DetailMenu(plugin, viewer, matchId).open();
                    } else {
                        GamesMenu.open(plugin, viewer);
                    }
                });
                inventory.setItem(49, Items.named(Material.ARROW,
                        msg.raw("games-gui-back"), msg.raw("games-gui-back-lore")));
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

            String ratingText = String.format(Locale.US, "%.1f", p.getRating())
                    + " — " + msg.raw(gradeKey(p.getRating()));
            put(10, Material.NETHER_STAR, msg, "games-gui-stat-rating", ratingText);
            put(11, Material.GOLD_NUGGET, msg, "games-gui-stat-goals", String.valueOf(p.getGoals()));
            put(12, Material.SLIME_BALL, msg, "games-gui-stat-assists", String.valueOf(p.getAssists()));
            put(13, Material.ARROW, msg, "games-gui-stat-shots", String.valueOf(p.getShots()));
            put(14, Material.SPECTRAL_ARROW, msg, "games-gui-stat-shots-on-target",
                    String.valueOf(p.getShotsOnTarget()));
            put(15, Material.FLINT, msg, "games-gui-stat-shots-off-target",
                    String.valueOf(p.getShotsOffTarget()));
            put(16, Material.BLAZE_POWDER, msg, "games-gui-stat-chances-created",
                    String.valueOf(p.getChancesCreated()));

            put(19, Material.COAL, msg, "games-gui-stat-chances-missed",
                    String.valueOf(p.getChancesMissed()));
            put(20, Material.CLOCK, msg, "games-gui-stat-possession", p.getPossessionPct() + "%");
            put(21, Material.LEATHER_BOOTS, msg, "games-gui-stat-distance",
                    String.format(Locale.US, "%.1f м", p.getDistance()));
            put(22, Material.FEATHER, msg, "games-gui-stat-max-speed",
                    String.format(Locale.US, "%.1f м/с", p.getMaxSpeed()));
            put(23, Material.PAPER, msg, "games-gui-stat-passes", String.valueOf(p.getPasses()));
            put(24, Material.EMERALD, msg, "games-gui-stat-passes-completed",
                    String.valueOf(p.getPassesCompleted()));
            put(25, Material.COMPASS, msg, "games-gui-stat-pass-accuracy",
                    p.getPassAccuracyPct() + "%");

            put(28, Material.BOOK, msg, "games-gui-stat-passes-received",
                    String.valueOf(p.getPassesReceived()));
            put(29, Material.GLOWSTONE_DUST, msg, "games-gui-stat-key-passes",
                    String.valueOf(p.getKeyPasses()));
            put(30, Material.FISHING_ROD, msg, "games-gui-stat-crosses",
                    String.valueOf(p.getCrosses()));
            put(31, Material.IRON_SWORD, msg, "games-gui-stat-tackles",
                    String.valueOf(p.getTackles()));
            put(32, Material.DIAMOND_SWORD, msg, "games-gui-stat-tackles-won",
                    String.valueOf(p.getTacklesWon()));
            put(33, Material.SHIELD, msg, "games-gui-stat-interceptions",
                    String.valueOf(p.getInterceptions()));
            put(34, Material.RABBIT_FOOT, msg, "games-gui-stat-dribbles",
                    String.valueOf(p.getDribbles()));

            put(37, Material.IRON_CHESTPLATE, msg, "games-gui-stat-duels",
                    String.valueOf(p.getDuelsWon()));
            put(38, Material.WATER_BUCKET, msg, "games-gui-stat-saves",
                    String.valueOf(p.getSaves()));
            put(39, Material.PISTON, msg, "games-gui-stat-clearances",
                    String.valueOf(p.getClearances()));
            put(40, Material.SNOWBALL, msg, "games-gui-stat-touches",
                    String.valueOf(p.getTouches()));
            put(41, Material.ROTTEN_FLESH, msg, "games-gui-stat-fouls",
                    String.valueOf(p.getFouls()));
            put(42, Material.YELLOW_DYE, msg, "games-gui-stat-yellows",
                    String.valueOf(p.getYellowCards()));
            put(43, Material.RED_DYE, msg, "games-gui-stat-own-goals",
                    String.valueOf(p.getOwnGoals()));

            backButton(49, () -> new DetailMenu(plugin, viewer, matchId).open());
            inventory.setItem(49, Items.named(Material.ARROW,
                    msg.raw("games-gui-back"), msg.raw("games-gui-back-lore")));
        }

        private void put(int slot, Material material, Messages msg, String key, String value) {
            inventory.setItem(slot, Items.named(material, msg.raw(key), "&f" + value));
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

    private static String gradeKey(double rating) {
        if (rating >= 9.0) {
            return "games-gui-grade-9";
        }
        if (rating >= 8.0) {
            return "games-gui-grade-8";
        }
        if (rating >= 7.0) {
            return "games-gui-grade-7";
        }
        if (rating >= 6.2) {
            return "games-gui-grade-6";
        }
        if (rating >= 5.4) {
            return "games-gui-grade-5";
        }
        if (rating >= 4.5) {
            return "games-gui-grade-4";
        }
        return "games-gui-grade-low";
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
