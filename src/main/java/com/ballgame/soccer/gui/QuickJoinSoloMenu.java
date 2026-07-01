package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.QuickJoinMode;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Solo quick join — choose how many opponent bots and how many team-mate bots,
 * then pick your team colour.
 */
public final class QuickJoinSoloMenu extends AbstractMenu {

    private int botCount;
    private int teammateCount;
    private BotDifficulty redDifficulty;
    private BotDifficulty blueDifficulty;

    private QuickJoinSoloMenu(SoccerPlugin plugin, Player viewer, int botCount, int teammateCount,
                              BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        super(plugin, viewer, plugin.messages().raw("quickjoin-solo-title"), 3);
        this.botCount = clampOpponents(plugin, botCount);
        this.teammateCount = clampTeammates(plugin, teammateCount);
        this.redDifficulty = redDifficulty != null ? redDifficulty : BotDifficulty.MEDIUM;
        this.blueDifficulty = blueDifficulty != null ? blueDifficulty : BotDifficulty.MEDIUM;
    }

    public static void open(SoccerPlugin plugin, Player player) {
        open(plugin, player, plugin.settings().botDefaultSoloCount,
                plugin.settings().botDefaultTeammateCount);
    }

    public static void open(SoccerPlugin plugin, Player player, int botCount, int teammateCount) {
        open(plugin, player, botCount, teammateCount, BotDifficulty.MEDIUM, BotDifficulty.MEDIUM);
    }

    public static void open(SoccerPlugin plugin, Player player, int botCount, int teammateCount,
                            BotDifficulty difficulty) {
        open(plugin, player, botCount, teammateCount, difficulty, difficulty);
    }

    public static void open(SoccerPlugin plugin, Player player, int botCount, int teammateCount,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        new QuickJoinSoloMenu(plugin, player, botCount, teammateCount, redDifficulty, blueDifficulty).open();
    }

