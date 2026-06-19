package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.championship.ChampFixture;
import com.ballgame.soccer.championship.ChampStanding;
import com.ballgame.soccer.championship.ChampTeam;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.championship.ChampionshipState;
import com.ballgame.soccer.championship.ChampionshipType;
import com.ballgame.soccer.championship.FixtureState;
import com.ballgame.soccer.championship.PlayoffStage;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * World Cup UI: group stage (8×2) → quarter-finals → semi-finals → final.
 */
public final class TournamentBracketView {

    public enum BracketPage {
        GROUPS("champ-bracket-page-groups"),
        KNOCKOUT("champ-bracket-page-knockout");

        private final String titleKey;

        BracketPage(String titleKey) {
            this.titleKey = titleKey;
        }

        public String titleKey() {
            return titleKey;
        }
    }

    private static final int[] TAB_SLOTS = {45, 46};

    private static final int[] QF_SLOTS = {10, 19, 28, 37};
    private static final int[] SF_SLOTS = {14, 32};
    private static final int FINAL_SLOT = 25;
    private static final int TROPHY_SLOT = 43;

    private static final int[] QF_CONNECTORS = {12, 21, 30, 39};
    private static final int[] SF_CONNECTORS = {15, 16, 17, 24, 33, 34};
    private static final int[] MERGE_CONNECTORS = {13, 22, 31, 40};

    private static final int[] GROUP_SLOTS = {10, 12, 14, 16, 28, 30, 32, 34};
    private static final int[] GROUP_MATCH_SLOTS = {19, 21, 23, 25, 37, 39, 41, 43};

    private TournamentBracketView() {
    }

    public static boolean hasBracket(Championship championship) {
        if (championship.getType() == ChampionshipType.KNOCKOUT) {
            return championship.getState() != ChampionshipState.REGISTRATION
                    && championship.getFixtures().stream()
                    .anyMatch(f -> f.getPlayoffStage() != null);
        }
        return championship.hasPlayoffFixtures() || championship.isPlayoffsActive();
    }

    public static BracketPage defaultPage(Championship championship) {
        if (championship.getType() != ChampionshipType.KNOCKOUT) {
            return BracketPage.KNOCKOUT;
        }
        if (championship.isPlayoffsActive()) {
            return BracketPage.KNOCKOUT;
        }
        return BracketPage.GROUPS;
    }

    public static void render(SoccerPlugin plugin, Messages msg, Championship championship,
                              Player viewer, Inventory inventory, BracketPage page,
                              BiConsumer<Integer, Runnable> clickRegistrar,
                              Consumer<BracketPage> pageChanger,
                              Consumer<String> groupResultsOpener) {
        fillBackground(inventory);

        if (championship.getType() == ChampionshipType.LEAGUE && !hasBracket(championship)) {
            inventory.setItem(22, Items.named(Material.BARRIER,
                    msg.raw("champ-bracket-not-started"),
                    msg.raw("champ-bracket-not-started-lore")));
            renderPageTabs(msg, inventory, page, clickRegistrar, pageChanger);
            return;
        }

        if (championship.getType() == ChampionshipType.KNOCKOUT
                && championship.getState() == ChampionshipState.REGISTRATION) {
            inventory.setItem(22, Items.named(Material.BARRIER,
                    msg.raw("champ-bracket-not-started"),
                    msg.raw("champ-bracket-not-started-lore")));
            renderPageTabs(msg, inventory, page, clickRegistrar, pageChanger);
            return;
        }

        if (page == BracketPage.GROUPS) {
            renderGroupsPage(plugin, msg, championship, viewer, inventory, clickRegistrar,
                    groupResultsOpener);
        } else {
            renderKnockoutPage(plugin, msg, championship, viewer, inventory, clickRegistrar);
        }

        renderPageTabs(msg, inventory, page, clickRegistrar, pageChanger);
    }

    /** Legacy single-page render for league playoff tab. */
    public static void render(SoccerPlugin plugin, Messages msg, Championship championship,
                              Player viewer, Inventory inventory,
                              BiConsumer<Integer, Runnable> clickRegistrar) {
        render(plugin, msg, championship, viewer, inventory,
                BracketPage.KNOCKOUT, clickRegistrar, p -> {
                }, null);
    }

