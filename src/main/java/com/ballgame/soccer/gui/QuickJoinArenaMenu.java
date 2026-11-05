package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.bot.BotFormation;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.GameState;
import com.ballgame.soccer.game.QuickJoinMode;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/**
 * Arena picker shown before team selection so players can plan a match on a
 * specific pitch instead of always accepting the auto-selected quickjoin arena.
 */
public final class QuickJoinArenaMenu extends AbstractMenu {

    private static final int[] ARENA_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };

    private final QuickJoinMode mode;
    private final int redBots;
    private final int blueBots;
    private final BotDifficulty redDifficulty;
    private final BotDifficulty blueDifficulty;
    private final BotFormation redFormation;
    private final BotFormation blueFormation;

    private QuickJoinArenaMenu(SoccerPlugin plugin, Player viewer, QuickJoinMode mode,
                               int redBots, int blueBots,
                               BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                               BotFormation redFormation, BotFormation blueFormation) {
        super(plugin, viewer, plugin.messages().raw("quickjoin-arena-title"), 4);
        this.mode = mode;
        this.redBots = redBots;
        this.blueBots = blueBots;
        this.redDifficulty = redDifficulty != null ? redDifficulty : BotDifficulty.MEDIUM;
        this.blueDifficulty = blueDifficulty != null ? blueDifficulty : BotDifficulty.MEDIUM;
        this.redFormation = redFormation != null ? redFormation : BotFormation.FOUR_FOUR_TWO;
        this.blueFormation = blueFormation != null ? blueFormation : BotFormation.FOUR_FOUR_TWO;
    }

    /** Legacy: botCount = opponents, teammateCount = allies (relative to picked team). */
    public static void open(SoccerPlugin plugin, Player player, QuickJoinMode mode,
                            int botCount, int teammateCount) {
        open(plugin, player, mode, botCount, teammateCount, BotDifficulty.MEDIUM);
    }

    public static void open(SoccerPlugin plugin, Player player, QuickJoinMode mode,
                            int botCount, int teammateCount, BotDifficulty difficulty) {
        open(plugin, player, mode, botCount, teammateCount, difficulty, difficulty);
    }

    public static void open(SoccerPlugin plugin, Player player, QuickJoinMode mode,
                            int botCount, int teammateCount,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        open(plugin, player, mode, botCount, teammateCount, redDifficulty, blueDifficulty,
                BotFormation.FOUR_FOUR_TWO);
    }

    public static void open(SoccerPlugin plugin, Player player, QuickJoinMode mode,
                            int botCount, int teammateCount,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                            BotFormation formation) {
        int red = teammateCount > 0 ? teammateCount : botCount;
        int blue = botCount;
        open(plugin, player, mode, red, blue, redDifficulty, blueDifficulty, formation, formation);
    }

    public static void open(SoccerPlugin plugin, Player player, QuickJoinMode mode,
                            int redBots, int blueBots,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                            BotFormation redFormation, BotFormation blueFormation) {
        new QuickJoinArenaMenu(plugin, player, mode, redBots, blueBots,
                redDifficulty, blueDifficulty, redFormation, blueFormation).open();
    }

    @Override
    protected void render() {
        fillBorder(Material.GRAY_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();

        List<Arena> arenas = availableArenas();
        inventory.setItem(4, Items.named(Material.MAP,
                msg.raw("quickjoin-arena-header"),
                msg.raw(mode == QuickJoinMode.SOLO
                        ? "quickjoin-arena-solo-lore"
                        : "quickjoin-arena-teams-lore"),
                msg.format("quickjoin-arena-count", "count", String.valueOf(arenas.size()))));

        if (arenas.isEmpty()) {
            inventory.setItem(22, Items.named(Material.BARRIER,
                    msg.raw("quickjoin-arena-empty"),
                    msg.raw("quickjoin-arena-empty-lore")));
        } else {
            for (int i = 0; i < arenas.size() && i < ARENA_SLOTS.length; i++) {
                Arena arena = arenas.get(i);
                Game game = plugin.games().getGame(arena);
                int players = game != null && !game.isEnded() ? game.playerCount() : 0;
                String state = game != null && !game.isEnded()
                        ? game.getState().display()
                        : msg.raw("quickjoin-gui-state-ready");
                Material icon = mode == QuickJoinMode.SOLO ? Material.IRON_SWORD : Material.GRASS_BLOCK;
                inventory.setItem(ARENA_SLOTS[i], Items.named(icon,
                        msg.format("quickjoin-arena-item", "arena", arena.getName()),
                        msg.format("quickjoin-gui-status", "state", state),
                        msg.format("quickjoin-gui-players", "count", String.valueOf(players)),
                        msg.raw("quickjoin-arena-click")));
                registerClick(ARENA_SLOTS[i], () -> openTeamPicker(arena));
            }
        }

        if (mode == QuickJoinMode.SOLO) {
            backButton(31, () -> QuickJoinSoloMenu.open(plugin, viewer,
                    redBots, blueBots, redDifficulty, blueDifficulty, redFormation, blueFormation));
        } else {
            backButton(31, () -> QuickJoinModeMenu.open(plugin, viewer));
        }
    }

    private List<Arena> availableArenas() {
        List<Arena> arenas = new ArrayList<>();
        for (Arena arena : plugin.arenas().getArenas()) {
            if (!arena.isPlayable()) {
                continue;
            }
            Game game = plugin.games().getGame(arena);
            if (isAvailableForMode(game)) {
                arenas.add(arena);
            }
        }
        return arenas;
    }

    private boolean isAvailableForMode(Game game) {
        if (game == null || game.isEnded()) {
            return true;
        }
        if (game.getState() != GameState.WAITING && game.getState() != GameState.COUNTDOWN) {
            return false;
        }
        if (game.isFull()) {
            return false;
        }
        return mode == QuickJoinMode.SOLO ? game.isSoloMatch() : !game.isSoloMatch();
    }

    private void openTeamPicker(Arena arena) {
        Game game = plugin.games().getGame(arena);
        if (game != null && game.isEnded()) {
            game = null;
        }
        QuickJoinMenu.open(plugin, viewer, arena, game, mode, redBots, blueBots,
                redDifficulty, blueDifficulty, redFormation, blueFormation);
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
