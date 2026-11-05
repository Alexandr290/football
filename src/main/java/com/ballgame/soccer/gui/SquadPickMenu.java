package com.ballgame.soccer.gui;



import com.ballgame.soccer.SoccerPlugin;

import com.ballgame.soccer.championship.ChampFixture;

import com.ballgame.soccer.championship.ChampTeam;

import com.ballgame.soccer.championship.Championship;

import com.ballgame.soccer.config.Messages;

import com.ballgame.soccer.fifa.FifaNation;

import com.ballgame.soccer.fifa.FifaPlayer;

import com.ballgame.soccer.game.Game;

import com.ballgame.soccer.game.TeamSide;

import com.ballgame.soccer.util.Items;

import org.bukkit.Material;

import org.bukkit.entity.Player;

import org.bukkit.event.inventory.ClickType;



import java.util.List;

import java.util.Locale;

import java.util.UUID;



/** Pick a side and starting-XI slot for a FIFA national team before/during lobby. */

public final class SquadPickMenu extends AbstractMenu {



    private final String championshipId;

    private final String fixtureId;

    private TeamSide selectedSide;



    public SquadPickMenu(SoccerPlugin plugin, Player viewer, String championshipId, String fixtureId) {

        this(plugin, viewer, championshipId, fixtureId, null);

    }



    public SquadPickMenu(SoccerPlugin plugin, Player viewer, String championshipId, String fixtureId,

                         TeamSide selectedSide) {

        super(plugin, viewer, plugin.messages().raw("champ-gui-squad-title"), 4);

        this.championshipId = championshipId;

        this.fixtureId = fixtureId;

        this.selectedSide = selectedSide;

    }



    @Override

    protected void render() {

        fillBorder(Material.CYAN_STAINED_GLASS_PANE);

        Messages msg = plugin.messages();

        Championship championship = plugin.championships().get(championshipId);

        ChampFixture fixture = championship != null ? championship.getFixture(fixtureId) : null;

        if (championship == null || fixture == null) {

            inventory.setItem(13, Items.named(Material.BARRIER, "&cNot found"));

            return;

        }



        ChampTeam teamA = championship.getTeam(fixture.getTeamAId());

        ChampTeam teamB = championship.getTeam(fixture.getTeamBId());

        TeamSide resolvedSide = resolveSide(championship, fixture, teamA, teamB);

        if (selectedSide == null) {

            selectedSide = resolvedSide;

        }



        if (selectedSide == null) {

            renderSidePick(msg, championship, fixture, teamA, teamB);

            return;

        }



        renderSquad(msg, championship, fixture, selectedSide, teamA, teamB);

    }



    private TeamSide resolveSide(Championship championship, ChampFixture fixture,

                                 ChampTeam teamA, ChampTeam teamB) {

        ChampTeam myTeam = championship.findTeamOf(viewer.getUniqueId());

        if (myTeam == null) {

            return null;

        }

        if (teamA != null && myTeam.getId().equals(teamA.getId())) {

            return TeamSide.RED;

        }

        if (teamB != null && myTeam.getId().equals(teamB.getId())) {

            return TeamSide.BLUE;

        }

        return null;

    }



    private void renderSidePick(Messages msg, Championship championship, ChampFixture fixture,

                                ChampTeam teamA, ChampTeam teamB) {

        inventory.setItem(4, Items.named(Material.COMPASS,

                msg.raw("champ-gui-squad-pick-side"),

                msg.raw("champ-gui-squad-pick-side-lore")));



        if (teamA != null && teamA.isNationalTeam()) {
            String chatColor = nationChatColor(teamA, com.ballgame.soccer.fifa.NationKitColors.TeamSlot.RED);
            inventory.setItem(11, Items.named(Material.RED_BANNER,
                    chatColor + "&l" + teamA.getName(),
                    msg.raw("champ-gui-squad-pick-red")));

            registerClick(11, () -> {

                selectedSide = TeamSide.RED;

                open();

            });

        }

        if (teamB != null && teamB.isNationalTeam()) {
            String chatColor = nationChatColor(teamB, com.ballgame.soccer.fifa.NationKitColors.TeamSlot.BLUE);
            inventory.setItem(15, Items.named(Material.BLUE_BANNER,
                    chatColor + "&l" + teamB.getName(),
                    msg.raw("champ-gui-squad-pick-blue")));

            registerClick(15, () -> {

                selectedSide = TeamSide.BLUE;

                open();

            });

        }



        backButton(31, () -> ChampMenus.openFixturesMenu(plugin, viewer, championshipId));

    }



