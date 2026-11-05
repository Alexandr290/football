package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.bot.BotFormation;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.QuickJoinMode;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Lets a player pick Red or Blue before joining the best available quick-join match.
 */
public final class QuickJoinMenu extends AbstractMenu {

    private final Arena arena;
    private final Game game;
    private final QuickJoinMode mode;
    private final int redBots;
    private final int blueBots;
    private final BotDifficulty redDifficulty;
    private final BotDifficulty blueDifficulty;
    private final BotFormation redFormation;
    private final BotFormation blueFormation;

    private QuickJoinMenu(SoccerPlugin plugin, Player viewer, Arena arena, Game game,
                          QuickJoinMode mode, int redBots, int blueBots,
                          BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                          BotFormation redFormation, BotFormation blueFormation) {
        super(plugin, viewer, plugin.messages().raw("quickjoin-gui-title"), 3);
        this.arena = arena;
        this.game = game;
        this.mode = mode;
        this.redBots = redBots;
        this.blueBots = blueBots;
        this.redDifficulty = redDifficulty != null ? redDifficulty : BotDifficulty.MEDIUM;
        this.blueDifficulty = blueDifficulty != null ? blueDifficulty : BotDifficulty.MEDIUM;
        this.redFormation = redFormation != null ? redFormation : BotFormation.FOUR_FOUR_TWO;
        this.blueFormation = blueFormation != null ? blueFormation : BotFormation.FOUR_FOUR_TWO;
    }

    public static void open(SoccerPlugin plugin, Player player, Arena arena, Game game) {
        open(plugin, player, arena, game, QuickJoinMode.TEAMS, 0, 0, BotDifficulty.MEDIUM);
    }

    public static void open(SoccerPlugin plugin, Player player, Arena arena, Game game,
                            QuickJoinMode mode, int botCount) {
        open(plugin, player, arena, game, mode, botCount, 0, BotDifficulty.MEDIUM);
    }

    public static void open(SoccerPlugin plugin, Player player, Arena arena, Game game,
                            QuickJoinMode mode, int botCount, int teammateCount) {
        open(plugin, player, arena, game, mode, botCount, teammateCount, BotDifficulty.MEDIUM);
    }

    public static void open(SoccerPlugin plugin, Player player, Arena arena, Game game,
                            QuickJoinMode mode, int botCount, int teammateCount, BotDifficulty difficulty) {
        open(plugin, player, arena, game, mode, botCount, teammateCount, difficulty, difficulty);
    }

    public static void open(SoccerPlugin plugin, Player player, Arena arena, Game game,
                            QuickJoinMode mode, int botCount, int teammateCount,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        open(plugin, player, arena, game, mode, botCount, teammateCount,
                redDifficulty, blueDifficulty, BotFormation.FOUR_FOUR_TWO);
    }

    public static void open(SoccerPlugin plugin, Player player, Arena arena, Game game,
                            QuickJoinMode mode, int botCount, int teammateCount,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                            BotFormation formation) {
        int red = teammateCount > 0 ? teammateCount : botCount;
        int blue = botCount;
        open(plugin, player, arena, game, mode, red, blue,
                redDifficulty, blueDifficulty, formation, formation);
    }

    public static void open(SoccerPlugin plugin, Player player, Arena arena, Game game,
                            QuickJoinMode mode, int redBots, int blueBots,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                            BotFormation redFormation, BotFormation blueFormation) {
        new QuickJoinMenu(plugin, player, arena, game, mode, redBots, blueBots,
                redDifficulty, blueDifficulty, redFormation, blueFormation).open();
    }