    @Override
    protected void render() {
        fillBorder(Material.LIME_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        int max = plugin.settings().botMaxSoloCount;
        int maxAllies = Math.max(0, plugin.settings().maxPlayersPerTeam - 1);
        maxAllies = Math.min(maxAllies, max);

        inventory.setItem(4, Items.named(Material.ZOMBIE_HEAD,
                msg.raw("quickjoin-solo-header"),
                msg.format("quickjoin-solo-count", "count", String.valueOf(botCount), "max", String.valueOf(max)),
                msg.format("quickjoin-solo-allies-count",
                        "count", String.valueOf(teammateCount), "max", String.valueOf(maxAllies)),
                msg.format("quickjoin-solo-red-difficulty",
                        "difficulty", msg.raw(redDifficulty.langKey())),
                msg.format("quickjoin-solo-blue-difficulty",
                        "difficulty", msg.raw(blueDifficulty.langKey())),
                msg.raw("quickjoin-solo-header-lore")));

        // --- Opponent bots (left) ---
        inventory.setItem(10, Items.named(Material.RED_DYE,
                msg.raw("quickjoin-solo-less"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(10, () -> reopen(botCount - 1, teammateCount));

        inventory.setItem(11, Items.named(Material.ZOMBIE_HEAD,
                msg.raw("quickjoin-solo-header"),
                msg.format("quickjoin-solo-count", "count", String.valueOf(botCount), "max", String.valueOf(max))));

        inventory.setItem(12, Items.named(Material.LIME_DYE,
                msg.raw("quickjoin-solo-more"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(12, () -> reopen(botCount + 1, teammateCount));

        // --- Confirm (centre) ---
        inventory.setItem(13, Items.named(Material.PAPER,
                msg.raw("quickjoin-solo-confirm"),
                msg.format("quickjoin-solo-confirm-lore", "count", String.valueOf(botCount)),
                msg.format("quickjoin-solo-red-difficulty",
                        "difficulty", msg.raw(redDifficulty.langKey())),
                msg.format("quickjoin-solo-blue-difficulty",
                        "difficulty", msg.raw(blueDifficulty.langKey()))));
        registerClick(13, this::openTeamPick);

        // --- Team-mate bots (right) ---
        int finalMaxAllies = maxAllies;
        inventory.setItem(14, Items.named(Material.RED_DYE,
                msg.raw("quickjoin-solo-allies-less"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(14, () -> reopen(botCount, teammateCount - 1));

        inventory.setItem(15, Items.named(Material.PLAYER_HEAD,
                msg.raw("quickjoin-solo-allies-header"),
                msg.format("quickjoin-solo-allies-count",
                        "count", String.valueOf(teammateCount), "max", String.valueOf(finalMaxAllies)),
                msg.raw("quickjoin-solo-allies-lore")));

        inventory.setItem(16, Items.named(Material.LIME_DYE,
                msg.raw("quickjoin-solo-allies-more"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(16, () -> reopen(botCount, teammateCount + 1));

        renderDifficultyControls(msg);

        backButton(22, () -> QuickJoinModeMenu.open(plugin, viewer));
    }

    private void reopen(int opponents, int teammates) {
        new QuickJoinSoloMenu(plugin, viewer, opponents, teammates, redDifficulty, blueDifficulty).open();
    }

    private void reopen(TeamDifficultyTarget target, BotDifficulty nextDifficulty) {
        BotDifficulty nextRed = target == TeamDifficultyTarget.RED ? nextDifficulty : redDifficulty;
        BotDifficulty nextBlue = target == TeamDifficultyTarget.BLUE ? nextDifficulty : blueDifficulty;
        new QuickJoinSoloMenu(plugin, viewer, botCount, teammateCount, nextRed, nextBlue).open();
    }

    private void renderDifficultyControls(Messages msg) {
        inventory.setItem(19, Items.named(Material.RED_DYE,
                msg.raw("quickjoin-solo-difficulty-prev"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(19, () -> reopen(TeamDifficultyTarget.RED, redDifficulty.previous()));

        Material redIcon = switch (redDifficulty) {
            case EASY -> Material.LIME_CONCRETE;
            case MEDIUM -> Material.YELLOW_CONCRETE;
            case HARD -> Material.RED_CONCRETE;
        };
        inventory.setItem(20, Items.named(redIcon,
                msg.raw("quickjoin-solo-red-difficulty-title"),
                msg.format("quickjoin-solo-red-difficulty",
                        "difficulty", msg.raw(redDifficulty.langKey())),
                msg.raw("quickjoin-solo-difficulty-lore")));

        inventory.setItem(21, Items.named(Material.LIME_DYE,
                msg.raw("quickjoin-solo-difficulty-next"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(21, () -> reopen(TeamDifficultyTarget.RED, redDifficulty.next()));

        inventory.setItem(23, Items.named(Material.RED_DYE,
                msg.raw("quickjoin-solo-difficulty-prev"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(23, () -> reopen(TeamDifficultyTarget.BLUE, blueDifficulty.previous()));

        Material blueIcon = switch (blueDifficulty) {
            case EASY -> Material.LIME_CONCRETE;
            case MEDIUM -> Material.YELLOW_CONCRETE;
            case HARD -> Material.RED_CONCRETE;
        };
        inventory.setItem(24, Items.named(blueIcon,
                msg.raw("quickjoin-solo-blue-difficulty-title"),
                msg.format("quickjoin-solo-blue-difficulty",
                        "difficulty", msg.raw(blueDifficulty.langKey())),
                msg.raw("quickjoin-solo-difficulty-lore")));

        inventory.setItem(25, Items.named(Material.LIME_DYE,
                msg.raw("quickjoin-solo-difficulty-next"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(25, () -> reopen(TeamDifficultyTarget.BLUE, blueDifficulty.next()));
    }

    private void openTeamPick() {
        if (plugin.games().findQuickJoinTarget(QuickJoinMode.SOLO) == null) {
            plugin.messages().send(viewer, "no-arenas-available");
            return;
        }
        QuickJoinArenaMenu.open(plugin, viewer, QuickJoinMode.SOLO,
                botCount, teammateCount, redDifficulty, blueDifficulty);
    }

    private enum TeamDifficultyTarget {
        RED,
        BLUE
    }

    private static int clampOpponents(SoccerPlugin plugin, int count) {
        int max = plugin.settings().botMaxSoloCount;
        int min = plugin.settings().botMinSoloCount;
        return Math.max(min, Math.min(max, count));
    }

    private static int clampTeammates(SoccerPlugin plugin, int count) {
        int max = Math.min(plugin.settings().botMaxSoloCount,
                Math.max(0, plugin.settings().maxPlayersPerTeam - 1));
        return Math.max(0, Math.min(max, count));
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
