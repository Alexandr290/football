package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.championship.ChampionshipState;
import com.ballgame.soccer.championship.ChampionshipType;
import com.ballgame.soccer.championship.ChampFixture;
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
                        ? Material.GOLDEN_BOOTS : Material.NETHERITE_SWORD;
                ItemStack item = Items.named(icon,
                        "&e&l" + championship.getDisplayName(),
                        "&7Type: &f" + championship.getType().display(),
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
                    "&7" + championship.getType().display() + " &8| &f" + championship.getState().display(),
                    "&7Teams: &f" + championship.teamCount(),
                    "&7Arena: &f" + nullOr(championship.getArenaName(), "-")));

            if (championship.isRegistrationOpen()) {
                if (myTeam == null) {
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
                } else {
                    inventory.setItem(20, Items.named(Material.RED_WOOL,
                            msg.raw("champ-gui-leave-team"),
                            "&7" + myTeam.getName(),
                            msg.raw("champ-gui-leave-team-lore")));
                    registerClick(20, () -> {
                        plugin.championships().leaveTeam(championship, viewer);
                        msg.send(viewer, "champ-team-left");
                        open();
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
                        msg.raw("champ-gui-table-lore")));
                registerClick(24, () -> new TableMenu(plugin, viewer, championshipId).open());
            } else {
                inventory.setItem(24, Items.named(Material.MAP,
                        msg.raw("champ-gui-bracket"),
                        msg.raw("champ-gui-bracket-lore")));
                registerClick(24, () -> new BracketMenu(plugin, viewer, championshipId).open());
            }

            inventory.setItem(31, Items.named(Material.PAPER,
                    msg.raw("champ-gui-fixtures"),
                    "&7Pending for you: &f" + pending.size()));
            registerClick(31, () -> new FixturesMenu(plugin, viewer, championshipId).open());

            if (championship.getState() == ChampionshipState.ACTIVE && !pending.isEmpty()) {
                inventory.setItem(40, Items.named(Material.EMERALD,
                        msg.raw("champ-gui-join-match"),
                        msg.raw("champ-gui-join-match-lore")));
                registerClick(40, () -> {
                    ChampFixture fixture = pending.get(0);
                    if (plugin.championships().joinFixtureMatch(viewer, championship, fixture)) {
                        msg.send(viewer, "champ-joined-match", "arena", championship.getArenaName());
                        viewer.closeInventory();
                    } else {
                        msg.send(viewer, "champ-join-failed");
                    }
                });
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

        TableMenu(SoccerPlugin plugin, Player viewer, String championshipId) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-table-title"), 6);
            this.championshipId = championshipId;
        }

        @Override
        protected void render() {
            fillBorder(Material.LIME_STAINED_GLASS_PANE);
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                return;
            }
            List<ChampStanding> rows = new ArrayList<>(championship.getStandings().values());
            rows.sort(Comparator.comparingInt(ChampStanding::getPoints).reversed()
                    .thenComparingInt(ChampStanding::getGoalDifference).reversed());

            inventory.setItem(4, Items.named(Material.GOLDEN_BOOTS, "&a&lLeague Table",
                    "&7Pos  Team  P  W  D  L  GD  Pts"));

            int slot = 10;
            int pos = 1;
            for (ChampStanding row : rows) {
                if (slot > 43) {
                    break;
                }
                ChampTeam team = championship.getTeam(row.getTeamId());
                String teamName = team != null ? team.getName() : row.getTeamId();
                String posColor = pos <= 3 ? "&e" : "&f";
                inventory.setItem(slot, Items.named(Material.PAPER,
                        posColor + "#" + pos + " &f" + teamName,
                        "&7P:&f" + row.getPlayed()
                                + " &7W:&a" + row.getWon()
                                + " &7D:&e" + row.getDrawn()
                                + " &7L:&c" + row.getLost(),
                        "&7GF:&f" + row.getGoalsFor()
                                + " &7GA:&f" + row.getGoalsAgainst()
                                + " &7GD:&f" + row.getGoalDifference(),
                        "&6Points: &f" + row.getPoints()));
                slot++;
                pos++;
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

    private static final class BracketMenu extends AbstractMenu {

        private final String championshipId;

        BracketMenu(SoccerPlugin plugin, Player viewer, String championshipId) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-bracket-title"), 6);
            this.championshipId = championshipId;
        }

        @Override
        protected void render() {
            fillBorder(Material.RED_STAINED_GLASS_PANE);
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                return;
            }

            int maxRound = championship.getFixtures().stream()
                    .mapToInt(ChampFixture::getRound).max().orElse(1);
            int slot = 10;
            for (ChampFixture fixture : championship.getFixtures()) {
                if (slot > 43) {
                    break;
                }
                ChampTeam a = championship.getTeam(fixture.getTeamAId());
                ChampTeam b = championship.getTeam(fixture.getTeamBId());
                String nameA = a != null ? a.getName() : "TBD";
                String nameB = b != null ? b.getName() : "TBD";
                String status = fixture.getState() == FixtureState.COMPLETED
                        ? "&a" + fixture.getScoreA() + " - " + fixture.getScoreB()
                        : "&7" + fixture.getState().name();
                Material mat = fixture.getState() == FixtureState.COMPLETED
                        ? Material.LIME_DYE : Material.GRAY_DYE;
                inventory.setItem(slot, Items.named(mat,
                        "&cR" + fixture.getRound() + " &f" + nameA + " &7vs &f" + nameB,
                        status,
                        fixture.getRound() == maxRound ? "&6Final stage" : "&7Round " + fixture.getRound()));
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
                    if (championship.isRegistrationOpen()
                            && !team.contains(viewer.getUniqueId())
                            && championship.findTeamOf(viewer.getUniqueId()) == null) {
                        lore.add(Text.color(""));
                        lore.add(Text.color("&aClick to join this team"));
                    }
                    meta.setLore(lore);
                    head.setItemMeta(meta);
                }
                inventory.setItem(slot, head);
                final String teamId = team.getId();
                if (championship.isRegistrationOpen()) {
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
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                return;
            }

            int slot = 10;
            for (ChampFixture fixture : championship.getFixtures()) {
                if (slot > 43) {
                    break;
                }
                ChampTeam a = championship.getTeam(fixture.getTeamAId());
                ChampTeam b = championship.getTeam(fixture.getTeamBId());
                String line = (a != null ? a.getName() : "TBD") + " vs "
                        + (b != null ? b.getName() : "TBD");
                String score = fixture.getState() == FixtureState.COMPLETED
                        ? fixture.getScoreA() + " - " + fixture.getScoreB()
                        : fixture.getState().display();
                inventory.setItem(slot, Items.named(Material.WRITABLE_BOOK,
                        "&e" + line,
                        "&7Round: &f" + fixture.getRound(),
                        "&7Status: &f" + score));
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

    private static String nullOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String nameOf(UUID uuid) {
        if (uuid == null) {
            return "?";
        }
        return Bukkit.getOfflinePlayer(uuid).getName() != null
                ? Bukkit.getOfflinePlayer(uuid).getName() : uuid.toString().substring(0, 8);
    }
}
