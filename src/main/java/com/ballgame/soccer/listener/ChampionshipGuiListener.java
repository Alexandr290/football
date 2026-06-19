package com.ballgame.soccer.listener;

import com.ballgame.soccer.gui.AbstractMenu;
import com.ballgame.soccer.gui.MenuClicks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

public final class ChampionshipGuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof AbstractMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.getUniqueId().equals(menu.getViewer().getUniqueId())) {
            return;
        }
        menu.handleClick(event.getRawSlot(), event.getClick());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof AbstractMenu) {
            MenuClicks.clear(Integer.toHexString(System.identityHashCode(event.getInventory())));
        }
    }
}
