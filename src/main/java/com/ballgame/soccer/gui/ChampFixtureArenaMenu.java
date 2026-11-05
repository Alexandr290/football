package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.championship.ChampFixture;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/** Arena picker for an existing championship fixture. */
public final class ChampFixtureArenaMenu extends AbstractMenu {

    private static final int[] ARENA_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };

    private final String championshipId;
    private final String fixtureId;

    private ChampFixtureArenaMenu(SoccerPlugin plugin, Player viewer,
                                  String championshipId, String fixtureId) {
        super(plugin, viewer, plugin.messages().raw("champ-fixture-arena-title"), 4);
        this.championshipId = championshipId;
        this.fixtureId = fixtureId;
    }

    public static void open(SoccerPlugin plugin, Player player,
                            String championshipId, String fixtureId) {
        new ChampFixtureArenaMenu(plugin, player, championshipId, fixtureId).open();
    }

    @Override
    protected void render() {
        fillBorder(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        Championship championship = plugin.championships().get(championshipId);
        ChampFixture fixture = championship != null ? championship.getFixture(fixtureId) : null;
        if (championship == null || fixture == null) {
            inventory.setItem(22, Items.named(Material.BARRIER, msg.raw("champ-not-found")));
            backButton(31, () -> ChampMenus.openFixturesMenu(plugin, viewer, championshipId));
            return;
        }

        String matchLine = fixture.sideADisplay(championship) + " vs "
                + fixture.sideBDisplay(championship);
        String currentArena = plugin.championships().resolveFixtureArenaName(championship, fixture);
        List<Arena> arenas = availableArenas();

        inventory.setItem(4, Items.named(Material.MAP,
                msg.raw("champ-fixture-arena-header"),
                "&7" + matchLine,
                msg.format("champ-fixture-arena-current", "arena", nullOr(currentArena, "-")),
                msg.raw("champ-fixture-arena-lore"),
                msg.format("quickjoin-arena-count", "count", String.valueOf(arenas.size()))));

        if (arenas.isEmpty()) {
            inventory.setItem(22, Items.named(Material.BARRIER,
                    msg.raw("quickjoin-arena-empty"),
                    msg.raw("quickjoin-arena-empty-lore")));
        } else {
            for (int i = 0; i < arenas.size() && i < ARENA_SLOTS.length; i++) {
                Arena arena = arenas.get(i);
                boolean selected = arena.getName().equalsIgnoreCase(currentArena);
                inventory.setItem(ARENA_SLOTS[i], Items.named(
                        selected ? Material.LIME_GLAZED_TERRACOTTA : Material.GRASS_BLOCK,
                        (selected ? "&a&l" : "&f") + arena.getName(),
                        selected
                                ? msg.raw("champ-fixture-arena-selected")
                                : msg.raw("champ-fixture-arena-click")));
                registerClick(ARENA_SLOTS[i], () -> applyArena(championship, fixture, arena));
            }
        }

        backButton(31, () -> ChampMenus.openFixturesMenu(plugin, viewer, championshipId));
    }

    private List<Arena> availableArenas() {
        List<Arena> arenas = new ArrayList<>();
        for (Arena arena : plugin.arenas().getArenas()) {
            if (arena.isPlayable()) {
                arenas.add(arena);
            }
        }
        return arenas;
    }

    private void applyArena(Championship championship, ChampFixture fixture, Arena arena) {
        viewer.closeInventory();
        plugin.championships().setFixtureArena(viewer, championship, fixture, arena.getName());
        ChampMenus.openFixturesMenu(plugin, viewer, championshipId);
    }

    private static String nullOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