    /** Legacy render without page tabs. */
    public static void render(SoccerPlugin plugin, Messages msg, Championship championship,
                              Player viewer, Inventory inventory, BracketPage page,
                              BiConsumer<Integer, Runnable> clickRegistrar,
                              Consumer<BracketPage> pageChanger) {
        render(plugin, msg, championship, viewer, inventory, page, clickRegistrar, pageChanger, null);
    }

    private static void renderGroupsPage(SoccerPlugin plugin, Messages msg, Championship championship,
                                         Player viewer, Inventory inventory,
                                         BiConsumer<Integer, Runnable> clickRegistrar,
                                         Consumer<String> groupResultsOpener) {
        plugin.championships().syncWorldCupGroupsFromFixtures(championship);

        int required = plugin.championships().worldCupRequiredGroupMatches();
        int scheduled = plugin.championships().countWorldCupGroupFixtures(championship);
        boolean calendarReady = plugin.championships().isWorldCupGroupCalendarComplete(championship);

        inventory.setItem(4, Items.named(Material.LIME_BANNER,
                msg.raw("champ-bracket-group-title"),
                msg.raw("champ-bracket-group-title-lore"),
                msg.format("champ-wc-scheduled-progress", "count", String.valueOf(scheduled),
                        "required", String.valueOf(required))));

        if (!calendarReady && championship.getState() == ChampionshipState.REGISTRATION) {
            inventory.setItem(22, Items.named(Material.ORANGE_STAINED_GLASS,
                    msg.raw("champ-wc-need-scheduled-title"),
                    msg.format("champ-wc-scheduled-progress", "count", String.valueOf(scheduled),
                            "required", String.valueOf(required)),
                    msg.raw("champ-wc-need-scheduled-lore")));
        }

        int advance = plugin.settings().champAdvancePerGroup;
        int groupCount = plugin.settings().champGroupCount;
        for (int i = 0; i < groupCount && i < GROUP_SLOTS.length; i++) {
            String groupId = String.valueOf((char) ('A' + i));
            inventory.setItem(GROUP_SLOTS[i], buildGroupPanel(msg, championship, groupId, advance));
            if (groupResultsOpener != null) {
                final String openGroupId = groupId;
                clickRegistrar.accept(GROUP_SLOTS[i], () -> groupResultsOpener.accept(openGroupId));
            }

            ChampFixture groupMatch = plugin.championships().findGroupFixture(championship, groupId);
            int matchSlot = GROUP_MATCH_SLOTS[i];
            if (groupMatch == null) {
                inventory.setItem(matchSlot, Items.named(Material.GRAY_STAINED_GLASS_PANE,
                        msg.raw("champ-bracket-group-match-empty"),
                        msg.raw("champ-bracket-group-match-empty-lore")));
            } else {
                placeGroupMatchBlock(plugin, msg, championship, viewer, inventory,
                        groupMatch, matchSlot, clickRegistrar, calendarReady);
            }
        }

        if (championship.isGroupPhaseComplete() && !championship.isPlayoffsActive()) {
            inventory.setItem(22, Items.named(Material.CLOCK,
                    msg.raw("champ-bracket-groups-done"),
                    msg.raw("champ-bracket-groups-done-lore")));
        }
    }