    private void renderSquad(Messages msg, Championship championship, ChampFixture fixture,

                             TeamSide side, ChampTeam teamA, ChampTeam teamB) {

        ChampTeam pickedTeam = side == TeamSide.RED ? teamA : teamB;

        if (pickedTeam == null || !pickedTeam.isNationalTeam()) {

            inventory.setItem(13, Items.named(Material.BARRIER, msg.raw("champ-gui-squad-need-nation")));

            backButton(31, () -> {

                selectedSide = null;

                open();

            });

            return;

        }



        FifaNation nation = plugin.fifaCatalog().get(pickedTeam.getNationCode());

        if (nation == null) {

            inventory.setItem(13, Items.named(Material.BARRIER, "&cSquad missing"));

            return;

        }



        Game game = plugin.games().findGameByFixture(championshipId, fixtureId);

        List<FifaPlayer> xi = nation.startingXi();



        inventory.setItem(4, Items.named(Material.NAME_TAG,

                "&6&l" + nation.localizedName(isRu()),

                msg.raw("champ-gui-squad-hint")));



        if (resolveSide(championship, fixture, teamA, teamB) == null) {

            inventory.setItem(8, Items.named(Material.ARROW,

                    msg.raw("champ-gui-squad-change-side"),

                    msg.raw("champ-gui-squad-change-side-lore")));

            registerClick(8, () -> {

                selectedSide = null;

                open();

            });

        }



        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22};

        for (int i = 0; i < Math.min(11, xi.size()); i++) {

            FifaPlayer player = xi.get(i);

            UUID claim = game != null ? game.getSquadClaim(side, i) : null;

            boolean mine = claim != null && claim.equals(viewer.getUniqueId());

            boolean taken = claim != null && !mine;

            Material icon = mine ? Material.LIME_WOOL : (taken ? Material.RED_WOOL : Material.WHITE_WOOL);

            String status = mine ? msg.raw("champ-gui-squad-yours")

                    : (taken ? msg.raw("champ-gui-squad-taken") : msg.raw("champ-gui-squad-free"));

            inventory.setItem(slots[i], Items.named(icon,

                    "&e" + player.displayLabel(),

                    "&7" + player.getPosition().name(),

                    status));

            if (!taken) {

                final int slotIndex = i;

                final TeamSide claimSide = side;

                registerClick(slots[i], () -> {

                    ensureGameAndClaim(championship, fixture, claimSide, slotIndex, nation);

                });

            }

        }



        inventory.setItem(25, Items.named(Material.IRON_GOLEM_SPAWN_EGG,

                msg.raw("champ-gui-squad-fill-bots"),

                msg.raw("champ-gui-squad-fill-bots-lore")));

        registerClick(25, () -> {

            int added = plugin.games().addChampionshipBots(championship, fixture, 0, 0,

                    com.ballgame.soccer.game.BotDifficulty.MEDIUM);

            if (added > 0) {

                msg.send(viewer, "champ-bots-added", "count", String.valueOf(added));

            } else {

                msg.send(viewer, "champ-bots-failed");

            }

            open();

        });



        inventory.setItem(28, Items.named(Material.COMPASS,

                msg.raw("champ-gui-squad-join-match"),

                msg.raw("champ-gui-squad-join-match-lore")));

        registerClick(28, () -> {

            if (plugin.championships().joinFixtureMatch(viewer, championship, fixture, side)) {

                msg.send(viewer, "champ-joined-match");

                viewer.closeInventory();

            }

        });



        backButton(31, () -> ChampMenus.openFixturesMenu(plugin, viewer, championshipId));

    }



    private void ensureGameAndClaim(Championship championship, ChampFixture fixture,

                                    TeamSide side, int slotIndex, FifaNation nation) {

        Messages msg = plugin.messages();

        if (!plugin.championships().joinFixtureMatch(viewer, championship, fixture, side)) {

            return;

        }

        Game game = plugin.games().findGameByFixture(championshipId, fixtureId);

        if (game == null) {

            return;

        }

        if (game.claimSquadSlot(side, slotIndex, viewer.getUniqueId())) {

            FifaPlayer fp = nation.starterAt(slotIndex);

            msg.send(viewer, "champ-squad-claimed",

                    "player", fp != null ? fp.displayLabel() : ("#" + (slotIndex + 1)));

        } else {

            msg.send(viewer, "champ-squad-taken");

        }

        open();

    }



    private boolean isRu() {

        String lang = plugin.getConfig().getString("settings.language", "en");

        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("ru");

    }



    @Override

    public void handleClick(int slot, ClickType type) {

        Runnable action = MenuClicks.get(Integer.toHexString(System.identityHashCode(inventory)), slot);

        if (action != null) {

            clickSound();

            action.run();

        }

    }

    private String nationChatColor(ChampTeam team, com.ballgame.soccer.fifa.NationKitColors.TeamSlot slot) {
        if (team == null || team.getNationCode() == null) {
            return slot == com.ballgame.soccer.fifa.NationKitColors.TeamSlot.RED ? "&c" : "&9";
        }
        com.ballgame.soccer.fifa.FifaNation nation = plugin.fifaCatalog().get(team.getNationCode());
        return com.ballgame.soccer.fifa.NationKitColors.nationHomeChatCode(nation, slot);
    }

}

