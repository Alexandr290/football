package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.championship.ChampFixture;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Adds AI players to a scheduled Soccer Cup match before it has kicked off.
 */
public final class ChampBotsMenu extends AbstractMenu {

    private final String championshipId;
    private final String fixtureId;
    private final int redBots;
    private final int blueBots;
    private final BotDifficulty redDifficulty;
    private final BotDifficulty blueDifficulty;

    private ChampBotsMenu(SoccerPlugin plugin, Player viewer, String championshipId,
                          String fixtureId, int redBots, int blueBots,
                          BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        super(plugin, viewer, plugin.messages().raw("champ-bots-title"), 4);
        this.championshipId = championshipId;
        this.fixtureId = fixtureId;
        this.redBots = Math.max(0, redBots);
        this.blueBots = Math.max(0, blueBots);
        this.redDifficulty = redDifficulty != null ? redDifficulty : BotDifficulty.MEDIUM;
        this.blueDifficulty = blueDifficulty != null ? blueDifficulty : BotDifficulty.MEDIUM;
    }

    public static void open(SoccerPlugin plugin, Player player, String championshipId,
                            String fixtureId, int redBots, int blueBots) {
        Game game = plugin.games().findGameByFixture(championshipId, fixtureId);
        BotDifficulty currentRed = game != null ? game.getBotDifficulty(TeamSide.RED) : BotDifficulty.MEDIUM;
        BotDifficulty currentBlue = game != null ? game.getBotDifficulty(TeamSide.BLUE) : BotDifficulty.MEDIUM;
        open(plugin, player, championshipId, fixtureId, redBots, blueBots, currentRed, currentBlue);
    }

    public static void open(SoccerPlugin plugin, Player player, String championshipId,
                            String fixtureId, int redBots, int blueBots, BotDifficulty difficulty) {
        open(plugin, player, championshipId, fixtureId, redBots, blueBots, difficulty, difficulty);
    }

    public static void open(SoccerPlugin plugin, Player player, String championshipId,
                            String fixtureId, int redBots, int blueBots,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        new ChampBotsMenu(plugin, player, championshipId, fixtureId, redBots, blueBots,
                redDifficulty, blueDifficulty).open();
    }