    private static void placeGroupMatchBlock(SoccerPlugin plugin, Messages msg, Championship championship,
                                             Player viewer, Inventory inventory, ChampFixture fixture,
                                             int slot, BiConsumer<Integer, Runnable> clickRegistrar,
                                             boolean calendarReady) {
        boolean fixtureReady = plugin.championships().canJoinFixture(championship, fixture);
        boolean involves = fixture.involvesPlayer(championship, viewer.getUniqueId());
        boolean clickable = fixtureReady && involves
                && (fixture.getState() == FixtureState.PENDING
                || fixture.getState() == FixtureState.LIVE);

        Material mat = Material.GRAY_CONCRETE;
        if (fixture.getState() == FixtureState.LIVE) {
            mat = Material.ORANGE_CONCRETE;
        } else if (fixture.getState() == FixtureState.COMPLETED) {
            mat = Material.GREEN_CONCRETE;
        } else if (!fixtureReady) {
            mat = Material.GRAY_STAINED_GLASS;
        }

        String teamA = stripColor(formatSide(championship, fixture, true));
        String teamB = stripColor(formatSide(championship, fixture, false));
        List<String> lore = new ArrayList<>();
        lore.add("&7  " + teamA);
        lore.add("&8  ×");
        lore.add("&7  " + teamB);
        lore.add("");
        if (fixture.getState() == FixtureState.COMPLETED || fixture.getState() == FixtureState.LIVE) {
            lore.add("&e&l  " + fixture.getScoreA() + " : " + fixture.getScoreB());
        } else if (!calendarReady) {
            lore.add(msg.raw("champ-wc-need-scheduled-lore"));
        } else if (!plugin.championships().areTeamsReadyForMatch(championship, fixture)) {
            lore.add(msg.raw("champ-wc-teams-not-ready-lore"));
        } else if (clickable) {
            lore.add(msg.raw("champ-gui-fixture-join-lore"));
        } else {
            lore.add("&7" + msg.raw("champ-bracket-status") + " &f" + fixture.getState().display());
        }

        inventory.setItem(slot, Items.named(mat,
                msg.raw("champ-bracket-group-match") + " &8| &f" + teamA + " &7× &f" + teamB,
                lore.toArray(new String[0])));

        if (clickable) {
            final ChampFixture selected = fixture;
            clickRegistrar.accept(slot, () -> {
                if (plugin.championships().joinFixtureMatch(viewer, championship, selected)) {
                    msg.send(viewer, "champ-joined-match", "arena", championship.getArenaName());
                    viewer.closeInventory();
                } else {
                    msg.send(viewer, "champ-join-failed");
                }
            });
        }
    }

    private static ItemStack buildGroupPanel(Messages msg, Championship championship,
                                             String groupId, int advance) {
        List<ChampStanding> rows = groupStandings(championship, groupId);
        List<String> lore = new ArrayList<>();
        lore.add(msg.raw("champ-bracket-group-panel-lore"));
        lore.add(msg.raw("champ-bracket-group-view-lore"));
        lore.add("");
        for (int i = 0; i < rows.size(); i++) {
            ChampStanding row = rows.get(i);
            ChampTeam team = championship.getTeam(row.getTeamId());
            String name = team != null ? team.getName() : "?";
            String prefix = i < advance ? "&a" : "&7";
            lore.add(prefix + (i + 1) + ". &f" + name
                    + " &8· &e" + row.getPoints() + " &7("
                    + row.getWon() + "-" + row.getDrawn() + "-" + row.getLost()
                    + ", " + row.getGoalsFor() + ":" + row.getGoalsAgainst() + ")");
        }
        if (rows.isEmpty()) {
            List<ChampTeam> groupTeams = championship.getTeamsInGroup(groupId);
            groupTeams.sort(Comparator.comparing(ChampTeam::getName));
            for (ChampTeam team : groupTeams) {
                lore.add("&7• &f" + team.getName()
                        + (team.size() > 0 ? " &8(" + team.size() + ")" : " &8(0)"));
            }
            if (groupTeams.isEmpty()) {
                lore.add("&8—");
            }
        }
        return Items.named(Material.WHITE_BANNER,
                msg.raw("champ-bracket-group") + " &e&l" + groupId,
                lore.toArray(new String[0]));
    }

