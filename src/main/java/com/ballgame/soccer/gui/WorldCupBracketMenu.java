package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.championship.ChampionshipType;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.fifa.FifaNation;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Manual World Cup R16 bracket builder — 16 nation slots. */
public final class WorldCupBracketMenu extends AbstractMenu {

    private final String championshipId;
    private int page;

    public WorldCupBracketMenu(SoccerPlugin plugin, Player viewer, String championshipId) {
        this(plugin, viewer, championshipId, 0);
    }

    public WorldCupBracketMenu(SoccerPlugin plugin, Player viewer, String championshipId, int page) {
        super(plugin, viewer, plugin.messages().raw("champ-gui-wc-bracket-title"), 6);
        this.championshipId = championshipId;
        this.page = Math.max(0, page);
    }

    @Override
    protected void render() {
        fillBorder(Material.BLUE_STAINED_GLASS_PANE);
        Messages msg = plugin.messages();
        Championship championship = plugin.championships().get(championshipId);
        if (championship == null || championship.getType() != ChampionshipType.KNOCKOUT) {
            inventory.setItem(22, Items.named(Material.BARRIER, "&cNot found"));
            backButton(49, () -> ChampMenus.openMain(plugin, viewer));
            return;
        }

        int size = plugin.settings().worldCupTeamSlots();
        inventory.setItem(4, Items.named(Material.GOLDEN_HELMET,
                msg.raw("champ-gui-wc-bracket-header"),
                msg.format("champ-gui-wc-bracket-progress",
                        "filled", String.valueOf(championship.filledBracketSlots()),
                        "required", String.valueOf(size)),
                msg.raw("champ-gui-wc-bracket-hint")));

        int start = page * 16;
        for (int i = 0; i < 16 && start + i < size; i++) {
            int slotIndex = start + i;
            int invSlot = 10 + (i % 8) + (i / 8) * 9;
            String code = championship.getBracketNation(slotIndex);
            FifaNation nation = code != null ? plugin.fifaCatalog().get(code) : null;
            Material icon = nation != null ? Material.PLAYER_HEAD : Material.GRAY_DYE;
            String title = nation != null
                    ? "&e&l" + (slotIndex + 1) + ". " + nation.localizedName(isRu())
                    : "&7&l" + (slotIndex + 1) + ". " + msg.raw("champ-gui-wc-empty-slot");
            String lore2 = nation != null
                    ? "&7" + nation.getConfederation() + " &8| &7Group " + nation.getWc2026Group()
                    : msg.raw("champ-gui-wc-click-pick");
            inventory.setItem(invSlot, Items.named(icon, title, lore2, msg.raw("champ-gui-wc-click-clear")));
            final int captured = slotIndex;
            registerClick(invSlot, () -> new NationPickMenu(plugin, viewer, championshipId, captured).open());
        }

        if (viewer.hasPermission("soccer.admin") || viewer.isOp()) {
            inventory.setItem(45, Items.named(Material.ENDER_PEARL,
                    msg.raw("champ-gui-wc-shuffle"),
                    msg.raw("champ-gui-wc-shuffle-lore")));
            registerClick(45, () -> {
                if (plugin.championships().shuffleWorldCupBracket(championship)) {
                    msg.send(viewer, "champ-wc-shuffled");
                    open();
                }
            });

            inventory.setItem(47, Items.named(Material.EMERALD_BLOCK,
                    msg.raw("champ-gui-wc-commit"),
                    msg.raw("champ-gui-wc-commit-lore")));
            registerClick(47, () -> {
                if (plugin.championships().ensureWorldCupR16Bracket(championship)) {
                    msg.send(viewer, "champ-wc-bracket-built");
                    ChampMenus.openFixturesMenu(plugin, viewer, championshipId);
                } else {
                    msg.send(viewer, "champ-start-wc-r16",
                            "filled", String.valueOf(championship.filledBracketSlots()),
                            "required", String.valueOf(size));
                }
            });

            if (championship.getState().name().equals("FINISHED")) {
                inventory.setItem(51, Items.named(Material.TNT,
                        msg.raw("champ-gui-wc-reset"),
                        msg.raw("champ-gui-wc-reset-lore")));
                registerClick(51, () -> {
                    if (plugin.championships().resetKnockoutBracket(championship)) {
                        msg.send(viewer, "champ-wc-reset");
                        open();
                    }
                });
            }
        }

        backButton(49, () -> ChampMenus.openDetail(plugin, viewer, championshipId));
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
            if (type == ClickType.RIGHT) {
                Championship championship = plugin.championships().get(championshipId);
                if (championship != null) {
                    for (int i = 0; i < 16; i++) {
                        int invSlot = 10 + (i % 8) + (i / 8) * 9;
                        if (invSlot == slot) {
                            plugin.championships().setWorldCupBracketSlot(championship, page * 16 + i, null);
                            open();
                            return;
                        }
                    }
                }
            }
            action.run();
        }
    }

    /** Nation picker for one bracket slot. */
    public static final class NationPickMenu extends AbstractMenu {

        private final String championshipId;
        private final int bracketSlot;
        private int page;

        NationPickMenu(SoccerPlugin plugin, Player viewer, String championshipId, int bracketSlot) {
            this(plugin, viewer, championshipId, bracketSlot, 0);
        }

        NationPickMenu(SoccerPlugin plugin, Player viewer, String championshipId, int bracketSlot, int page) {
            super(plugin, viewer, plugin.messages().raw("champ-gui-nation-pick-title"), 6);
            this.championshipId = championshipId;
            this.bracketSlot = bracketSlot;
            this.page = Math.max(0, page);
        }

        @Override
        protected void render() {
            fillBorder(Material.GREEN_STAINED_GLASS_PANE);
            Messages msg = plugin.messages();
            Championship championship = plugin.championships().get(championshipId);
            if (championship == null) {
                inventory.setItem(22, Items.named(Material.BARRIER, "&cNot found"));
                return;
            }

            Set<String> used = new HashSet<>();
            for (String code : championship.getBracketNationCodes()) {
                if (code != null) {
                    used.add(code.toUpperCase(Locale.ROOT));
                }
            }

            List<FifaNation> nations = plugin.fifaCatalog().sortedByRank();
            int perPage = 28;
            int from = page * perPage;
            int slot = 10;
            for (int i = from; i < nations.size() && slot < 44; i++) {
                if (slot % 9 == 0 || slot % 9 == 8) {
                    slot++;
                }
                FifaNation nation = nations.get(i);
                boolean taken = used.contains(nation.getCode())
                        && !nation.getCode().equalsIgnoreCase(championship.getBracketNation(bracketSlot));
                Material icon = taken ? Material.BARRIER : Material.WHITE_BANNER;
                inventory.setItem(slot, Items.named(icon,
                        (taken ? "&c" : "&a") + "&l" + nation.localizedName(isRu()),
                        "&7" + nation.getCode() + " &8| &7" + nation.getConfederation(),
                        "&7FIFA rank: &f" + nation.getFifaRank(),
                        "&7WC2026 group: &f" + nation.getWc2026Group(),
                        taken ? msg.raw("champ-gui-nation-taken") : msg.raw("champ-gui-nation-select")));
                if (!taken) {
                    final String code = nation.getCode();
                    registerClick(slot, () -> {
                        if (plugin.championships().setWorldCupBracketSlot(championship, bracketSlot, code)) {
                            msg.send(viewer, "champ-wc-slot-set",
                                    "slot", String.valueOf(bracketSlot + 1),
                                    "team", nation.localizedName(isRu()));
                            new WorldCupBracketMenu(plugin, viewer, championshipId).open();
                        }
                    });
                }
                slot++;
            }

            if (page > 0) {
                inventory.setItem(45, Items.named(Material.ARROW, "&ePrevious"));
                registerClick(45, () -> new NationPickMenu(plugin, viewer, championshipId, bracketSlot, page - 1).open());
            }
            if (from + perPage < nations.size()) {
                inventory.setItem(53, Items.named(Material.ARROW, "&eNext"));
                registerClick(53, () -> new NationPickMenu(plugin, viewer, championshipId, bracketSlot, page + 1).open());
            }
            backButton(49, () -> new WorldCupBracketMenu(plugin, viewer, championshipId).open());
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
    }
}
