package com.ballgame.soccer.gui;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.fifa.FifaClub;
import com.ballgame.soccer.fifa.FifaLeague;
import com.ballgame.soccer.fifa.FifaPlayer;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.setup.MatchSetupSession;
import com.ballgame.soccer.util.Items;
import com.ballgame.soccer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Complete command-free match creation wizard. */
public final class MatchSetupMenu {

    private static final int[] RED_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30, 37, 38};
    private static final int[] BLUE_SLOTS = {16, 15, 14, 25, 24, 23, 34, 33, 32, 43, 42};
    private static final int[] DURATION_CHOICES = {60, 180, 300, 600, 900};

    private MatchSetupMenu() {
    }

    public static void open(SoccerPlugin plugin, Player player) {
        MatchSetupSession existing = plugin.matchSetup().getFor(player.getUniqueId());
        if (existing != null) {
            if (existing.readyToConfigure()) {
                openLobby(plugin, player, existing);
            } else if (existing.isOwner(player.getUniqueId())) {
                new ModeMenu(plugin, player, existing).open();
            }
            return;
        }
        new ModeMenu(plugin, player, plugin.matchSetup().create(player)).open();
    }

    public static void openLobby(SoccerPlugin plugin, Player player, MatchSetupSession session) {
        if (session != null && plugin.matchSetup().get(session.getId()) != null) {
            new LobbyMenu(plugin, player, session).open();
        }
    }

    private static final class ModeMenu extends AbstractMenu {
        private final MatchSetupSession session;

        private ModeMenu(SoccerPlugin plugin, Player viewer, MatchSetupSession session) {
            super(plugin, viewer, "&lНОВЫЙ МАТЧ — РЕЖИМ", 6);
            this.session = session;
        }

        @Override
        protected void render() {
            fillDashboard();
            modeButton(20, Material.ARMOR_STAND, MatchSetupSession.Mode.BOTS_VS_BOTS,
                    "&7Смотрите матч полностью управляемых составов.");
            modeButton(22, Material.PLAYER_HEAD, MatchSetupSession.Mode.MIXED,
                    "&7Люди занимают позиции, остальные места получают боты.");
            modeButton(24, Material.DIAMOND_SWORD, MatchSetupSession.Mode.PLAYERS_VS_PLAYERS,
                    "&7Только приглашённые игроки, без заполнения ботами.");
            backButton(49, this::cancel);
        }

        private void modeButton(int slot, Material material, MatchSetupSession.Mode mode, String lore) {
            uiButton(slot, material, "&f&l" + mode.title(), () -> {
                session.setMode(mode);
                new LeagueMenu(plugin, viewer, session, TeamSide.RED).open();
            }, lore, "", "&eВыбрать режим");
        }

        private void cancel() {
            plugin.matchSetup().close(session);
            MainHubMenu.open(plugin, viewer);
        }
    }

    private static final class LeagueMenu extends AbstractMenu {
        private final MatchSetupSession session;
        private final TeamSide side;

        private LeagueMenu(SoccerPlugin plugin, Player viewer, MatchSetupSession session, TeamSide side) {
            super(plugin, viewer, side == TeamSide.RED
                    ? "&lКОМАНДА 1 — ЛИГА" : "&lКОМАНДА 2 — ЛИГА", 6);
            this.session = session;
            this.side = side;
        }

        @Override
        protected void render() {
            fillDashboard();
            int slot = 10;
            for (FifaLeague league : plugin.clubCatalog().leagues()) {
                while (slot % 9 == 0 || slot % 9 == 8) {
                    slot++;
                }
                inventory.setItem(slot, Items.named(Material.BOOK,
                        "&b&l" + league.getNameRu(),
                        "&7" + league.getName(),
                        "&fКлубов: &b" + plugin.clubCatalog().clubsInLeague(league).size(),
                        "",
                        "&eОткрыть"));
                registerClick(slot, () -> new ClubMenu(plugin, viewer, session, side, league.getCode()).open());
                if (++slot > 43) {
                    break;
                }
            }
            backButton(49, () -> {
                if (side == TeamSide.RED) {
                    new ModeMenu(plugin, viewer, session).open();
                } else {
                    new LeagueMenu(plugin, viewer, session, TeamSide.RED).open();
                }
            });
        }
    }

    private static final class ClubMenu extends AbstractMenu {
        private final MatchSetupSession session;
        private final TeamSide side;
        private final String leagueCode;

        private ClubMenu(SoccerPlugin plugin, Player viewer, MatchSetupSession session,
                         TeamSide side, String leagueCode) {
            super(plugin, viewer, side == TeamSide.RED
                    ? "&lКОМАНДА 1 — КЛУБ" : "&lКОМАНДА 2 — КЛУБ", 6);
            this.session = session;
            this.side = side;
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
                        "&7Цвет формы: &f" + club.getPrimaryColor(),
                        "",
                        "&eВыбрать клуб"));
                registerClick(slot, () -> select(club));
                if (++slot > 43) {
                    break;
                }
            }
            backButton(49, () -> new LeagueMenu(plugin, viewer, session, side).open());
        }

        private void select(FifaClub club) {
            if (side == TeamSide.RED) {
                session.setRedClubCode(club.getCode());
                new LeagueMenu(plugin, viewer, session, TeamSide.BLUE).open();
            } else {
                session.setBlueClubCode(club.getCode());
                openLobby(plugin, viewer, session);
            }
        }
    }

    private static final class LobbyMenu extends AbstractMenu {
        private final MatchSetupSession session;

        private LobbyMenu(SoccerPlugin plugin, Player viewer, MatchSetupSession session) {
            super(plugin, viewer, "&lСОСТАВЫ И ПОЗИЦИИ", 6);
            this.session = session;
        }

        @Override
        protected void render() {
            fillDashboard();
            FifaClub red = plugin.clubCatalog().get(session.getRedClubCode());
            FifaClub blue = plugin.clubCatalog().get(session.getBlueClubCode());
            if (red == null || blue == null) {
                viewer.sendMessage(Text.color("&cКлубы матча больше недоступны."));
                plugin.matchSetup().close(session);
                MainHubMenu.open(plugin, viewer);
                return;
            }

            boolean owner = session.isOwner(viewer.getUniqueId());
            inventory.setItem(0, Items.named(Material.RED_BANNER,
                    "&c&l" + red.getNameRu(),
                    "&fИгроков на поле: &c" + session.getRedSize(),
                    owner ? "&eНажмите, чтобы увеличить (1–11)" : "&7Выбирает создатель"));
            inventory.setItem(8, Items.named(Material.BLUE_BANNER,
                    "&9&l" + blue.getNameRu(),
                    "&fИгроков на поле: &9" + session.getBlueSize(),
                    owner ? "&eНажмите, чтобы увеличить (1–11)" : "&7Выбирает создатель"));
            if (owner) {
                registerClick(0, () -> changeSize(TeamSide.RED));
                registerClick(8, () -> changeSize(TeamSide.BLUE));
            }

            renderTeam(red, TeamSide.RED, RED_SLOTS, "&c");
            renderTeam(blue, TeamSide.BLUE, BLUE_SLOTS, "&9");

            if (owner && session.getMode() != MatchSetupSession.Mode.BOTS_VS_BOTS) {
                uiButton(46, Material.NAME_TAG, "&a&lПРИГЛАСИТЬ",
                        () -> new InviteMenu(plugin, viewer, session).open(),
                        "&7Выберите игроков онлайн.",
                        "&7Приглашение не обязательно.");
            }
            if (owner) {
                uiButton(49, Material.COMPARATOR, "&e&lПАРАМЕТРЫ",
                        () -> new OptionsMenu(plugin, viewer, session).open(),
                        "&7Арена, погода, время, длительность и сложность.");
                uiButton(52, Material.EMERALD, "&a&lНАЧАТЬ МАТЧ",
                        () -> launch(plugin, viewer, session),
                        "&7Незаполненные места в смешанном режиме",
                        "&7автоматически займут игроки выбранных клубов.");
            }
            backButton(45, () -> leaveLobby(plugin, viewer, session));
        }

        private void renderTeam(FifaClub club, TeamSide side, int[] inventorySlots, String color) {
            List<FifaPlayer> lineup = club.startingXi();
            int size = session.size(side);
            for (int i = 0; i < size && i < inventorySlots.length && i < lineup.size(); i++) {
                FifaPlayer footballer = lineup.get(i);
                UUID occupantId = session.occupant(side, i);
                Player occupant = occupantId != null ? Bukkit.getPlayer(occupantId) : null;
                String assignment;
                if (session.getMode() == MatchSetupSession.Mode.BOTS_VS_BOTS) {
                    assignment = "&7Бот: &f" + footballer.getName();
                } else if (occupantId != null) {
                    assignment = "&aИгрок: &f" + (occupant != null ? occupant.getName() : occupantId.toString());
                } else if (session.getMode() == MatchSetupSession.Mode.MIXED) {
                    assignment = "&eСвободно — будет бот";
                } else {
                    assignment = "&eСвободная позиция";
                }
                int index = i;
                inventory.setItem(inventorySlots[i], Items.named(
                        footballer.getPosition().name().equals("GK")
                                ? Material.GOLDEN_CHESTPLATE : Material.PLAYER_HEAD,
                        color + "&l" + positionName(footballer) + " · " + footballer.getName(),
                        assignment,
                        session.getMode() == MatchSetupSession.Mode.BOTS_VS_BOTS
                                ? "&7Состав управляется ИИ" : "&bНажмите, чтобы занять позицию"));
                if (session.getMode() != MatchSetupSession.Mode.BOTS_VS_BOTS) {
                    registerClick(inventorySlots[i], () -> {
                        if (!session.claim(viewer.getUniqueId(), side, index)) {
                            viewer.sendMessage(Text.color("&cЭта позиция уже занята."));
                            return;
                        }
                        plugin.matchSetup().refreshLobby(session);
                    });
                }
            }
        }

        private void changeSize(TeamSide side) {
            int next = session.size(side) >= 11 ? 1 : session.size(side) + 1;
            if (side == TeamSide.RED) {
                session.setRedSize(next);
            } else {
                session.setBlueSize(next);
            }
            plugin.matchSetup().refreshLobby(session);
        }
    }

    private static final class InviteMenu extends AbstractMenu {
        private final MatchSetupSession session;

        private InviteMenu(SoccerPlugin plugin, Player viewer, MatchSetupSession session) {
            super(plugin, viewer, "&lПРИГЛАСИТЬ ИГРОКОВ", 6);
            this.session = session;
        }

        @Override
        protected void render() {
            fillDashboard();
            int slot = 10;
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getUniqueId().equals(viewer.getUniqueId())
                        || session.getAccepted().contains(target.getUniqueId())
                        || plugin.matchSetup().getFor(target.getUniqueId()) != null
                        || plugin.games().getGame(target) != null) {
                    continue;
                }
                while (slot % 9 == 0 || slot % 9 == 8) {
                    slot++;
                }
                boolean invited = session.getInvited().contains(target.getUniqueId());
                inventory.setItem(slot, Items.named(Material.PLAYER_HEAD,
                        "&f&l" + target.getName(),
                        invited ? "&eПриглашение уже отправлено" : "&aНажмите, чтобы пригласить"));
                if (!invited) {
                    registerClick(slot, () -> {
                        if (plugin.matchSetup().invite(session, target)) {
                            viewer.sendMessage(Text.color("&aПриглашение отправлено игроку &f" + target.getName()));
                        }
                        new InviteMenu(plugin, viewer, session).open();
                    });
                }
                if (++slot > 43) {
                    break;
                }
            }
            backButton(49, () -> openLobby(plugin, viewer, session));
        }
    }

    private static final class OptionsMenu extends AbstractMenu {
        private final MatchSetupSession session;

        private OptionsMenu(SoccerPlugin plugin, Player viewer, MatchSetupSession session) {
            super(plugin, viewer, "&lПАРАМЕТРЫ МАТЧА", 6);
            this.session = session;
        }

        @Override
        protected void render() {
            fillDashboard();
            uiButton(10, Material.GRASS_BLOCK, "&f&lАРЕНА",
                    () -> new ArenaMenu(plugin, viewer, session).open(),
                    session.getArenaName() == null ? "&cНе выбрана" : "&a" + session.getArenaName(),
                    "&eНажмите, чтобы выбрать");
            uiButton(12, Material.WATER_BUCKET, "&b&lПОГОДА: &f" + session.getWeather().title(), () -> {
                session.setWeather(session.getWeather().next());
                new OptionsMenu(plugin, viewer, session).open();
            }, "&7Погода применяется только участникам матча.");
            uiButton(14, Material.CLOCK, "&6&lВРЕМЯ: &f" + session.getTimeOfDay().title(), () -> {
                session.setTimeOfDay(session.getTimeOfDay().next());
                new OptionsMenu(plugin, viewer, session).open();
            }, "&7Личное время суток участников.");
            uiButton(16, Material.RECOVERY_COMPASS, "&e&lДЛИТЕЛЬНОСТЬ: &f" + session.getDurationSeconds() / 60 + " мин.", () -> {
                session.setDurationSeconds(nextDuration(session.getDurationSeconds()));
                new OptionsMenu(plugin, viewer, session).open();
            }, "&7Два тайма.");

            uiButton(20, Material.IRON_SWORD, "&c&lСЛОЖНОСТЬ 1: &f" + difficultyName(session.getRedDifficulty()), () -> {
                session.setRedDifficulty(session.getRedDifficulty().next());
                new OptionsMenu(plugin, viewer, session).open();
            }, "&7Влияет на решения и технику ботов.");
            uiButton(24, Material.DIAMOND_SWORD, "&9&lСЛОЖНОСТЬ 2: &f" + difficultyName(session.getBlueDifficulty()), () -> {
                session.setBlueDifficulty(session.getBlueDifficulty().next());
                new OptionsMenu(plugin, viewer, session).open();
            }, "&7Можно настроить команды по-разному.");
            backButton(49, () -> openLobby(plugin, viewer, session));
        }
    }

    private static final class ArenaMenu extends AbstractMenu {
        private final MatchSetupSession session;

        private ArenaMenu(SoccerPlugin plugin, Player viewer, MatchSetupSession session) {
            super(plugin, viewer, "&lВЫБОР АРЕНЫ", 6);
            this.session = session;
        }

        @Override
        protected void render() {
            fillDashboard();
            int slot = 10;
            for (Arena arena : plugin.arenas().getArenas()) {
                if (!arena.isPlayable()) {
                    continue;
                }
                while (slot % 9 == 0 || slot % 9 == 8) {
                    slot++;
                }
                boolean occupied = plugin.games().getGame(arena) != null;
                inventory.setItem(slot, Items.named(occupied ? Material.BARRIER : Material.GRASS_BLOCK,
                        "&f&l" + arena.getName(),
                        occupied ? "&cНа этой арене уже идёт матч" : "&aАрена свободна",
                        arena.getName().equalsIgnoreCase(session.getArenaName()) ? "&eВыбрано" : "&7Нажмите для выбора"));
                if (!occupied) {
                    registerClick(slot, () -> {
                        session.setArenaName(arena.getName());
                        new OptionsMenu(plugin, viewer, session).open();
                    });
                }
                if (++slot > 43) {
                    break;
                }
            }
            backButton(49, () -> new OptionsMenu(plugin, viewer, session).open());
        }
    }

    private static void launch(SoccerPlugin plugin, Player owner, MatchSetupSession session) {
        if (!session.isOwner(owner.getUniqueId())) {
            return;
        }
        if (!session.readyToLaunch()) {
            owner.sendMessage(Text.color("&cСначала выберите свободную арену в параметрах."));
            return;
        }
        if (session.getMode() == MatchSetupSession.Mode.MIXED && session.getClaims().isEmpty()) {
            owner.sendMessage(Text.color("&cВ смешанном режиме хотя бы один игрок должен занять позицию."));
            return;
        }
        if (session.getMode() == MatchSetupSession.Mode.PLAYERS_VS_PLAYERS
                && (claimedOn(session, TeamSide.RED) == 0 || claimedOn(session, TeamSide.BLUE) == 0)) {
            owner.sendMessage(Text.color("&cВ режиме игроков в каждой команде нужен хотя бы один участник."));
            return;
        }
        if (plugin.games().startConfiguredMatch(session) == null) {
            owner.sendMessage(Text.color("&cНе удалось начать матч: проверьте арену и участников."));
            openLobby(plugin, owner, session);
            return;
        }
        plugin.matchSetup().close(session);
    }

    private static int claimedOn(MatchSetupSession session, TeamSide side) {
        int count = 0;
        for (MatchSetupSession.Claim claim : session.getClaims().values()) {
            if (claim.team() == side) {
                count++;
            }
        }
        return count;
    }

    private static void leaveLobby(SoccerPlugin plugin, Player viewer, MatchSetupSession session) {
        plugin.matchSetup().leave(viewer.getUniqueId());
        MainHubMenu.open(plugin, viewer);
    }

    private static String positionName(FifaPlayer player) {
        return switch (player.getPosition()) {
            case GK -> "ВРТ";
            case DF -> "ЗАЩ";
            case MF -> "ПЗЩ";
            case FW -> "НАП";
        };
    }

    private static String difficultyName(BotDifficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "Лёгкая";
            case MEDIUM -> "Средняя";
            case HARD -> "Сложная";
        };
    }

    private static int nextDuration(int current) {
        for (int value : DURATION_CHOICES) {
            if (value > current) {
                return value;
            }
        }
        return DURATION_CHOICES[0];
    }
}
