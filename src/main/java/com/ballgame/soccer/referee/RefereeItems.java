package com.ballgame.soccer.referee;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/**
 * Tagged referee toolkit.
 */
public final class RefereeItems {

    public static final String WHISTLE = "whistle";
    public static final String DECISION = "decision";
    public static final String VAR = "var";
    public static final String START = "start";
    public static final String END = "end";
    public static final String ADD_TIME = "add_time";
    public static final String RESUME = "resume";

    private RefereeItems() {
    }

    public static void equip(Player player, SoccerPlugin plugin) {
        Messages msg = plugin.messages();
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setItem(0, tagged(plugin, Material.BLAZE_ROD, WHISTLE,
                msg.raw("referee-item-whistle"),
                msg.raw("referee-item-whistle-lore")));
        inv.setItem(1, tagged(plugin, Material.WRITABLE_BOOK, DECISION,
                msg.raw("referee-item-decision"),
                msg.raw("referee-item-decision-lore")));
        inv.setItem(2, tagged(plugin, Material.CARROT_ON_A_STICK, START,
                msg.raw("referee-item-start"),
                msg.raw("referee-item-start-lore")));
        inv.setItem(3, tagged(plugin, Material.SPYGLASS, VAR,
                msg.raw("referee-item-var"),
                msg.raw("referee-item-var-lore")));
        inv.setItem(4, tagged(plugin, Material.CLOCK, ADD_TIME,
                msg.raw("referee-item-add-time"),
                msg.raw("referee-item-add-time-lore")));
        inv.setItem(5, tagged(plugin, Material.CARROT_ON_A_STICK, END,
                msg.raw("referee-item-end"),
                msg.raw("referee-item-end-lore")));
        inv.setItem(8, tagged(plugin, Material.WARPED_FUNGUS_ON_A_STICK, RESUME,
                msg.raw("referee-item-resume"),
                msg.raw("referee-item-resume-lore")));
        player.getInventory().setHeldItemSlot(0);
    }

    public static String readType(SoccerPlugin plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(key(plugin), PersistentDataType.STRING);
    }

    public static String readType(SoccerPlugin plugin, Player player) {
        return readType(plugin, player.getInventory().getItemInMainHand());
    }

    private static ItemStack tagged(SoccerPlugin plugin, Material material, String type,
                                    String name, String lore) {
        ItemStack item = Items.named(material, name, lore);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, type);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static NamespacedKey key(SoccerPlugin plugin) {
        return new NamespacedKey(plugin, "referee_tool");
    }
}
