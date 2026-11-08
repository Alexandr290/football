package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.setup.MatchSetupSession;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Accept/decline screen pushed to an invited online player. */
public final class MatchInviteMenu extends AbstractMenu {

    private final UUID sessionId;

    private MatchInviteMenu(SoccerPlugin plugin, Player viewer, UUID sessionId) {
        super(plugin, viewer, "&lПРИГЛАШЕНИЕ В МАТЧ", 3);
        this.sessionId = sessionId;
    }

    public static void open(SoccerPlugin plugin, Player player, UUID sessionId) {
        new MatchInviteMenu(plugin, player, sessionId).open();
    }

    @Override
    protected void render() {
        fillDashboard();
        MatchSetupSession session = plugin.matchSetup().get(sessionId);
        if (session == null) {
            uiButton(13, Material.BARRIER, "&cПриглашение больше не действует", null);
            return;
        }
        Player owner = Bukkit.getPlayer(session.getOwnerId());
        String ownerName = owner != null ? owner.getName() : "Создатель";
        uiButton(4, Material.NAME_TAG, "&f" + ownerName + " приглашает вас", null,
                "&7Вы сможете выбрать клуб и свободную позицию.");
        uiButton(11, Material.LIME_DYE, "&a&lПРИНЯТЬ", () -> {
            if (plugin.matchSetup().accept(viewer, sessionId)) {
                MatchSetupMenu.openLobby(plugin, viewer, session);
            } else {
                viewer.closeInventory();
                viewer.sendMessage("§cПриглашение больше не действует.");
            }
        });
        uiButton(15, Material.RED_DYE, "&c&lОТКЛОНИТЬ", () -> {
            plugin.matchSetup().decline(viewer.getUniqueId(), sessionId);
            viewer.closeInventory();
        });
    }
}
