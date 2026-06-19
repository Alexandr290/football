package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.championship.ChampionshipState;
import com.ballgame.soccer.championship.ChampionshipType;
import com.ballgame.soccer.championship.ChampFixture;
import com.ballgame.soccer.championship.ChampRenamePrompt;
import com.ballgame.soccer.championship.ChampSchedulePrompt;
import com.ballgame.soccer.championship.ChampStanding;
import com.ballgame.soccer.championship.ChampTeam;
import com.ballgame.soccer.championship.FixtureState;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.util.Items;
import com.ballgame.soccer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ChampMenus {

    private ChampMenus() {
    }

    public static void openMain(SoccerPlugin plugin, Player player) {
        new MainMenu(plugin, player).open();
    }

    private static final class MainMenu extends AbstractMenu {

        MainMenu(SoccerPlugin plugin, Player viewer) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-main-title"), 6);
        }

        @Override
        protected void render() {
            fillBorder(Material.BLACK_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();
            inventory.setItem(4, Items.named(Material.GOLDEN_HELMET,
                    msg.raw("champ-gui-main-header"),
                    msg.raw("champ-gui-main-header-lore")));

            List<Championship> list = new ArrayList<>(plugin.championships().getAll());
            list.sort(Comparator.comparingLong(Championship::getCreatedAt).reversed());

            int slot = 10;
            for (Championship championship : list) {
                if (slot > 43) {
                    break;
                }
                if (slot % 9 == 0 || slot % 9 == 8) {
                    slot++;
                }
                Material icon = championship.getType() == ChampionshipType.LEAGUE
                        ? Material.GOLDEN_BOOTS : Material.GOLDEN_HELMET;
                ItemStack item = Items.named(icon,
                        "&e&l" + championship.getDisplayName(),
                        "&7Type: &f" + typeLabel(msg, championship.getType()),
                        "&7Status: &f" + championship.getState().display(),
                        "&7Teams: &f" + championship.teamCount(),
                        "&7Arena: &f" + nullOr(championship.getArenaName(), "Any"),
                        "",
                        "&aClick to open");
                final String id = championship.getId();
                inventory.setItem(slot, item);
                registerClick(slot, () -> new DetailMenu(plugin, viewer, id).open());
                slot++;
            }

            if (list.isEmpty()) {
                inventory.setItem(22, Items.named(Material.BARRIER,
                        msg.raw("champ-gui-no-events"),
                        msg.raw("champ-gui-no-events-lore")));
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

        private final String championshipId;

        DetailMenu(SoccerPlugin plugin, Player viewer, String championshipId) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-detail-title"), 6);
            this.championshipId = championshipId;
        }

        @Override
        protected void render() {
            fillBorder(Material.GRAY_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                inventory.setItem(22, Items.named(Material.BARRIER, "&cNot found"));
                backButton(49, () -> openMain(plugin, viewer));
                return;
            }

            ChampTeam myTeam = championship.findTeamOf(viewer.getUniqueId());
            List<ChampFixture> pending = championship.pendingFixturesFor(viewer.getUniqueId());

            inventory.setItem(4, Items.named(Material.NETHER_STAR,
                    "&6&l" + championship.getDisplayName(),
                    "&7" + typeLabel(msg, championship.getType()) + " &8| &f" + championship.getState().display(),
                    "&7Teams: &f" + championship.teamCount(),
                    "&7Arena: &f" + nullOr(championship.getArenaName(), "-")));

            if (championship.isRegistrationOpen() && myTeam == null) {
                    inventory.setItem(20, Items.named(Material.LIME_WOOL,
                            msg.raw("champ-gui-create-team"),
                            msg.raw("champ-gui-create-team-lore")));
                    registerClick(20, () -> {
                        String name = viewer.getName() + "'s FC";
                        ChampTeam team = plugin.championships().createTeam(championship, viewer, name);
                        if (team != null) {
                            msg.send(viewer, "champ-team-created", "team", team.getName());
                            open();
                        } else {
                            msg.send(viewer, "champ-team-create-failed");
                        }
                    });
            }
            if (myTeam != null && plugin.championships().canLeaveTeam(championship)) {
                    inventory.setItem(20, Items.named(Material.RED_WOOL,
                            msg.raw("champ-gui-leave-team"),
                            "&7" + myTeam.getName(),
                            msg.raw("champ-gui-leave-team-lore")));
                    registerClick(20, () -> {
                        plugin.championships().leaveTeam(championship, viewer);
                        msg.send(viewer, "champ-team-left");
                        open();
                    });
                    if (viewer.getUniqueId().equals(myTeam.getCaptain())
                            && championship.isRegistrationOpen()) {
                        inventory.setItem(21, Items.named(Material.NAME_TAG,
                                msg.raw("champ-gui-rename-team"),
                                msg.raw("champ-gui-rename-team-lore")));
                        registerClick(21, () -> ChampRenamePrompt.startTeam(
                                plugin, viewer, championshipId, myTeam.getId()));
                    }
            }

            if (viewer.hasPermission("soccer.admin")) {
                inventory.setItem(47, Items.named(Material.NAME_TAG,
                        msg.raw("champ-gui-rename-tournament"),
                        msg.raw("champ-gui-rename-tournament-lore")));
                registerClick(47, () -> ChampRenamePrompt.startChampionship(plugin, viewer, championshipId));

                if (championship.isRegistrationOpen()) {
                    List<String> startLore = new ArrayList<>();
                    startLore.add(msg.raw("champ-gui-start-lore"));
                    if (championship.getType() == ChampionshipType.KNOCKOUT) {
                        startLore.add(msg.format("champ-gui-start-wc-teams",
                                "required", String.valueOf(plugin.settings().worldCupTeamSlots())));
                        startLore.add(msg.format("champ-gui-start-teams",
                                "count", String.valueOf(championship.teamCount()),
                                "min", String.valueOf(plugin.settings().worldCupTeamSlots())));
                        startLore.add(msg.format("champ-wc-scheduled-progress",
                                "count", String.valueOf(plugin.championships().countWorldCupGroupFixtures(championship)),
                                "required", String.valueOf(plugin.championships().worldCupRequiredGroupMatches())));
                    } else {
                        startLore.add(msg.format("champ-gui-start-teams",
                                "count", String.valueOf(championship.teamCount()),
                                "min", String.valueOf(plugin.settings().champMinTeamsToStart)));
                    }
                    inventory.setItem(38, Items.named(Material.LIME_CONCRETE,
                            msg.raw("champ-gui-start"),
                            startLore.toArray(new String[0])));
                    registerClick(38, () -> {
                        if (plugin.championships().tryStartChampionship(viewer, championshipId)) {
                            open();
                        }
                    });
                }
            }

            inventory.setItem(22, Items.named(Material.PLAYER_HEAD,
                    msg.raw("champ-gui-teams"),
                    msg.raw("champ-gui-teams-lore")));
            registerClick(22, () -> new TeamsMenu(plugin, viewer, championshipId).open());

            if (championship.getType() == ChampionshipType.LEAGUE) {
                inventory.setItem(24, Items.named(Material.BOOK,
                        msg.raw("champ-gui-table"),
                        TournamentBracketView.hasBracket(championship)
                                ? msg.raw("champ-gui-table-bracket-lore")
                                : msg.raw("champ-gui-table-lore")));
                registerClick(24, () -> new TableMenu(plugin, viewer, championshipId).open());
                if (TournamentBracketView.hasBracket(championship)) {
                    inventory.setItem(25, Items.named(Material.GOLDEN_SWORD,
                            msg.raw("champ-gui-playoffs"),
                            msg.raw("champ-gui-playoffs-lore")));
                    registerClick(25, () -> new BracketMenu(plugin, viewer, championshipId).open());
                }
            } else {
                inventory.setItem(24, Items.named(Material.MAP,
                        msg.raw("champ-gui-world-cup-bracket"),
                        msg.raw("champ-gui-world-cup-bracket-lore")));
                registerClick(24, () -> new BracketMenu(plugin, viewer, championshipId).open());
                inventory.setItem(25, Items.named(Material.BOOK,
                        msg.raw("champ-gui-table"),
                        msg.raw("champ-gui-table-lore")));
                registerClick(25, () -> new TableMenu(plugin, viewer, championshipId).open());
            }

            inventory.setItem(31, Items.named(Material.PAPER,
                    msg.raw("champ-gui-fixtures"),
                    "&7Pending for you: &f" + pending.size()));
            registerClick(31, () -> new FixturesMenu(plugin, viewer, championshipId).open());

            if (championship.isRegistrationOpen()
                    || (championship.getState() == ChampionshipState.ACTIVE
                    && championship.getType() == ChampionshipType.LEAGUE)) {
                inventory.setItem(29, Items.named(Material.COMPASS,
                        msg.raw("champ-gui-schedule-match"),
                        scheduleLore(msg, plugin, championship)));
                registerClick(29, () -> new ScheduleOpponentMenu(plugin, viewer, championshipId).open());
            }

        boolean calendarReady = championship.getType() != ChampionshipType.KNOCKOUT
                    || plugin.championships().isWorldCupGroupCalendarComplete(championship);
            if ((championship.getState() == ChampionshipState.ACTIVE
                    || championship.getState() == ChampionshipState.REGISTRATION)
                    && !pending.isEmpty()) {
                final ChampFixture joinFixture = pending.get(0);
                boolean canJoin = plugin.championships().canJoinFixture(championship, joinFixture);
                if (canJoin) {
                    inventory.setItem(40, Items.named(Material.EMERALD,
                            msg.raw("champ-gui-join-match"),
                            msg.raw("champ-gui-join-match-lore")));
                    registerClick(40, () -> {
                        if (plugin.championships().joinFixtureMatch(viewer, championship, joinFixture)) {
                            msg.send(viewer, "champ-joined-match", "arena", championship.getArenaName());
                            viewer.closeInventory();
                        } else {
                            msg.send(viewer, "champ-join-failed");
                        }
                    });
                } else if (championship.getType() == ChampionshipType.KNOCKOUT) {
                    int scheduled = plugin.championships().countWorldCupGroupFixtures(championship);
                    int required = plugin.championships().worldCupRequiredGroupMatches();
                    inventory.setItem(40, Items.named(Material.ORANGE_STAINED_GLASS,
                            msg.raw("champ-wc-need-scheduled-title"),
                            msg.format("champ-wc-scheduled-progress",
                                    "count", String.valueOf(scheduled),
                                    "required", String.valueOf(required)),
                            !calendarReady
                                    ? msg.raw("champ-wc-need-scheduled-lore")
                                    : msg.raw("champ-wc-teams-not-ready-lore")));
                }
            }

            if (championship.getState() == ChampionshipState.FINISHED
                    && championship.getChampionTeamId() != null) {
                ChampTeam champ = championship.getTeam(championship.getChampionTeamId());
                if (champ != null) {
                    inventory.setItem(13, Items.named(Material.GOLDEN_APPLE,
                            msg.raw("champ-gui-champion"),
                            "&6&l" + champ.getName()));
                }
            }

            backButton(49, () -> openMain(plugin, viewer));
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

    private static final class TableMenu extends AbstractMenu {

        private final String championshipId;
        private ViewMode viewMode = ViewMode.STANDINGS;

        private enum ViewMode {
            STANDINGS,
            BRACKET
        }

        TableMenu(SoccerPlugin plugin, Player viewer, String championshipId) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-table-title"), 6);
            this.championshipId = championshipId;
            Championship championship = plugin.championships().get(championshipId);
            if (championship != null && TournamentBracketView.hasBracket(championship)) {
                viewMode = ViewMode.BRACKET;
            }
        }

        TableMenu(SoccerPlugin plugin, Player viewer, String championshipId, ViewMode mode) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-table-title"), 6);
            this.championshipId = championshipId;
            this.viewMode = mode;
        }

        @Override
        protected void render() {
            Messages msg = plugin.messages();
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                return;
            }

            boolean hasBracket = TournamentBracketView.hasBracket(championship);

            if (viewMode == ViewMode.BRACKET && hasBracket) {
                TournamentBracketView.render(plugin, msg, championship, viewer, inventory, this::registerClick);
            } else {
                renderStandings(msg, championship, hasBracket);
            }

            if (hasBracket) {
                inventory.setItem(45, Items.named(
                        viewMode == ViewMode.STANDINGS ? Material.LIME_DYE : Material.GRAY_DYE,
                        msg.raw("champ-table-tab-standings"),
                        msg.raw("champ-table-tab-standings-lore")));
                registerClick(45, () -> new TableMenu(plugin, viewer, championshipId, ViewMode.STANDINGS).open());

                inventory.setItem(53, Items.named(
                        viewMode == ViewMode.BRACKET ? Material.LIME_DYE : Material.GRAY_DYE,
                        msg.raw("champ-table-tab-bracket"),
                        msg.raw("champ-table-tab-bracket-lore")));
                registerClick(53, () -> new TableMenu(plugin, viewer, championshipId, ViewMode.BRACKET).open());
            }

            backButton(49, () -> new DetailMenu(plugin, viewer, championshipId).open());
        }

        private void renderStandings(Messages msg, Championship championship, boolean hasBracket) {
            fillBorder(Material.LIME_STAINED_GLASS_PANE);
            List<ChampStanding> rows = new ArrayList<>(championship.getStandings().values());
            rows.sort(Comparator.comparingInt(ChampStanding::getPoints).reversed()
                    .thenComparingInt(ChampStanding::getGoalDifference).reversed()
                    .thenComparingInt(ChampStanding::getGoalsFor).reversed());

            int playoffSpots = Math.min(plugin.settings().champPlayoffQualifiers, rows.size());
            int leaderPoints = rows.isEmpty() ? 1 : Math.max(1, rows.get(0).getPoints());

            inventory.setItem(4, Items.named(Material.GOLDEN_BOOTS,
                    msg.raw("champ-table-header"),
                    hasBracket ? msg.raw("champ-table-header-bracket-hint") : msg.raw("champ-table-header-lore")));

            inventory.setItem(2, Items.named(Material.PAPER,
                    msg.raw("champ-table-col-pos"), "&8#"));
            inventory.setItem(3, Items.named(Material.PAPER,
                    msg.raw("champ-table-col-team"), "&8Club"));
            inventory.setItem(5, Items.named(Material.PAPER,
                    msg.raw("champ-table-col-stats"), "&8P W D L"));
            inventory.setItem(6, Items.named(Material.PAPER,
                    msg.raw("champ-table-col-goals"), "&8GF GA GD"));
            inventory.setItem(7, Items.named(Material.PAPER,
                    msg.raw("champ-table-col-points"), "&8Pts"));

            int[] rowSlots = {19, 20, 21, 22, 23, 24, 25, 26, 28, 29, 30, 31, 32, 33, 34, 35};
            int pos = 1;
            for (int i = 0; i < rows.size() && i < rowSlots.length; i++) {
                ChampStanding row = rows.get(i);
                ChampTeam team = championship.getTeam(row.getTeamId());
                String teamName = team != null ? team.getName() : row.getTeamId();
                int slot = rowSlots[i];

                Material icon = pos == 1 ? Material.GOLDEN_HELMET
                        : pos <= 3 ? Material.IRON_HELMET
                        : pos <= playoffSpots ? Material.LIME_DYE
                        : Material.PLAYER_HEAD;

                String posLabel = positionLabel(msg, pos);
                String zone = zoneLabel(msg, pos, playoffSpots, championship);
                String bar = pointsBar(row.getPoints(), leaderPoints);

                inventory.setItem(slot, Items.named(icon,
                        posLabel + " &f" + teamName + zone,
                        "&7" + row.getPlayed() + "  &a" + row.getWon()
                                + "  &e" + row.getDrawn() + "  &c" + row.getLost(),
                        "&7GF &f" + row.getGoalsFor() + " &7GA &f" + row.getGoalsAgainst()
                                + " &7GD &f" + formatGd(row.getGoalDifference()),
                        bar,
                        "&6&l" + row.getPoints() + " &7" + msg.raw("champ-table-points-label")));
                pos++;
            }

            if (rows.isEmpty()) {
                inventory.setItem(22, Items.named(Material.BARRIER,
                        msg.raw("champ-table-empty"),
                        msg.raw("champ-table-empty-lore")));
            }
        }

        private static String positionLabel(Messages msg, int pos) {
            return switch (pos) {
                case 1 -> "&6&l#1";
                case 2 -> "&7&l#2";
                case 3 -> "&c&l#3";
                default -> "&f#" + pos;
            };
        }

        private String zoneLabel(Messages msg, int pos, int playoffSpots, Championship championship) {
            if (!championship.isPlayoffsActive() && !championship.hasPlayoffFixtures()
                    && championship.getState() != ChampionshipState.FINISHED) {
                if (pos <= playoffSpots && playoffSpots >= plugin.settings().champPlayoffMinTeams) {
                    return " &a★";
                }
            }
            if (championship.isPlayoffsActive() || championship.hasPlayoffFixtures()) {
                if (pos <= playoffSpots) {
                    return " &2●";
                }
            }
            return "";
        }

        private static String formatGd(int gd) {
            return gd > 0 ? "+" + gd : String.valueOf(gd);
        }

        private static String pointsBar(int points, int leaderPoints) {
            int width = 10;
            int filled = Math.min(width, (int) Math.round((points * 1.0 / leaderPoints) * width));
            StringBuilder bar = new StringBuilder("&8");
            for (int i = 0; i < width; i++) {
                bar.append(i < filled ? "▮" : "▯");
            }
            return bar.toString();
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

    private static final class BracketMenu extends AbstractMenu {

        private final String championshipId;
        private final TournamentBracketView.BracketPage page;

        BracketMenu(SoccerPlugin plugin, Player viewer, String championshipId) {
            this(plugin, viewer, championshipId, null);
        }

        BracketMenu(SoccerPlugin plugin, Player viewer, String championshipId,
                    TournamentBracketView.BracketPage page) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-bracket-title"), 6);
            this.championshipId = championshipId;
            Championship championship = plugin.championships().get(championshipId);
            if (page != null) {
                this.page = page;
            } else if (championship != null) {
                this.page = TournamentBracketView.defaultPage(championship);
            } else {
                this.page = TournamentBracketView.BracketPage.GROUPS;
            }
        }

        @Override
        protected void render() {
            Messages msg = plugin.messages();
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                fillBorder(Material.RED_STAINED_GLASS_PANE);
                return;
            }
            TournamentBracketView.render(plugin, msg, championship, viewer, inventory, page,
                    this::registerClick,
                    target -> new BracketMenu(plugin, viewer, championshipId, target).open(),
                    groupId -> new GroupResultsMenu(plugin, viewer, championshipId, groupId).open());
            backButton(49, () -> new DetailMenu(plugin, viewer, championshipId).open());
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

    private static final class GroupResultsMenu extends AbstractMenu {

        private final String championshipId;
        private final String groupId;

        GroupResultsMenu(SoccerPlugin plugin, Player viewer, String championshipId, String groupId) {
            super(plugin, viewer,
                    plugin.messages().format("champ-group-results-title", "group", groupId), 6);
            this.championshipId = championshipId;
            this.groupId = groupId;
        }

        @Override
        protected void render() {
            fillBorder(Material.CYAN_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                return;
            }
            plugin.championships().syncWorldCupGroupsFromFixtures(championship);

            int advance = plugin.settings().champAdvancePerGroup;
            inventory.setItem(4, Items.named(Material.WHITE_BANNER,
                    msg.raw("champ-bracket-group") + " &e&l" + groupId,
                    msg.raw("champ-group-results-subtitle")));

            List<ChampTeam> groupTeams = new ArrayList<>(championship.getTeamsInGroup(groupId));
            groupTeams.sort(Comparator.comparing(ChampTeam::getName));

            if (groupTeams.size() >= 1) {
                inventory.setItem(19, teamCard(championship, groupTeams.get(0)));
            }
            if (groupTeams.size() >= 2) {
                inventory.setItem(25, teamCard(championship, groupTeams.get(1)));
            }

            ChampFixture match = plugin.championships().findGroupFixture(championship, groupId);
            if (match != null) {
                Material mat = Material.GRAY_CONCRETE;
                if (match.getState() == FixtureState.LIVE) {
                    mat = Material.ORANGE_CONCRETE;
                } else if (match.getState() == FixtureState.COMPLETED) {
                    mat = Material.GREEN_CONCRETE;
                }
                List<String> matchLore = new ArrayList<>();
                matchLore.add("&f" + match.sideADisplay(championship));
                matchLore.add("&8  ×");
                matchLore.add("&f" + match.sideBDisplay(championship));
                matchLore.add("");
                if (match.getState() == FixtureState.COMPLETED || match.getState() == FixtureState.LIVE) {
                    matchLore.add("&e&l  " + match.getScoreA() + "  :  " + match.getScoreB());
                    if (match.getState() == FixtureState.COMPLETED && match.getWinnerTeamId() != null) {
                        ChampTeam winner = championship.getTeam(match.getWinnerTeamId());
                        if (winner != null) {
                            matchLore.add("");
                            matchLore.add(msg.format("champ-group-results-winner", "team", winner.getName()));
                        }
                    }
                } else {
                    matchLore.add("&8  —  :  —");
                }
                matchLore.add("");
                matchLore.add("&7" + msg.raw("champ-bracket-status") + " &f" + match.getState().display());

                inventory.setItem(22, Items.named(mat,
                        msg.raw("champ-group-results-match"),
                        matchLore.toArray(new String[0])));

                boolean canJoin = plugin.championships().canJoinFixture(championship, match)
                        && match.involvesPlayer(championship, viewer.getUniqueId())
                        && (match.getState() == FixtureState.PENDING
                        || match.getState() == FixtureState.LIVE);
                if (canJoin) {
                    inventory.setItem(40, Items.named(Material.EMERALD,
                            msg.raw("champ-gui-join-match"),
                            msg.raw("champ-gui-fixture-join-lore")));
                    registerClick(40, () -> {
                        if (plugin.championships().joinFixtureMatch(viewer, championship, match)) {
                            msg.send(viewer, "champ-joined-match", "arena", championship.getArenaName());
                            viewer.closeInventory();
                        } else {
                            msg.send(viewer, "champ-join-failed");
                        }
                    });
                }
            } else {
                inventory.setItem(22, Items.named(Material.BARRIER,
                        msg.raw("champ-group-results-no-match"),
                        msg.raw("champ-bracket-group-match-empty-lore")));
            }

            inventory.setItem(13, Items.named(Material.PAPER,
                    msg.raw("champ-group-results-standings"),
                    msg.raw("champ-table-col-stats")));

            List<ChampStanding> rows = groupStandings(championship, groupId);
            int[] slots = {28, 29, 30, 31, 32, 33, 34, 35};
            for (int i = 0; i < rows.size() && i < slots.length; i++) {
                ChampStanding row = rows.get(i);
                ChampTeam team = championship.getTeam(row.getTeamId());
                String name = team != null ? team.getName() : "?";
                String prefix = i < advance ? "&a" : "&7";
                inventory.setItem(slots[i], Items.named(Material.BOOK,
                        prefix + "&l" + (i + 1) + ". &f" + name,
                        "&7" + msg.raw("champ-table-col-points") + " &e" + row.getPoints(),
                        "&7P W D L: &f" + row.getPlayed() + " "
                                + row.getWon() + " " + row.getDrawn() + " " + row.getLost(),
                        "&7GF:GA &f" + row.getGoalsFor() + ":" + row.getGoalsAgainst(),
                        "&7GD &f" + row.getGoalDifference()));
            }

            backButton(49, () -> new BracketMenu(plugin, viewer, championshipId,
                    TournamentBracketView.BracketPage.GROUPS).open());
        }

        private ItemStack teamCard(Championship championship, ChampTeam team) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Text.color("&b&l" + team.getName()));
                List<String> lore = new ArrayList<>();
                lore.add(Text.color("&7" + plugin.messages().raw("champ-gui-teams") + ": &f" + team.size()));
                ChampStanding standing = championship.getStanding(team.getId());
                if (standing != null && standing.getPlayed() > 0) {
                    lore.add(Text.color("&7Pts: &e" + standing.getPoints()));
                }
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            return head;
        }

        private List<ChampStanding> groupStandings(Championship championship, String groupId) {
            List<ChampStanding> rows = new ArrayList<>();
            for (ChampTeam team : championship.getTeamsInGroup(groupId)) {
                ChampStanding standing = championship.getStanding(team.getId());
                if (standing != null) {
                    rows.add(standing);
                }
            }
            rows.sort(Comparator.comparingInt(ChampStanding::getPoints).reversed()
                    .thenComparingInt(ChampStanding::getGoalDifference).reversed()
                    .thenComparingInt(ChampStanding::getGoalsFor).reversed());
            return rows;
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

    private static final class TeamsMenu extends AbstractMenu {

        private final String championshipId;

        TeamsMenu(SoccerPlugin plugin, Player viewer, String championshipId) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-teams-title"), 6);
            this.championshipId = championshipId;
        }

        @Override
        protected void render() {
            fillBorder(Material.BLUE_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                return;
            }

            int slot = 10;
            for (ChampTeam team : championship.getTeams().values()) {
                if (slot > 43) {
                    break;
                }
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                ItemMeta meta = head.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(Text.color("&b&l" + team.getName()
                            + (team.isEliminated() ? " &c(OUT)" : "")));
                    List<String> lore = new ArrayList<>();
                    lore.add(Text.color("&7Members: &f" + team.size()));
                    lore.add(Text.color("&7Captain: &f" + nameOf(team.getCaptain())));
                    boolean inThisTeam = team.contains(viewer.getUniqueId());
                    boolean canLeave = plugin.championships().canLeaveTeam(championship);
                    boolean canJoin = plugin.championships().canJoinTeam(championship);
                    if (inThisTeam && canLeave) {
                        lore.add(Text.color(""));
                        lore.add(Text.color("&cClick to leave this team"));
                    } else if (canJoin && !inThisTeam
                            && championship.findTeamOf(viewer.getUniqueId()) == null) {
                        lore.add(Text.color(""));
                        lore.add(Text.color("&aClick to join this team"));
                    }
                    meta.setLore(lore);
                    head.setItemMeta(meta);
                }
                inventory.setItem(slot, head);
                final String teamId = team.getId();
                final boolean inThisTeam = team.contains(viewer.getUniqueId());
                if (inThisTeam && plugin.championships().canLeaveTeam(championship)) {
                    registerClick(slot, () -> {
                        plugin.championships().leaveTeam(championship, viewer);
                        msg.send(viewer, "champ-team-left");
                        open();
                    });
                } else if (!inThisTeam
                        && plugin.championships().canJoinTeam(championship)
                        && championship.findTeamOf(viewer.getUniqueId()) == null) {
                    registerClick(slot, () -> {
                        if (plugin.championships().joinTeam(championship, viewer, teamId)) {
                            msg.send(viewer, "champ-joined-team", "team", team.getName());
                            open();
                        } else {
                            msg.send(viewer, "champ-join-team-failed");
                        }
                    });
                }
                slot++;
            }
            backButton(49, () -> new DetailMenu(plugin, viewer, championshipId).open());
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

    private static final class FixturesMenu extends AbstractMenu {

        private final String championshipId;

        FixturesMenu(SoccerPlugin plugin, Player viewer, String championshipId) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-fixtures-title"), 6);
            this.championshipId = championshipId;
        }

        @Override
        protected void render() {
            fillBorder(Material.YELLOW_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                return;
            }

            boolean wcKnockout = championship.getType() == ChampionshipType.KNOCKOUT;
            boolean calendarReady = !wcKnockout
                    || plugin.championships().isWorldCupGroupCalendarComplete(championship);

            if (wcKnockout && championship.getState() != ChampionshipState.FINISHED) {
                int scheduled = plugin.championships().countWorldCupGroupFixtures(championship);
                int required = plugin.championships().worldCupRequiredGroupMatches();
                inventory.setItem(4, Items.named(
                        calendarReady ? Material.LIME_DYE : Material.ORANGE_DYE,
                        msg.raw("champ-gui-fixtures-title"),
                        msg.format("champ-wc-scheduled-progress",
                                "count", String.valueOf(scheduled),
                                "required", String.valueOf(required)),
                        calendarReady
                                ? msg.raw("champ-wc-calendar-ready-lore")
                                : msg.raw("champ-wc-need-scheduled-lore")));
            }

            int slot = 10;
            for (ChampFixture fixture : championship.getFixtures()) {
                if (slot > 43) {
                    break;
                }
                if (wcKnockout && !plugin.championships().isWorldCupGroupFixture(championship, fixture)
                        && championship.getState() == ChampionshipState.REGISTRATION) {
                    continue;
                }
                String line = fixture.sideADisplay(championship) + " vs "
                        + fixture.sideBDisplay(championship);
                String score = fixture.getState() == FixtureState.COMPLETED
                        ? fixture.getScoreA() + " - " + fixture.getScoreB()
                        : fixture.getState().display();
                String roundLabel = fixture.getRound() == 0
                        ? msg.raw("champ-gui-fixture-scheduled-label")
                        : "&7Round: &f" + fixture.getRound();
                boolean canJoin = calendarReady
                        && fixture.getState() == FixtureState.PENDING
                        && fixture.involvesPlayer(championship, viewer.getUniqueId());
                ItemStack item = Items.named(canJoin ? Material.WRITABLE_BOOK : Material.BOOK,
                        (canJoin ? "&a&l" : "&e") + line,
                        roundLabel,
                        "&7Status: &f" + score);
                if (canJoin) {
                    item = Items.named(Material.WRITABLE_BOOK,
                            "&a&l" + line,
                            roundLabel,
                            "&7Status: &f" + score,
                            "",
                            msg.raw("champ-gui-fixture-join-lore"));
                } else if (fixture.getState() == FixtureState.PENDING
                        && fixture.involvesPlayer(championship, viewer.getUniqueId())
                        && wcKnockout
                        && plugin.championships().isWorldCupGroupFixture(championship, fixture)
                        && !calendarReady) {
                    item = Items.named(Material.ORANGE_STAINED_GLASS,
                            "&6" + line,
                            roundLabel,
                            "&7Status: &f" + score,
                            "",
                            msg.raw("champ-wc-need-scheduled-lore"));
                }
                inventory.setItem(slot, item);
                final ChampFixture selected = fixture;
                if (canJoin) {
                    registerClick(slot, () -> {
                        if (plugin.championships().joinFixtureMatch(viewer, championship, selected)) {
                            msg.send(viewer, "champ-joined-match", "arena", championship.getArenaName());
                            viewer.closeInventory();
                        } else {
                            msg.send(viewer, "champ-join-failed");
                        }
                    });
                }
                slot++;
            }
            backButton(49, () -> new DetailMenu(plugin, viewer, championshipId).open());
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

    private static final class ScheduleOpponentMenu extends AbstractMenu {

        private final String championshipId;

        ScheduleOpponentMenu(SoccerPlugin plugin, Player viewer, String championshipId) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-schedule-title"), 6);
            this.championshipId = championshipId;
        }

        @Override
        protected void render() {
            fillBorder(Material.ORANGE_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                backButton(49, () -> new DetailMenu(plugin, viewer, championshipId).open());
                return;
            }

            ChampTeam myTeam = championship.findTeamOf(viewer.getUniqueId());
            inventory.setItem(4, Items.named(Material.WRITABLE_BOOK,
                    msg.raw("champ-gui-schedule-custom"),
                    msg.raw("champ-gui-schedule-custom-lore")));
            registerClick(4, () -> ChampSchedulePrompt.startCustom(plugin, viewer, championshipId));

            if (myTeam != null && viewer.getUniqueId().equals(myTeam.getCaptain())) {
                inventory.setItem(6, Items.named(Material.NAME_TAG,
                        msg.raw("champ-gui-schedule-my-team"),
                        "&7" + myTeam.getName(),
                        msg.raw("champ-gui-schedule-my-team-lore")));
                registerClick(6, () -> ChampSchedulePrompt.startWithSideA(
                        plugin, viewer, championshipId, myTeam.getName(), myTeam.getId()));
            }

            inventory.setItem(22, Items.named(Material.COMPASS,
                    msg.raw("champ-gui-schedule-header"),
                    msg.raw("champ-gui-schedule-header-lore")));

            int slot = 10;
            boolean hasTeams = false;
            for (ChampTeam team : championship.getTeams().values()) {
                if (slot > 34) {
                    break;
                }
                if (myTeam != null && team.getId().equals(myTeam.getId())) {
                    continue;
                }
                hasTeams = true;
                inventory.setItem(slot, Items.named(Material.BLUE_BANNER,
                        "&b&l" + team.getName(),
                        "&7Members: &f" + team.size(),
                        "",
                        msg.raw("champ-gui-schedule-vs-team-lore")));
                final String teamId = team.getId();
                final String teamName = team.getName();
                registerClick(slot, () -> ChampSchedulePrompt.startWithSideA(
                        plugin, viewer, championshipId, teamName, teamId));
                slot++;
            }

            if (!hasTeams) {
                inventory.setItem(31, Items.named(Material.PAPER,
                        msg.raw("champ-gui-schedule-no-teams"),
                        msg.raw("champ-gui-schedule-no-teams-lore")));
            }

            backButton(49, () -> new DetailMenu(plugin, viewer, championshipId).open());
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

    private static String nullOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String[] scheduleLore(Messages msg, SoccerPlugin plugin, Championship championship) {
        if (championship.getType() == ChampionshipType.KNOCKOUT && championship.isRegistrationOpen()) {
            return new String[] {
                    msg.raw("champ-gui-schedule-match-lore"),
                    msg.format("champ-wc-scheduled-progress",
                            "count", String.valueOf(plugin.championships().countWorldCupGroupFixtures(championship)),
                            "required", String.valueOf(plugin.championships().worldCupRequiredGroupMatches()))
            };
        }
        return new String[] {msg.raw("champ-gui-schedule-match-lore")};
    }

    private static String typeLabel(Messages msg, ChampionshipType type) {
        return type == ChampionshipType.KNOCKOUT
                ? msg.raw("champ-type-world-cup")
                : type.display();
    }

    private static String nameOf(UUID uuid) {
        if (uuid == null) {
            return "?";
        }
        return Bukkit.getOfflinePlayer(uuid).getName() != null
                ? Bukkit.getOfflinePlayer(uuid).getName() : uuid.toString().substring(0, 8);
    }
}