    private static List<ChampStanding> groupStandings(Championship championship, String groupId) {
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

    private static void renderKnockoutPage(SoccerPlugin plugin, Messages msg,
                                           Championship championship, Player viewer,
                                           Inventory inventory,
                                           BiConsumer<Integer, Runnable> clickRegistrar) {
        inventory.setItem(4, Items.named(Material.NETHER_STAR,
                msg.raw("champ-bracket-title-main"),
                msg.raw("champ-bracket-title-lore")));

        inventory.setItem(1, Items.named(Material.BLACK_BANNER,
                msg.raw("champ-bracket-qf"), msg.raw("champ-bracket-qf-lore")));
        inventory.setItem(5, Items.named(Material.RED_BANNER,
                msg.raw("champ-bracket-sf"), msg.raw("champ-bracket-sf-lore")));
        inventory.setItem(7, Items.named(Material.LIGHT_BLUE_BANNER,
                msg.raw("champ-bracket-final-exclaim"), msg.raw("champ-bracket-final-lore")));

        for (int slot : QF_CONNECTORS) {
            inventory.setItem(slot, Items.named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        }
        for (int slot : SF_CONNECTORS) {
            inventory.setItem(slot, Items.named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        }
        for (int slot : MERGE_CONNECTORS) {
            inventory.setItem(slot, Items.named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        }

        Map<PlayoffStage, List<ChampFixture>> grouped = groupKnockoutFixtures(championship);
        List<ChampFixture> quarterFinals = grouped.getOrDefault(PlayoffStage.QUARTER_FINAL, List.of());
        List<ChampFixture> semiFinals = grouped.getOrDefault(PlayoffStage.SEMI_FINAL, List.of());
        List<ChampFixture> finals = grouped.getOrDefault(PlayoffStage.FINAL, List.of());

        if (quarterFinals.isEmpty() && semiFinals.isEmpty() && finals.isEmpty()
                && !championship.isPlayoffsActive()) {
            inventory.setItem(22, Items.named(Material.BARRIER,
                    msg.raw("champ-bracket-stage-locked"),
                    msg.raw("champ-bracket-stage-locked-lore")));
        }

        placeFixtures(plugin, msg, championship, viewer, inventory,
                quarterFinals, QF_SLOTS, PlayoffStage.QUARTER_FINAL, clickRegistrar);
        placeFixtures(plugin, msg, championship, viewer, inventory,
                semiFinals, SF_SLOTS, PlayoffStage.SEMI_FINAL, clickRegistrar);
        if (!finals.isEmpty()) {
            placeFixture(plugin, msg, championship, viewer, inventory,
                    finals.get(0), FINAL_SLOT, clickRegistrar);
        } else {
            inventory.setItem(FINAL_SLOT, Items.named(Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                    msg.raw("champ-bracket-final-exclaim"),
                    msg.raw("champ-bracket-tbd-lore")));
        }

        ChampTeam champion = resolveChampion(championship, finals);
        if (champion != null) {
            inventory.setItem(TROPHY_SLOT, Items.named(Material.GOLDEN_HELMET,
                    "&6&l" + champion.getName(),
                    msg.raw("champ-bracket-champion-lore")));
        } else {
            inventory.setItem(TROPHY_SLOT, Items.named(Material.GOLDEN_APPLE,
                    msg.raw("champ-bracket-trophy"),
                    msg.raw("champ-bracket-trophy-lore")));
        }
    }

    private static void renderPageTabs(Messages msg, Inventory inventory, BracketPage active,
                                       BiConsumer<Integer, Runnable> clickRegistrar,
                                       Consumer<BracketPage> pageChanger) {
        BracketPage[] pages = BracketPage.values();
        for (int i = 0; i < pages.length && i < TAB_SLOTS.length; i++) {
            BracketPage page = pages[i];
            boolean selected = page == active;
            Material mat = selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            inventory.setItem(TAB_SLOTS[i], Items.named(mat,
                    (selected ? "&a&l▶ " : "&7") + msg.raw(page.titleKey()),
                    msg.raw("champ-bracket-page-switch-lore")));
            if (pageChanger != null && !selected) {
                BracketPage target = page;
                clickRegistrar.accept(TAB_SLOTS[i], () -> pageChanger.accept(target));
            }
        }
    }

    private static void fillBackground(Inventory inventory) {
        ItemStack dark = Items.named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, dark);
        }
    }

    private static Map<PlayoffStage, List<ChampFixture>> groupKnockoutFixtures(Championship championship) {
        Map<PlayoffStage, List<ChampFixture>> grouped = new EnumMap<>(PlayoffStage.class);
        for (ChampFixture fixture : championship.getFixtures()) {
            if (fixture.getRound() == 0 || fixture.getPlayoffStage() == null) {
                continue;
            }
            if (fixture.getPlayoffStage() == PlayoffStage.GROUP) {
                continue;
            }
            grouped.computeIfAbsent(fixture.getPlayoffStage(), k -> new ArrayList<>()).add(fixture);
        }
        for (List<ChampFixture> list : grouped.values()) {
            list.sort(Comparator.comparingInt(ChampFixture::getRound).thenComparing(ChampFixture::getId));
        }
        return grouped;
    }

    public static PlayoffStage resolveStage(Championship championship, ChampFixture fixture) {
        if (fixture.getRound() == 0) {
            return null;
        }
        if (fixture.getPlayoffStage() != null) {
            return fixture.getPlayoffStage();
        }
        if (championship.getType() != ChampionshipType.KNOCKOUT) {
            return null;
        }
        int maxRound = championship.getFixtures().stream()
                .filter(f -> f.getRound() > 0 && f.getPlayoffStage() != null
                        && f.getPlayoffStage().isKnockout())
                .mapToInt(ChampFixture::getRound)
                .max()
                .orElse(1);
        return PlayoffStage.fromKnockoutRound(maxRound, fixture.getRound());
    }

    private static void placeFixtures(SoccerPlugin plugin, Messages msg, Championship championship,
                                      Player viewer, Inventory inventory, List<ChampFixture> fixtures,
                                      int[] slots, PlayoffStage stage,
                                      BiConsumer<Integer, Runnable> clickRegistrar) {
        for (int i = 0; i < slots.length; i++) {
            if (i < fixtures.size()) {
                placeFixture(plugin, msg, championship, viewer, inventory,
                        fixtures.get(i), slots[i], clickRegistrar);
            } else {
                inventory.setItem(slots[i], emptyMatchSlot(msg, stage));
            }
        }
    }

    private static ItemStack emptyMatchSlot(Messages msg, PlayoffStage stage) {
        Material mat = materialForStage(stage, FixtureState.PENDING);
        return Items.named(mat,
                msg.raw("champ-bracket-empty-match"),
                msg.raw("champ-bracket-tbd-lore"));
    }

    private static void placeFixture(SoccerPlugin plugin, Messages msg, Championship championship,
                                     Player viewer, Inventory inventory, ChampFixture fixture, int slot,
                                     BiConsumer<Integer, Runnable> clickRegistrar) {
        boolean teamsReady = plugin.championships().canJoinFixture(championship, fixture);
        boolean involves = fixture.involvesPlayer(championship, viewer.getUniqueId());
        boolean clickable = teamsReady && involves
                && (fixture.getState() == FixtureState.PENDING
                || fixture.getState() == FixtureState.LIVE);

        inventory.setItem(slot, buildMatchCard(plugin, msg, championship, fixture, viewer, teamsReady));

        if (clickable) {
            final ChampFixture selected = fixture;
            clickRegistrar.accept(slot, () -> {
                if (plugin.championships().joinFixtureMatch(viewer, championship, selected)) {
                    msg.send(viewer, "champ-joined-match", "arena", championship.getArenaName());
                    viewer.closeInventory();
                } else {
                    msg.send(viewer, "champ-join-failed");
                }
            });
        }
    }

    private static ItemStack buildMatchCard(SoccerPlugin plugin, Messages msg, Championship championship,
                                            ChampFixture fixture, Player viewer, boolean teamsReady) {
        PlayoffStage stage = resolveStage(championship, fixture);
        String teamA = stripColor(formatSide(championship, fixture, true));
        String teamB = stripColor(formatSide(championship, fixture, false));
        Material material = materialForStage(stage, fixture.getState());
        if (!teamsReady && fixture.getState() == FixtureState.PENDING) {
            material = Material.GRAY_STAINED_GLASS;
        }

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(teamLine(championship, fixture, true));
        lore.add("&8─────────────");
        lore.add(teamLine(championship, fixture, false));
        lore.add("");

        if (fixture.getState() == FixtureState.COMPLETED || fixture.getState() == FixtureState.LIVE) {
            lore.add("&e&l  " + fixture.getScoreA() + "  :  " + fixture.getScoreB());
        } else {
            lore.add("&8  —  :  —");
        }

        lore.add("");
        lore.add("&7" + msg.raw("champ-bracket-status") + " &f" + fixture.getState().display());

        if (fixture.getState() == FixtureState.PENDING
                && fixture.involvesPlayer(championship, viewer.getUniqueId())) {
            lore.add("");
            if (teamsReady) {
                lore.add(msg.raw("champ-gui-fixture-join-lore"));
            } else {
                lore.add(msg.raw("champ-wc-teams-not-ready-lore"));
            }
        } else if (fixture.getState() == FixtureState.PENDING && !teamsReady && fixture.hasBothSides()) {
            lore.add("");
            lore.add(msg.raw("champ-wc-teams-not-ready-lore"));
        }

        String title = titleForStage(msg, stage) + " &8| &f" + teamA + " &7× &f" + teamB;
        if (fixture.getState() == FixtureState.COMPLETED && fixture.getWinnerTeamId() != null) {
            title += " &6✓";
        } else if (fixture.getState() == FixtureState.LIVE) {
            title = "&c&lLIVE &r" + title;
        }

        return Items.named(material, title, lore.toArray(new String[0]));
    }

    private static Material materialForStage(PlayoffStage stage, FixtureState state) {
        if (stage == null) {
            return Material.GRAY_CONCRETE;
        }
        if (state == FixtureState.LIVE) {
            return Material.ORANGE_CONCRETE;
        }
        if (state == FixtureState.COMPLETED) {
            return switch (stage) {
                case GROUP -> Material.GREEN_TERRACOTTA;
                case QUARTER_FINAL -> Material.GRAY_CONCRETE;
                case SEMI_FINAL -> Material.PINK_CONCRETE;
                case FINAL -> Material.CYAN_CONCRETE;
                default -> Material.GRAY_CONCRETE;
            };
        }
        return switch (stage) {
            case GROUP -> Material.LIME_CONCRETE;
            case QUARTER_FINAL -> Material.BLACK_CONCRETE;
            case SEMI_FINAL -> Material.RED_CONCRETE;
            case FINAL -> Material.LIGHT_BLUE_CONCRETE;
            default -> Material.GRAY_CONCRETE;
        };
    }

    private static String titleForStage(Messages msg, PlayoffStage stage) {
        if (stage == null) {
            return "&7?";
        }
        return msg.raw(stage.displayKey());
    }

    private static String formatSide(Championship championship, ChampFixture fixture, boolean sideA) {
        String name = sideA ? fixture.sideADisplay(championship) : fixture.sideBDisplay(championship);
        if ("TBD".equals(name)) {
            return "&8???";
        }
        String teamId = sideA ? fixture.getTeamAId() : fixture.getTeamBId();
        if (teamId != null) {
            ChampTeam team = championship.getTeam(teamId);
            if (team != null && team.isEliminated() && fixture.getState() != FixtureState.COMPLETED) {
                return "&8" + name;
            }
        }
        return "&f&l" + name;
    }

    private static String teamLine(Championship championship, ChampFixture fixture, boolean sideA) {
        String team = formatSide(championship, fixture, sideA);
        String teamId = sideA ? fixture.getTeamAId() : fixture.getTeamBId();
        boolean winner = teamId != null && teamId.equals(fixture.getWinnerTeamId());
        if (winner) {
            return "&a&l▶ " + team;
        }
        if ("&8???".equals(team)) {
            return "&8  ???";
        }
        return "&7  " + team;
    }

    private static String stripColor(String text) {
        return text.replaceAll("§[0-9a-fk-or]", "").replaceAll("&[0-9a-fk-or]", "");
    }

    private static ChampTeam resolveChampion(Championship championship, List<ChampFixture> finals) {
        if (championship.getChampionTeamId() != null) {
            return championship.getTeam(championship.getChampionTeamId());
        }
        for (ChampFixture finalMatch : finals) {
            if (finalMatch.getState() == FixtureState.COMPLETED && finalMatch.getWinnerTeamId() != null) {
                return championship.getTeam(finalMatch.getWinnerTeamId());
            }
        }
        return null;
    }
}