    @Override
    protected void render() {
        fillBorder(Material.PURPLE_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        Championship championship = plugin.championships().get(championshipId);
        ChampFixture fixture = championship != null ? championship.getFixture(fixtureId) : null;
        if (championship == null || fixture == null) {
            inventory.setItem(13, Items.named(Material.BARRIER, msg.raw("champ-not-found")));
            backButton(31, () -> ChampMenus.openFixturesMenu(plugin, viewer, championshipId));
            return;
        }

        Game game = plugin.games().findGameByFixture(championshipId, fixtureId);
        int redCurrent = game != null ? game.getTeamCount(TeamSide.RED) : 0;
        int blueCurrent = game != null ? game.getTeamCount(TeamSide.BLUE) : 0;
        int max = plugin.settings().maxPlayersPerTeam;
        int redAdd = Math.min(redBots, Math.max(0, max - redCurrent));
        int blueAdd = Math.min(blueBots, Math.max(0, max - blueCurrent));

        inventory.setItem(4, Items.named(Material.ZOMBIE_HEAD,
                msg.raw("champ-bots-header"),
                "&7" + fixture.sideADisplay(championship) + " vs " + fixture.sideBDisplay(championship),
                msg.format("champ-bots-current",
                        "red", String.valueOf(redCurrent),
                        "blue", String.valueOf(blueCurrent),
                        "max", String.valueOf(max)),
                msg.format("champ-bots-red-difficulty",
                        "difficulty", msg.raw(redDifficulty.langKey())),
                msg.format("champ-bots-blue-difficulty",
                        "difficulty", msg.raw(blueDifficulty.langKey())),
                msg.raw("champ-bots-header-lore")));

        renderSideControls(10, 11, 12, TeamSide.RED, fixture.sideADisplay(championship), redAdd, redCurrent, max);
        renderSideControls(14, 15, 16, TeamSide.BLUE, fixture.sideBDisplay(championship), blueAdd, blueCurrent, max);
        renderDifficultyControls();

        inventory.setItem(22, Items.named(Material.EMERALD,
                msg.raw("champ-bots-confirm"),
                msg.format("champ-bots-confirm-lore",
                        "red", String.valueOf(redAdd),
                        "blue", String.valueOf(blueAdd)),
                msg.format("champ-bots-red-difficulty",
                        "difficulty", msg.raw(redDifficulty.langKey())),
                msg.format("champ-bots-blue-difficulty",
                        "difficulty", msg.raw(blueDifficulty.langKey()))));
        registerClick(22, () -> confirm(redAdd, blueAdd));

        backButton(31, () -> ChampMenus.openFixturesMenu(plugin, viewer, championshipId));
    }

    private void renderSideControls(int lessSlot, int displaySlot, int moreSlot, TeamSide side,
                                    String teamName, int add, int current, int max) {
        Messages msg = plugin.messages();
        inventory.setItem(lessSlot, Items.named(Material.RED_DYE,
                msg.raw("champ-bots-less"),
                msg.raw("champ-bots-adjust-lore")));
        registerClick(lessSlot, () -> reopen(side, -1));

        inventory.setItem(displaySlot, Items.named(side == TeamSide.RED ? Material.RED_WOOL : Material.BLUE_WOOL,
                msg.format("champ-bots-side", "team", teamName),
                msg.format("champ-bots-add-count", "count", String.valueOf(add)),
                msg.format("champ-bots-team-count",
                        "count", String.valueOf(current),
                        "max", String.valueOf(max))));

        inventory.setItem(moreSlot, Items.named(Material.LIME_DYE,
                msg.raw("champ-bots-more"),
                msg.raw("champ-bots-adjust-lore")));
        registerClick(moreSlot, () -> reopen(side, 1));
    }

    private void renderDifficultyControls() {
        Messages msg = plugin.messages();
        inventory.setItem(19, Items.named(Material.RED_DYE,
                msg.raw("champ-bots-difficulty-prev"),
                msg.raw("champ-bots-adjust-lore")));
        registerClick(19, () -> reopen(TeamSide.RED, redDifficulty.previous()));

        Material redIcon = switch (redDifficulty) {
            case EASY -> Material.LIME_CONCRETE;
            case MEDIUM -> Material.YELLOW_CONCRETE;
            case HARD -> Material.RED_CONCRETE;
        };
        inventory.setItem(20, Items.named(redIcon,
                msg.raw("champ-bots-red-difficulty-title"),
                msg.format("champ-bots-red-difficulty", "difficulty", msg.raw(redDifficulty.langKey())),
                msg.raw("champ-bots-difficulty-lore")));

        inventory.setItem(21, Items.named(Material.LIME_DYE,
                msg.raw("champ-bots-difficulty-next"),
                msg.raw("champ-bots-adjust-lore")));
        registerClick(21, () -> reopen(TeamSide.RED, redDifficulty.next()));

        inventory.setItem(23, Items.named(Material.RED_DYE,
                msg.raw("champ-bots-difficulty-prev"),
                msg.raw("champ-bots-adjust-lore")));
        registerClick(23, () -> reopen(TeamSide.BLUE, blueDifficulty.previous()));

        Material blueIcon = switch (blueDifficulty) {
            case EASY -> Material.LIME_CONCRETE;
            case MEDIUM -> Material.YELLOW_CONCRETE;
            case HARD -> Material.RED_CONCRETE;
        };
        inventory.setItem(24, Items.named(blueIcon,
                msg.raw("champ-bots-blue-difficulty-title"),
                msg.format("champ-bots-blue-difficulty", "difficulty", msg.raw(blueDifficulty.langKey())),
                msg.raw("champ-bots-difficulty-lore")));

        inventory.setItem(25, Items.named(Material.LIME_DYE,
                msg.raw("champ-bots-difficulty-next"),
                msg.raw("champ-bots-adjust-lore")));
        registerClick(25, () -> reopen(TeamSide.BLUE, blueDifficulty.next()));
    }

    private void reopen(TeamSide side, int delta) {
        int nextRed = side == TeamSide.RED ? redBots + delta : redBots;
        int nextBlue = side == TeamSide.BLUE ? blueBots + delta : blueBots;
        ChampBotsMenu.open(plugin, viewer, championshipId, fixtureId,
                nextRed, nextBlue, redDifficulty, blueDifficulty);
    }

    private void reopen(TeamSide side, BotDifficulty nextDifficulty) {
        BotDifficulty nextRed = side == TeamSide.RED ? nextDifficulty : redDifficulty;
        BotDifficulty nextBlue = side == TeamSide.BLUE ? nextDifficulty : blueDifficulty;
        ChampBotsMenu.open(plugin, viewer, championshipId, fixtureId, redBots, blueBots, nextRed, nextBlue);
    }

    private void confirm(int redAdd, int blueAdd) {
        Championship championship = plugin.championships().get(championshipId);
        ChampFixture fixture = championship != null ? championship.getFixture(fixtureId) : null;
        if (championship == null || fixture == null) {
            plugin.messages().send(viewer, "champ-not-found");
            return;
        }
        int added = plugin.games().addChampionshipBots(championship, fixture, redAdd, blueAdd,
                redDifficulty, blueDifficulty);
        if (added == -1) {
            plugin.messages().send(viewer, "champ-bots-difficulty-updated",
                    "red_difficulty", plugin.messages().raw(redDifficulty.langKey()),
                    "blue_difficulty", plugin.messages().raw(blueDifficulty.langKey()));
            ChampMenus.openFixturesMenu(plugin, viewer, championshipId);
        } else if (added > 0) {
            plugin.messages().send(viewer, "champ-bots-added",
                    "count", String.valueOf(added),
                    "red_difficulty", plugin.messages().raw(redDifficulty.langKey()),
                    "blue_difficulty", plugin.messages().raw(blueDifficulty.langKey()));
            ChampMenus.openFixturesMenu(plugin, viewer, championshipId);
        } else {
            plugin.messages().send(viewer, "champ-bots-failed");
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
