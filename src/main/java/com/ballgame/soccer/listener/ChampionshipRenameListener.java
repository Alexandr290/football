package com.ballgame.soccer.listener;



import com.ballgame.soccer.SoccerPlugin;

import com.ballgame.soccer.championship.ChampRenamePrompt;

import com.ballgame.soccer.championship.ChampSchedulePrompt;

import org.bukkit.entity.Player;

import org.bukkit.event.EventHandler;

import org.bukkit.event.EventPriority;

import org.bukkit.event.Listener;

import org.bukkit.event.player.AsyncPlayerChatEvent;

import org.bukkit.event.player.PlayerQuitEvent;



public final class ChampionshipRenameListener implements Listener {



    private final SoccerPlugin plugin;



    public ChampionshipRenameListener(SoccerPlugin plugin) {

        this.plugin = plugin;

    }



    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)

    public void onChat(AsyncPlayerChatEvent event) {

        Player player = event.getPlayer();

        if (!ChampRenamePrompt.hasPending(player) && !ChampSchedulePrompt.hasPending(player)) {

            return;

        }

        event.setCancelled(true);

        String message = event.getMessage();

        plugin.getServer().getScheduler().runTask(plugin, () -> {

            if (ChampRenamePrompt.hasPending(player)) {

                ChampRenamePrompt.handleChat(plugin, player, message);

            } else if (ChampSchedulePrompt.hasPending(player)) {

                ChampSchedulePrompt.handleChat(plugin, player, message);

            }

        });

    }



    @EventHandler

    public void onQuit(PlayerQuitEvent event) {

        Player player = event.getPlayer();

        ChampRenamePrompt.cancel(player);

        ChampSchedulePrompt.cancel(player);

    }

}

