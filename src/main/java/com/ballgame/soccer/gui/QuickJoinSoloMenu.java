package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.bot.BotFormation;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.QuickJoinMode;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Solo quick join — red/blue bot counts, per-team formations, difficulty, then team pick.
 */
public final class QuickJoinSoloMenu extends AbstractMenu {

    private int redBots;
    private int blueBots;
    private BotDifficulty redDifficulty;
    private BotDifficulty blueDifficulty;
    private BotFormation redFormation;
    private BotFormation blueFormation;

    private QuickJoinSoloMenu(SoccerPlugin plugin, Player viewer, int redBots, int blueBots,
                              BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                              BotFormation redFormation, BotFormation blueFormation) {
        super(plugin, viewer, plugin.messages().raw("quickjoin-solo-title"), 3);
        this.redBots = clampBots(plugin, redBots);
        this.blueBots = clampBots(plugin, blueBots);
        this.redDifficulty = redDifficulty != null ? redDifficulty : BotDifficulty.MEDIUM;
        this.blueDifficulty = blueDifficulty != null ? blueDifficulty : BotDifficulty.MEDIUM;
        this.redFormation = redFormation != null ? redFormation : BotFormation.FOUR_FOUR_TWO;
        this.blueFormation = blueFormation != null ? blueFormation : BotFormation.FOUR_FOUR_TWO;
    }

    public static void open(SoccerPlugin plugin, Player player) {
        open(plugin, player, plugin.settings().botDefaultSoloCount,
                plugin.settings().botDefaultSoloCount);
    }

    public static void open(SoccerPlugin plugin, Player player, int redBots, int blueBots) {
        open(plugin, player, redBots, blueBots, BotDifficulty.MEDIUM, BotDifficulty.MEDIUM);
    }

    public static void open(SoccerPlugin plugin, Player player, int redBots, int blueBots,
                            BotDifficulty difficulty) {
        open(plugin, player, redBots, blueBots, difficulty, difficulty);
    }

    public static void open(SoccerPlugin plugin, Player player, int redBots, int blueBots,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty) {
        open(plugin, player, redBots, blueBots, redDifficulty, blueDifficulty,
                BotFormation.FOUR_FOUR_TWO, BotFormation.FOUR_FOUR_TWO);
    }

    public static void open(SoccerPlugin plugin, Player player, int redBots, int blueBots,
                            BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                            BotFormation redFormation, BotFormation blueFormation) {
        new QuickJoinSoloMenu(plugin, player, redBots, blueBots,
                redDifficulty, blueDifficulty, redFormation, blueFormation).open();
    }

    /** Legacy: opponents on the picked team side, allies on the other. */
    public static void openLegacy(SoccerPlugin plugin, Player player, int opponents, int allies,
                                  BotDifficulty redDifficulty, BotDifficulty blueDifficulty,
                                  BotFormation formation) {
        open(plugin, player, opponents, allies > 0 ? allies : opponents,
                redDifficulty, blueDifficulty, formation, formation);
    }

