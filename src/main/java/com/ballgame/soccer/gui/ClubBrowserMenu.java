package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.fifa.FifaClub;
import com.ballgame.soccer.fifa.FifaLeague;
import com.ballgame.soccer.fifa.FifaPlayer;
import com.ballgame.soccer.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Read-only Russian club, league and starting-XI browser. */
public final class ClubBrowserMenu {

    private ClubBrowserMenu() {
    }

    public static void open(SoccerPlugin plugin, Player player) {
        new Leagues(plugin, player).open();
    }

    private static final class Leagues extends AbstractMenu {
        private Leagues(SoccerPlugin plugin, Player viewer) {
            super(plugin, viewer, "&lКЛУБЫ И ЛИГИ", 6);
        }

        @Override
        protected void render() {
            fillDashboard();
            int slot = 10;
            for (FifaLeague league : plugin.clubCatalog().leagues()) {
                while (slot % 9 == 0 || slot % 9 == 8) {
                    slot++;
                }
                List<FifaClub> clubs = plugin.clubCatalog().clubsInLeague(league);
                inventory.setItem(slot, Items.named(Material.BOOK,
                        "&b&l" + league.getNameRu(),
                        "&7" + league.getName(),
                        "&fКлубов: &b" + clubs.size(),
                        "",
                        "&eНажмите, чтобы открыть"));
                String code = league.getCode();
                registerClick(slot, () -> new Clubs(plugin, viewer, code).open());
                if (++slot > 43) {
                    break;
                }
            }
            backButton(49, () -> MainHubMenu.open(plugin, viewer));
        }
    }

    private static final class Clubs extends AbstractMenu {
        private final String leagueCode;

        private Clubs(SoccerPlugin plugin, Player viewer, String leagueCode) {
            super(plugin, viewer, "&lКЛУБЫ ЛИГИ", 6);
            this.leagueCode = leagueCode;
        }

        @Override
        protected void render() {
            fillDashboard();
            int slot = 10;
            for (FifaClub club : plugin.clubCatalog().clubsInLeague(leagueCode)) {
                while (slot % 9 == 0 || slot % 9 == 8) {
                    slot++;
                }
                inventory.setItem(slot, Items.named(Material.SHIELD,
                        "&f&l" + club.getNameRu(),
                        "&7" + club.getName(),
                        "&7Код: &f" + club.getCode(),
                        "",
                        "&eОткрыть состав"));
                registerClick(slot, () -> new Squad(plugin, viewer, club.getCode()).open());
                if (++slot > 43) {
                    break;
                }
            }
            backButton(49, () -> new Leagues(plugin, viewer).open());
        }
    }

    private static final class Squad extends AbstractMenu {
        private final String clubCode;

        private Squad(SoccerPlugin plugin, Player viewer, String clubCode) {
            super(plugin, viewer, "&lСОСТАВ КЛУБА", 6);
            this.clubCode = clubCode;
        }

        @Override
        protected void render() {
            fillDashboard();
            FifaClub club = plugin.clubCatalog().get(clubCode);
            if (club == null) {
                backButton(49, () -> new Leagues(plugin, viewer).open());
                return;
            }
            inventory.setItem(4, Items.named(Material.NETHER_STAR,
                    "&f&l" + club.getNameRu(),
                    "&7" + club.getLeagueNameRu(),
                    "&7Основной цвет: &f" + club.getPrimaryColor(),
                    "&7Гостевой цвет: &f" + club.getSecondaryColor()));

            List<FifaPlayer> starters = new ArrayList<>(club.startingXi());
            int[] slots = {19, 20, 21, 22, 23, 24, 25, 29, 31, 33, 40};
            for (int i = 0; i < starters.size() && i < slots.length; i++) {
                FifaPlayer footballer = starters.get(i);
                inventory.setItem(slots[i], Items.named(
                        footballer.getPosition().name().equals("GK")
                                ? Material.GOLDEN_CHESTPLATE : Material.PLAYER_HEAD,
                        "&f&l" + footballer.getName(),
                        "&b" + footballer.getPosition().name(),
                        footballer.getNumber() > 0 ? "&7Номер: &f" + footballer.getNumber() : "&7Стартовый состав"));
            }
            backButton(49, () -> new Clubs(plugin, viewer, club.getLeagueCode()).open());
        }
    }
}
