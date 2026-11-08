package com.ballgame.soccer.listener;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.gui.MainHubMenu;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Permanent command-free entry button for the football dashboard. */
public final class HubListener implements Listener {

    private final SoccerPlugin plugin;
    private final NamespacedKey menuKey;

    public HubListener(SoccerPlugin plugin) {
        this.plugin = plugin;
        this.menuKey = new NamespacedKey(plugin, "main_menu");
    }

    public void giveMenuButton(Player player) {
        if (player == null || isMatchParticipant(player)) {
            return;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (isMenuButton(item)) {
                return;
            }
        }
        ItemStack item = Items.named(Material.COMPASS, "&a&lГлавное меню",
                "&7Нажмите, чтобы открыть AdvancedSoccer");
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(menuKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        if (player.getInventory().getItem(8) == null
                || player.getInventory().getItem(8).getType() == Material.AIR) {
            player.getInventory().setItem(8, item);
        } else {
            player.getInventory().addItem(item);
        }
    }

    private boolean isMatchParticipant(Player player) {
        com.ballgame.soccer.game.Game game = plugin.games().getGame(player);
        return game != null && game.contains(player.getUniqueId());
    }

    private boolean isMenuButton(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .has(menuKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (!isMenuButton(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        MainHubMenu.open(plugin, event.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isMenuButton(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMove(InventoryClickEvent event) {
        if (isMenuButton(event.getCurrentItem())
                && event.getWhoClicked() instanceof Player player
                && !isMatchParticipant(player)) {
            event.setCancelled(true);
        }
    }
}
