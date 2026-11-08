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
        int rows = size / 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == rows - 1 || column == 0 || column == 8) {
                inventory.setItem(slot, pane);
            }
        }
    }

    protected void fillDashboard() {
        fillBorder(Material.GRAY_STAINED_GLASS_PANE);
    }

    protected void uiButton(int slot, Material material, String name, Runnable action, String... lore) {
        inventory.setItem(slot, Items.named(material, name, lore));
        if (action != null) {
            registerClick(slot, action);
        }
    }

    protected void backButton(int slot, Runnable action) {
        inventory.setItem(slot, Items.named(Material.ARROW, "&c&lНазад", "&7Вернуться на предыдущий экран"));
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