    @Override
    protected void render() {
        fillBorder(Material.GRAY_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        SoccerSettings settings = plugin.settings();

        int redCount = game != null ? game.getTeamCount(TeamSide.RED) : 0;
        int blueCount = game != null ? game.getTeamCount(TeamSide.BLUE) : 0;
        String state = game != null ? game.getState().display() : msg.raw("quickjoin-gui-state-ready");

        inventory.setItem(4, Items.named(Material.COMPASS,
                msg.raw("quickjoin-gui-header"),
                msg.format("quickjoin-gui-arena", "arena", arena.getName()),
                msg.format("quickjoin-gui-status", "state", state),
                msg.format("quickjoin-gui-players", "count", String.valueOf(redCount + blueCount)),
                mode == QuickJoinMode.SOLO
                        ? msg.format("quickjoin-solo-red-count", "count", String.valueOf(redBots),
                        "max", String.valueOf(settings.botMaxSoloCount))
                        : "",
                mode == QuickJoinMode.SOLO
                        ? msg.format("quickjoin-solo-blue-count", "count", String.valueOf(blueBots),
                        "max", String.valueOf(settings.botMaxSoloCount))
                        : "",
                mode == QuickJoinMode.SOLO
                        ? msg.format("quickjoin-solo-red-formation", "formation", msg.raw(redFormation.langKey()))
                        : "",
                mode == QuickJoinMode.SOLO
                        ? msg.format("quickjoin-solo-blue-formation", "formation", msg.raw(blueFormation.langKey()))
                        : "",
                mode == QuickJoinMode.SOLO
                        ? msg.format("quickjoin-solo-red-difficulty",
                        "difficulty", msg.raw(redDifficulty.langKey()))
                        : "",
                mode == QuickJoinMode.SOLO
                        ? msg.format("quickjoin-solo-blue-difficulty",
                        "difficulty", msg.raw(blueDifficulty.langKey()))
                        : ""));

        renderTeamSlot(11, TeamSide.RED, settings.redName, settings.redChatColor,
                redCount, settings.maxPlayersPerTeam);
        renderTeamSlot(15, TeamSide.BLUE, settings.blueName, settings.blueChatColor,
                blueCount, settings.maxPlayersPerTeam);

        renderRefereeSlot(13, game != null && game.isRefereeSlotTaken());

        if (mode == QuickJoinMode.SOLO) {
            backButton(22, () -> QuickJoinSoloMenu.open(plugin, viewer,
                    redBots, blueBots, redDifficulty, blueDifficulty, redFormation, blueFormation));
        } else {
            backButton(22, () -> QuickJoinModeMenu.open(plugin, viewer));
        }
    }

    private void renderTeamSlot(int slot, TeamSide side, String teamName, String chatColor,
                                int count, int max) {
        Messages msg = plugin.messages();
        boolean full = game != null && game.isTeamFull(side);
        Material icon = side == TeamSide.RED ? Material.RED_WOOL : Material.BLUE_WOOL;
        if (full) {
            icon = Material.BARRIER;
        }

        inventory.setItem(slot, Items.named(icon,
                chatColor + "&l" + teamName,
                msg.format("quickjoin-gui-team-count",
                        "count", String.valueOf(count),
                        "max", String.valueOf(max)),
                full ? msg.raw("quickjoin-gui-team-full-lore") : msg.raw("quickjoin-gui-team-join-lore")));

        registerClick(slot, () -> tryJoin(side, teamName));
    }

    private void renderRefereeSlot(int slot, boolean taken) {
        Messages msg = plugin.messages();
        Material icon = taken ? Material.BARRIER : Material.BELL;
        String lore = taken
                ? msg.raw("quickjoin-gui-referee-taken")
                : (mode == QuickJoinMode.SOLO
                ? msg.format("quickjoin-gui-referee-solo-lore",
                "red", String.valueOf(redBots),
                "blue", String.valueOf(blueBots))
                : msg.raw("quickjoin-gui-referee-lore"));
        inventory.setItem(slot, Items.named(icon, msg.raw("quickjoin-gui-referee"), lore));
        if (!taken) {
            registerClick(slot, this::tryJoinReferee);
        }
    }

    private void tryJoinReferee() {
        if (game != null && game.isRefereeSlotTaken()) {
            plugin.messages().send(viewer, "referee-slot-taken");
            clickSound();
            return;
        }
        boolean joined;
        if (mode == QuickJoinMode.SOLO) {
            joined = plugin.games().joinSoloAsReferee(viewer, arena, redBots, blueBots,
                    redDifficulty, blueDifficulty, redFormation, blueFormation);
        } else {
            joined = plugin.games().joinAsReferee(viewer, arena);
        }
        if (joined) {
            viewer.closeInventory();
        }
    }

    private void tryJoin(TeamSide side, String teamName) {
        if (game != null && game.isTeamFull(side)) {
            plugin.messages().send(viewer, "quickjoin-team-full", "team", teamName);
            clickSound();
            return;
        }
        boolean joined = mode == QuickJoinMode.SOLO
                ? plugin.games().joinSolo(viewer, arena, side, redBots, blueBots,
                redDifficulty, blueDifficulty, redFormation, blueFormation)
                : plugin.games().join(viewer, arena, side);
        if (joined) {
            viewer.closeInventory();
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