    @Override
    protected void render() {
        fillBorder(Material.LIME_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        int max = plugin.settings().botMaxSoloCount;

        inventory.setItem(4, Items.named(Material.ZOMBIE_HEAD,
                msg.raw("quickjoin-solo-header"),
                msg.format("quickjoin-solo-red-count", "count", String.valueOf(redBots), "max", String.valueOf(max)),
                msg.format("quickjoin-solo-blue-count", "count", String.valueOf(blueBots), "max", String.valueOf(max)),
                msg.format("quickjoin-solo-red-formation", "formation", msg.raw(redFormation.langKey())),
                msg.format("quickjoin-solo-blue-formation", "formation", msg.raw(blueFormation.langKey())),
                msg.raw("quickjoin-solo-header-lore")));

        inventory.setItem(1, Items.named(Material.RED_DYE,
                msg.raw("quickjoin-solo-red-formation-prev"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(1, () -> reopen(redFormation.previous(), blueFormation));

        inventory.setItem(2, Items.named(Material.RED_BANNER,
                msg.raw("quickjoin-solo-red-formation-title"),
                msg.format("quickjoin-solo-red-formation", "formation", msg.raw(redFormation.langKey())),
                msg.raw("quickjoin-solo-formation-lore")));

        inventory.setItem(3, Items.named(Material.LIME_DYE,
                msg.raw("quickjoin-solo-red-formation-next"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(3, () -> reopen(redFormation.next(), blueFormation));

        inventory.setItem(5, Items.named(Material.BLUE_DYE,
                msg.raw("quickjoin-solo-blue-formation-prev"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(5, () -> reopen(redFormation, blueFormation.previous()));

        inventory.setItem(6, Items.named(Material.BLUE_BANNER,
                msg.raw("quickjoin-solo-blue-formation-title"),
                msg.format("quickjoin-solo-blue-formation", "formation", msg.raw(blueFormation.langKey())),
                msg.raw("quickjoin-solo-formation-lore")));

        inventory.setItem(7, Items.named(Material.LIME_DYE,
                msg.raw("quickjoin-solo-blue-formation-next"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(7, () -> reopen(redFormation, blueFormation.next()));

        inventory.setItem(10, Items.named(Material.RED_DYE,
                msg.raw("quickjoin-solo-red-less"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(10, () -> reopen(redBots - 1, blueBots));

        inventory.setItem(11, Items.named(Material.RED_WOOL,
                msg.raw("quickjoin-solo-red-header"),
                msg.format("quickjoin-solo-red-count", "count", String.valueOf(redBots), "max", String.valueOf(max)),
                msg.raw("quickjoin-solo-red-bots-lore")));

        inventory.setItem(12, Items.named(Material.LIME_DYE,
                msg.raw("quickjoin-solo-red-more"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(12, () -> reopen(redBots + 1, blueBots));

        inventory.setItem(13, Items.named(Material.PAPER,
                msg.raw("quickjoin-solo-confirm"),
                msg.format("quickjoin-solo-red-count", "count", String.valueOf(redBots), "max", String.valueOf(max)),
                msg.format("quickjoin-solo-blue-count", "count", String.valueOf(blueBots), "max", String.valueOf(max))));
        registerClick(13, this::openTeamPick);

        inventory.setItem(14, Items.named(Material.RED_DYE,
                msg.raw("quickjoin-solo-blue-less"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(14, () -> reopen(redBots, blueBots - 1));

        inventory.setItem(15, Items.named(Material.BLUE_WOOL,
                msg.raw("quickjoin-solo-blue-header"),
                msg.format("quickjoin-solo-blue-count", "count", String.valueOf(blueBots), "max", String.valueOf(max)),
                msg.raw("quickjoin-solo-blue-bots-lore")));

        inventory.setItem(16, Items.named(Material.LIME_DYE,
                msg.raw("quickjoin-solo-blue-more"),
                msg.raw("quickjoin-solo-adjust-lore")));
        registerClick(16, () -> reopen(redBots, blueBots + 1));

        renderDifficultyControls(msg);

        backButton(22, () -> QuickJoinModeMenu.open(plugin, viewer));
    }

    private void reopen(int red, int blue) {
        new QuickJoinSoloMenu(plugin, viewer, red, blue,
                redDifficulty, blueDifficulty, redFormation, blueFormation).open();
    }

    private void reopen(BotFormation nextRed, BotFormation nextBlue) {
        new QuickJoinSoloMenu(plugin, viewer, redBots, blueBots,
                redDifficulty, blueDifficulty, nextRed, nextBlue).open();
    }

    private void reopen(TeamDifficultyTarget target, BotDifficulty nextDifficulty) {
        BotDifficulty nextRed = target == TeamDifficultyTarget.RED ? nextDifficulty : redDifficulty;
        BotDifficulty nextBlue = target == TeamDifficultyTarget.BLUE ? nextDifficulty : blueDifficulty;
        new QuickJoinSoloMenu(plugin, viewer, redBots, blueBots, nextRed, nextBlue,
                redFormation, blueFormation).open();
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
                redBots, blueBots, redDifficulty, blueDifficulty, redFormation, blueFormation);
    }

    private enum TeamDifficultyTarget {
        RED,
        BLUE
    }

    private static int clampBots(SoccerPlugin plugin, int count) {
        int max = plugin.settings().botMaxSoloCount;
        int min = plugin.settings().botMinSoloCount;
        return Math.max(min, Math.min(max, count));
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
