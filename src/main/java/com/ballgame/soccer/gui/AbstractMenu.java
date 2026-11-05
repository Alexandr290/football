package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.util.Items;
import com.ballgame.soccer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public abstract class AbstractMenu implements InventoryHolder {

    protected final SoccerPlugin plugin;
    protected final Player viewer;
    protected final Inventory inventory;
    private final Map<Integer, Runnable> clicks = new HashMap<>();

    protected AbstractMenu(SoccerPlugin plugin, Player viewer, String title, int rows) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, rows * 9, Text.color(title));
    }

    public void open() {
        clicks.clear();
        MenuClicks.clear(menuKey());
        render();
        viewer.openInventory(inventory);
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.1f);
    }

    protected abstract void render();

    public void handleClick(int slot, ClickType type) {
        Runnable action = clicks.get(slot);
        if (action != null) {
            clickSound();
            action.run();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player getViewer() {
        return viewer;
    }

    protected void fillBorder(Material material) {
        ItemStack pane = Items.named(material, " ");
        int size = inventory.getSize();
        for (int i = 0; i < size; i++) {
            if (i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8) {
                inventory.setItem(i, pane);
            }
        }
    }

    protected void backButton(int slot, Runnable action) {
        inventory.setItem(slot, Items.named(Material.ARROW, "&c&lBack", "&7Return to previous menu"));
        registerClick(slot, action);
    }

    protected void registerClick(int slot, Runnable action) {
        clicks.put(slot, action);
        MenuClicks.register(menuKey(), slot, action);
    }

    private String menuKey() {
        return Integer.toHexString(System.identityHashCode(inventory));
    }

    protected void clickSound() {
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
    }
}
