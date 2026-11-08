package com.ballgame.soccer.command;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.arena.TouchlineSegment;
import com.ballgame.soccer.ball.BallOrphanCleanup;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.championship.ChampionshipType;
import com.ballgame.soccer.championship.ChampFixture;
import com.ballgame.soccer.championship.ChampTeam;
import com.ballgame.soccer.crowd.CrowdPackMode;
import com.ballgame.soccer.game.BotDifficulty;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.stats.ChatLeaderboard;
import com.ballgame.soccer.stats.LeaderboardCategory;
import com.ballgame.soccer.stats.PlayerStats;
import com.ballgame.soccer.gui.ChampFixtureArenaMenu;
import com.ballgame.soccer.gui.GamesMenu;
import com.ballgame.soccer.util.Cuboid;
import com.ballgame.soccer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Single-entry command dispatcher for {@code /soccer}. Handles both player-facing
 * commands (join/leave/stats) and admin arena setup, plus tab completion.
 */
public final class SoccerCommand implements CommandExecutor, TabCompleter {

    private final SoccerPlugin plugin;
    private final Map<UUID, Location[]> selections = new HashMap<>();

    private static final List<String> PLAYER_SUBS = Arrays.asList(
            "join", "quickjoin", "leave", "list", "stats", "games", "top", "leaderboard", "cup", "championship", "crowdpack", "help");
    private static final List<String> ADMIN_SUBS = Arrays.asList(
            "create", "delete", "pos1", "pos2", "setfield", "setredgoal", "setbluegoal",
            "setballspawn", "setredspawn", "setbluespawn", "setlobby", "setspectator",
            "setredpenalty", "setbluepenalty",
            "enable", "disable", "start", "botmatch", "stop", "penalty", "reload", "cleanup", "resetstats",
            "addoutline", "clearoutlines", "listoutlines", "crowdpack",
            "cupcreate", "cupdelete", "cupstart", "cuprename", "teamrename", "cupbotmatch", "stadium", "cupfixturearena");

    public SoccerCommand(SoccerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join":        return join(sender, args);
            case "quickjoin":   return quickJoin(sender, args);
            case "leave":       return leave(sender);
            case "list":        return list(sender);
            case "stats":       return stats(sender, args);
            case "games":       return openGamesGui(sender);
            case "top":         return top(sender, args);
            case "leaderboard": return leaderboard(sender, args);
            case "cup":
            case "championship": return openChampionshipGui(sender);
            case "crowdpack":   return crowdPack(sender, args);
            case "help":        help(sender); return true;

            case "cupcreate":   return cupCreate(sender, args);
            case "cupdelete":   return cupDelete(sender, args);
            case "cupstart":    return cupStart(sender, args);
            case "cuprename":   return cupRename(sender, args);
            case "teamrename":  return teamRename(sender, args);
            case "cupbotmatch": return cupBotMatch(sender, args);
            case "stadium":     return stadium(sender, args);
            case "cupfixturearena": return cupFixtureArena(sender, args);

            // --- admin ---
            case "create":      return adminCreate(sender, args);
            case "delete":      return adminDelete(sender, args);
            case "pos1":        return setPos(sender, 0);
            case "pos2":        return setPos(sender, 1);
            case "setfield":    return setRegion(sender, args, RegionTarget.FIELD);
            case "setredgoal":  return setRegion(sender, args, RegionTarget.RED_GOAL);
            case "setbluegoal": return setRegion(sender, args, RegionTarget.BLUE_GOAL);
            case "setballspawn":return setPoint(sender, args, PointTarget.BALL);
            case "setredspawn": return setPoint(sender, args, PointTarget.RED);
            case "setbluespawn":return setPoint(sender, args, PointTarget.BLUE);
            case "setlobby":    return setPoint(sender, args, PointTarget.LOBBY);
            case "setspectator":return setPoint(sender, args, PointTarget.SPECTATOR);
            case "setredpenalty": return setPoint(sender, args, PointTarget.RED_PENALTY);
            case "setbluepenalty": return setPoint(sender, args, PointTarget.BLUE_PENALTY);
            case "enable":      return toggle(sender, args, true);
            case "disable":     return toggle(sender, args, false);
            case "start":       return adminStart(sender, args);
            case "botmatch":    return botMatch(sender, args);
            case "stop":        return adminStop(sender, args);
            case "penalty":     return adminPenalty(sender, args);
            case "reload":      return reload(sender);
            case "cleanup":     return cleanup(sender, args);
            case "resetstats":  return resetStats(sender, args);
            case "addoutline":  return addOutLine(sender, args);
            case "clearoutlines": return clearOutLines(sender, args);
            case "listoutlines": return listOutLines(sender, args);
            default:
                help(sender);
                return true;
        }
    }

    // ==================================================================
    // Player commands
    // ==================================================================

    private boolean join(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "soccer.play");
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            plugin.messages().sendRaw(player, "&cUsage: /soccer join <arena> [referee]");
            return true;
        }
        Arena arena = plugin.arenas().get(args[1]);
        if (arena == null) {
            if (plugin.network().redirectJoin(player, args[1], null)) {
                return true;
            }
            plugin.messages().send(player, "arena-not-found", "arena", args[1]);
            return true;
        }
        if (args.length >= 3 && isRefereeArg(args[2])) {
            plugin.games().joinAsReferee(player, arena);
        } else {
            plugin.games().join(player, arena);
        }
        return true;
    }

    private static boolean isRefereeArg(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.equals("referee") || lower.equals("ref") || lower.equals("судья");
    }

    private boolean quickJoin(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "soccer.play");
        if (player == null) {
            return true;
        }
        TeamSide team = null;
        if (args.length >= 2) {
            team = parseTeamSide(args[1]);
            if (team == null) {
                plugin.messages().sendRaw(player, "&cUsage: /soccer quickjoin [red|blue]");
                return true;
            }
        }
        plugin.games().quickJoin(player, team);
        return true;
    }

    private TeamSide parseTeamSide(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "red", "r" -> TeamSide.RED;
            case "blue", "b" -> TeamSide.BLUE;
            default -> null;
        };
    }

    private boolean leave(CommandSender sender) {
        Player player = requirePlayer(sender, "soccer.play");
        if (player == null) {
            return true;
        }
        plugin.games().leave(player);
        return true;
    }

    private boolean list(CommandSender sender) {
        plugin.messages().sendRaw(sender, "&a&lArenas:");
        if (plugin.arenas().getArenas().isEmpty()) {
            plugin.messages().sendRaw(sender, "&7  (none yet - create one with /soccer create)");
            return true;
        }
        for (Arena arena : plugin.arenas().getArenas()) {
            Game game = null;
            for (Game g : plugin.games().getActiveGames()) {
                if (g.getArena() == arena) {
                    game = g;
                    break;
                }
            }
            String status;
            if (!arena.isSetupComplete()) {
                status = "&c(incomplete)";
            } else if (!arena.isEnabled()) {
                status = "&8(disabled)";
            } else if (game != null) {
                status = "&e" + game.getState().display() + " &7" + game.playerCount() + "p";
            } else {
                status = "&aready";
            }
            sender.sendMessage(Text.color("&7- &f" + arena.getName() + " " + status));
        }
        return true;
    }

    private boolean stats(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length >= 2) {
            target = Bukkit.getOfflinePlayer(args[1]);
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer stats <player>");
            return true;
        }
        PlayerStats s = plugin.stats().peek(target.getUniqueId());
        if (s == null) {
            plugin.messages().sendRaw(sender, "&7No stats recorded for &f"
                    + target.getName() + "&7 yet.");
            return true;
        }
        plugin.messages().sendRaw(sender, "&a&lStats - &f" + s.getName());
        plugin.messages().sendRaw(sender, "&7Games: &f" + s.getGamesPlayed()
                + " &7| W/L/D: &a" + s.getWins() + "&7/&c" + s.getLosses()
                + "&7/&e" + s.getDraws());
        plugin.messages().sendRaw(sender, "&7Win rate: &f"
                + String.format(Locale.US, "%.1f%%", s.getWinRate()));
        plugin.messages().sendRaw(sender, "&7Goals: &f" + s.getGoals()
                + " &7| Assists: &f" + s.getAssists()
                + " &7| Own goals: &f" + s.getOwnGoals());
        plugin.messages().sendRaw(sender, "&7Tackles: &f" + s.getTackles()
                + " &7| Saves: &f" + s.getSaves()
                + " &7| Clean sheets: &f" + s.getCleanSheets());
        return true;
    }

    private boolean top(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            ChatLeaderboard.sendOverview(sender, plugin, plugin.settings().leaderboardOverviewEntries);
            return true;
        }
        LeaderboardCategory category = args.length >= 2
                ? LeaderboardCategory.parse(args[1])
                : LeaderboardCategory.GOALS;
        ChatLeaderboard.sendCategory(sender, plugin, category, plugin.settings().leaderboardEntries);
        return true;
    }

    private boolean leaderboard(CommandSender sender, String[] args) {
        if (args.length <= 1 || args[1].equalsIgnoreCase("all")) {
            ChatLeaderboard.sendOverview(sender, plugin, plugin.settings().leaderboardOverviewEntries);
            return true;
        }
        LeaderboardCategory category = LeaderboardCategory.parse(args[1]);
        ChatLeaderboard.sendCategory(sender, plugin, category, plugin.settings().leaderboardEntries);
        return true;
    }

    private boolean resetStats(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "stats-reset-usage");
            return true;
        }
        if (args[1].equalsIgnoreCase("all")) {
            int count = plugin.stats().resetAll();
            plugin.messages().send(sender, "stats-reset-all", "count", String.valueOf(count));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!plugin.stats().reset(target.getUniqueId())) {
            plugin.messages().send(sender, "stats-reset-not-found", "player", target.getName());
            return true;
        }
        plugin.messages().send(sender, "stats-reset-player", "player", target.getName());
        return true;
    }

    private boolean addOutLine(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "soccer.admin");
        if (player == null) {
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        Location[] sel = selections.get(player.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) {
            plugin.messages().send(sender, "outline-need-positions");
            return true;
        }
        if (!arena.addOutLine(sel[0], sel[1])) {
            plugin.messages().send(sender, "outline-add-failed");
            return true;
        }
        plugin.arenas().save();
        plugin.messages().send(sender, "outline-added", "arena", arena.getName(),
                "count", String.valueOf(arena.getOutLines().size()));
        return true;
    }

    private boolean clearOutLines(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        int count = arena.getOutLines().size();
        arena.clearOutLines();
        plugin.arenas().save();
        plugin.messages().send(sender, "outline-cleared", "arena", arena.getName(),
                "count", String.valueOf(count));
        return true;
    }

    private boolean listOutLines(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        int count = arena.getOutLines().size();
        if (count == 0) {
            plugin.messages().send(sender, "outline-list-empty", "arena", arena.getName());
            return true;
        }
        plugin.messages().send(sender, "outline-list-header", "arena", arena.getName(),
                "count", String.valueOf(count));
        int index = 1;
        for (TouchlineSegment segment : arena.getOutLines()) {
            Location from = segment.from();
            Location to = segment.to();
            plugin.messages().sendRaw(sender, "&7  #" + index++ + " &f("
                    + formatCoord(from.getX()) + ", " + formatCoord(from.getZ()) + ") -> ("
                    + formatCoord(to.getX()) + ", " + formatCoord(to.getZ()) + ")");
        }
        return true;
    }

    private static String formatCoord(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    // ==================================================================
    // Admin commands
    // ==================================================================

    private boolean adminCreate(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer create <arena>");
            return true;
        }
        if (plugin.arenas().exists(args[1])) {
            plugin.messages().sendRaw(sender, "&cAn arena with that name already exists.");
            return true;
        }
        plugin.arenas().create(args[1]);
        plugin.arenas().save();
        plugin.messages().sendRaw(sender, "&aCreated arena &f" + args[1]
                + "&a. Now set its regions and spawns.");
        sendSetupGuide(sender, args[1]);
        return true;
    }

    private boolean adminDelete(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer delete <arena>");
            return true;
        }
        if (plugin.arenas().delete(args[1])) {
            plugin.arenas().save();
            plugin.messages().sendRaw(sender, "&aDeleted arena &f" + args[1]);
        } else {
            plugin.messages().send(sender, "arena-not-found", "arena", args[1]);
        }
        return true;
    }

    private boolean setPos(CommandSender sender, int index) {
        Player player = requirePlayer(sender, "soccer.admin");
        if (player == null) {
            return true;
        }
        Location[] sel = selections.computeIfAbsent(player.getUniqueId(), k -> new Location[2]);
        sel[index] = player.getLocation().getBlock().getLocation();
        plugin.messages().sendRaw(player, "&aPosition " + (index + 1) + " set to your block.");
        return true;
    }

    private enum RegionTarget { FIELD, RED_GOAL, BLUE_GOAL }
    private enum PointTarget { BALL, RED, BLUE, LOBBY, SPECTATOR, RED_PENALTY, BLUE_PENALTY }

    private boolean setRegion(CommandSender sender, String[] args, RegionTarget target) {
        Player player = requirePlayer(sender, "soccer.admin");
        if (player == null) {
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        Location[] sel = selections.get(player.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) {
            plugin.messages().sendRaw(player, "&cSet both corners first with /soccer pos1 and /soccer pos2.");
            return true;
        }
        Cuboid cuboid = new Cuboid(sel[0], sel[1]);
        if (target == RegionTarget.RED_GOAL || target == RegionTarget.BLUE_GOAL) {
            cuboid = expandGoalRegion(sel[0], sel[1]);
        }
        switch (target) {
            case FIELD: arena.setField(cuboid); break;
            case RED_GOAL: arena.setRedGoal(cuboid); break;
            case BLUE_GOAL: arena.setBlueGoal(cuboid); break;
        }
        plugin.arenas().save();
        plugin.messages().sendRaw(player, "&aSet " + target.name().toLowerCase(Locale.ROOT)
                + " for &f" + arena.getName() + "&a.");
        reportCompletion(player, arena);
        return true;
    }

    private Cuboid expandGoalRegion(Location a, Location b) {
        double minX = Math.min(a.getX(), b.getX());
        double maxX = Math.max(a.getX(), b.getX());
        double minY = Math.min(a.getY(), b.getY());
        double minZ = Math.min(a.getZ(), b.getZ());
        double maxZ = Math.max(a.getZ(), b.getZ());
        double maxY = minY + 3.0;
        return new Cuboid(
                new Location(a.getWorld(), minX, minY, minZ),
                new Location(a.getWorld(), maxX, maxY, maxZ));
    }

    private boolean setPoint(CommandSender sender, String[] args, PointTarget target) {
        Player player = requirePlayer(sender, "soccer.admin");
        if (player == null) {
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        Location loc = player.getLocation();
        switch (target) {
            case BALL: arena.setBallSpawn(loc); break;
            case RED: arena.setRedSpawn(loc); break;
            case BLUE: arena.setBlueSpawn(loc); break;
            case LOBBY: arena.setLobby(loc); break;
            case SPECTATOR: arena.setSpectator(loc); break;
            case RED_PENALTY: arena.setRedPenaltySpot(loc); break;
            case BLUE_PENALTY: arena.setBluePenaltySpot(loc); break;
        }
        plugin.arenas().save();
        String label = switch (target) {
            case RED_PENALTY -> "red penalty spot";
            case BLUE_PENALTY -> "blue penalty spot";
            default -> target.name().toLowerCase(Locale.ROOT) + " spawn";
        };
        plugin.messages().sendRaw(player, "&aSet " + label + " for &f" + arena.getName() + "&a.");
        reportCompletion(player, arena);
        return true;
    }

    private boolean toggle(CommandSender sender, String[] args, boolean enabled) {
        if (notAdmin(sender)) {
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        arena.setEnabled(enabled);
        plugin.arenas().save();
        plugin.messages().sendRaw(sender, "&aArena &f" + arena.getName() + "&a is now "
                + (enabled ? "&aenabled" : "&cdisabled") + "&a.");
        return true;
    }

    private boolean adminStart(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        Game game = null;
        for (Game g : plugin.games().getActiveGames()) {
            if (g.getArena() == arena) {
                game = g;
                break;
            }
        }
        if (game == null) {
            plugin.messages().sendRaw(sender, "&cThere is no waiting match in that arena.");
            return true;
        }
        game.startCountdown();
        plugin.messages().sendRaw(sender, "&aForced countdown for &f" + arena.getName());
        return true;
    }

    /** {@code /soccer botmatch <arena> [red-level] [blue-level]} — watch the AI play itself. */
    private boolean botMatch(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer botmatch <arena> [easy|medium|hard] [easy|medium|hard]");
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        BotDifficulty red = args.length >= 3 ? parseDifficulty(args[2]) : BotDifficulty.MEDIUM;
        BotDifficulty blue = args.length >= 4 ? parseDifficulty(args[3]) : red;
        if (red == null || blue == null) {
            plugin.messages().sendRaw(sender, "&cLevel must be easy, medium or hard.");
            return true;
        }
        Game game = plugin.games().startExhibitionBotMatch(arena, red, blue);
        if (game == null) {
            plugin.messages().sendRaw(sender, "&cCould not start: arena busy or not ready, or bots are disabled.");
            return true;
        }
        plugin.messages().sendRaw(sender, "&aBot match on &f" + arena.getName() + "&a: &c"
                + red.name().toLowerCase(Locale.ROOT) + " &7vs &9" + blue.name().toLowerCase(Locale.ROOT)
                + "&a. Use &f/soccer stadium " + arena.getName() + " &ato bring everyone to watch.");
        return true;
    }

    private static BotDifficulty parseDifficulty(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "easy", "e", "лёгкий", "легкий" -> BotDifficulty.EASY;
            case "medium", "m", "normal", "средний" -> BotDifficulty.MEDIUM;
            case "hard", "h", "сложный" -> BotDifficulty.HARD;
            default -> null;
        };
    }

    private boolean adminStop(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        for (Game g : plugin.games().getGamesSnapshot()) {
            if (g.getArena() == arena) {
                g.forceEnd();
                plugin.messages().sendRaw(sender, "&aStopped match in &f" + arena.getName());
                return true;
            }
        }
        plugin.messages().sendRaw(sender, "&cNo active match in that arena.");
        return true;
    }

    private boolean adminPenalty(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        Arena arena = requireArena(sender, args);
        if (arena == null) {
            return true;
        }
        for (Game g : plugin.games().getGamesSnapshot()) {
            if (g.getArena() == arena) {
                if (g.forcePenaltyShootout()) {
                    plugin.messages().send(sender, "admin-penalty-started", "arena", arena.getName());
                } else {
                    plugin.messages().send(sender, "admin-penalty-failed", "arena", arena.getName());
                }
                return true;
            }
        }
        plugin.messages().send(sender, "admin-penalty-no-match", "arena", arena.getName());
        return true;
    }

    private boolean crowdPack(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().sendRaw(sender, "&cPlayers only.");
            return true;
        }

        if (args.length >= 2 && "accept".equalsIgnoreCase(args[1])) {
            if (!plugin.settings().crowdRealSounds) {
                plugin.messages().send(player, "crowd-pack-real-sounds-off");
                return true;
            }
            if (!plugin.crowdPack().isReady()) {
                plugin.messages().send(player, "crowd-pack-unavailable");
                return true;
            }
            plugin.crowdPack().markAccepted(player);
            plugin.messages().send(player, "crowd-pack-accepted");
            return true;
        }

        if (!plugin.crowdPack().isReady()) {
            plugin.messages().send(player, "crowd-pack-unavailable");
            return true;
        }

        switch (plugin.crowdPack().mode()) {
            case MANUAL -> {
                File install = plugin.crowdPack().installFile();
                plugin.messages().send(player, "crowd-pack-manual-path", "path", install.getAbsolutePath());
                plugin.messages().send(player, "crowd-pack-manual-steps");
                plugin.messages().send(player, "crowd-pack-manual-accept-hint");
            }
            case DOWNLOAD, URL -> {
                String url = plugin.crowdPack().sampleDownloadUrl(player);
                plugin.messages().send(player, "crowd-pack-url", "url", url != null ? url : "n/a");
                plugin.messages().send(player, "crowd-pack-hint");
            }
        }

        if (sender.hasPermission("soccer.admin")) {
            plugin.messages().sendRaw(sender, "&7Mode: &f" + plugin.crowdPack().mode().name().toLowerCase());
            if (plugin.crowdPack().mode() != CrowdPackMode.MANUAL) {
                plugin.messages().sendRaw(sender, "&7Port: &f" + plugin.crowdPack().activePort());
                plugin.messages().sendRaw(sender, "&7Host candidates: &f"
                        + String.join(", ", plugin.crowdPack().hostCandidates(player)));
            }
        }
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (notAdmin(sender)) {
            return true;
        }
        plugin.reloadAll();
        plugin.arenas().load();
        int removed = BallOrphanCleanup.cleanupAllArenas(plugin);
        plugin.messages().send(sender, "reload-success");
        int halves = Math.max(1, plugin.settings().halves);
        int halfSeconds = Math.max(10, plugin.settings().matchDuration / halves);
        plugin.messages().sendRaw(sender, "&7Match: &f" + (halfSeconds * halves) + "s &7("
                + halves + " x " + halfSeconds + "s). &7File: &f"
                + new java.io.File(plugin.getDataFolder(), "config.yml").getAbsolutePath());
        if (removed > 0) {
            plugin.messages().sendRaw(sender, "&aRemoved &f" + removed + " &aorphaned ball(s) from arenas.");
        }
        return true;
    }

    private boolean cleanup(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        int removed;
        if (args.length >= 2) {
            Arena arena = plugin.arenas().get(args[1]);
            if (arena == null) {
                plugin.messages().send(sender, "arena-not-found", "arena", args[1]);
                return true;
            }
            removed = BallOrphanCleanup.cleanupArena(plugin, arena);
            plugin.messages().sendRaw(sender, "&aRemoved &f" + removed
                    + " &aorphaned ball(s) from arena &f" + arena.getName() + "&a.");
        } else {
            removed = BallOrphanCleanup.cleanupAllArenas(plugin);
            plugin.messages().sendRaw(sender, "&aRemoved &f" + removed + " &aorphaned ball(s) from all arenas.");
        }
        return true;
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private void reportCompletion(Player player, Arena arena) {
        if (arena.isSetupComplete()) {
            plugin.messages().sendRaw(player, "&a&l" + arena.getName()
                    + " is fully set up and ready to play!");
        }
    }

    private void sendSetupGuide(CommandSender sender, String arena) {
        plugin.messages().sendRaw(sender, "&7Setup steps for &f" + arena + "&7:");
        plugin.messages().sendRaw(sender, "&8 1. &7/soccer pos1 & pos2 then &f/soccer setfield " + arena);
        plugin.messages().sendRaw(sender, "&8 2. &7pos1/pos2 + &f/soccer setredgoal " + arena);
        plugin.messages().sendRaw(sender, "&8 3. &7pos1/pos2 + &f/soccer setbluegoal " + arena);
        plugin.messages().sendRaw(sender, "&8 4. &f/soccer setballspawn " + arena + " &7(stand at center)");
        plugin.messages().sendRaw(sender, "&8 5. &f/soccer setredspawn / setbluespawn " + arena);
        plugin.messages().sendRaw(sender, "&8 6. &f/soccer setredpenalty / setbluepenalty " + arena + " &7(stand on mark)");
        plugin.messages().sendRaw(sender, "&8 7. &7pos1/pos2 at each sideline + &f/soccer addoutline " + arena);
        plugin.messages().sendRaw(sender, "&8 8. &f/soccer setlobby " + arena + " &7(optional)");
    }

    private boolean openGamesGui(CommandSender sender) {
        Player player = requirePlayer(sender, "soccer.play");
        if (player == null) {
            return true;
        }
        GamesMenu.open(plugin, player);
        return true;
    }

    private boolean openChampionshipGui(CommandSender sender) {
        Player player = requirePlayer(sender, "soccer.play");
        if (player == null) {
            return true;
        }
        if (!plugin.settings().championshipsEnabled) {
            plugin.messages().send(player, "champ-disabled");
            return true;
        }
        plugin.championships().openMainMenu(player);
        return true;
    }

    private boolean cupCreate(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer cupcreate <id> <league|knockout> [name] [arena]");
            return true;
        }
        String id = args[1];
        ChampionshipType type;
        try {
            type = ChampionshipType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.messages().sendRaw(sender, "&cType must be league or knockout.");
            return true;
        }
        String name = args.length >= 4 ? args[3] : id;
        String arena = args.length >= 5 ? args[4] : "";
        if (!arena.isEmpty() && plugin.arenas().get(arena) == null) {
            plugin.messages().send(sender, "arena-not-found", "arena", arena);
            return true;
        }
        Championship created = plugin.championships().create(id, name, type, arena,
                sender instanceof Player player ? player : null);
        if (created == null) {
            plugin.messages().send(sender, "champ-create-failed");
        } else {
            plugin.messages().send(sender, "champ-created", "name", created.getDisplayName());
        }
        return true;
    }

    private boolean cupDelete(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer cupdelete <id>");
            return true;
        }
        if (plugin.championships().delete(args[1])) {
            plugin.messages().send(sender, "champ-deleted", "id", args[1]);
        } else {
            plugin.messages().send(sender, "champ-not-found");
        }
        return true;
    }

    private boolean cupStart(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer cupstart <id>");
            return true;
        }
        if (plugin.championships().tryStartChampionship(
                sender instanceof Player player ? player : null, args[1])) {
            if (!(sender instanceof Player)) {
                plugin.messages().send(sender, "champ-started-admin", "id", args[1]);
            }
        } else if (sender instanceof Player) {
            // tryStartChampionship already sent the reason
        } else {
            plugin.messages().send(sender, "champ-start-failed");
        }
        return true;
    }

    private boolean cupBotMatch(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "soccer.cup");
        if (player == null) {
            return true;
        }
        if (args.length < 3) {
            plugin.messages().sendRaw(player, "&cUsage: /soccer cupbotmatch <cupId> <fixtureId>");
            return true;
        }
        Championship championship = plugin.championships().get(args[1]);
        if (championship == null) {
            plugin.messages().send(player, "champ-not-found");
            return true;
        }
        if (!plugin.championships().canManageChampionship(player, championship)) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        ChampFixture fixture = championship.getFixture(args[2]);
        if (fixture == null) {
            plugin.messages().send(player, "champ-fixture-not-found");
            return true;
        }
        plugin.championships().startBotFixtureMatch(player, championship, fixture);
        return true;
    }

    private boolean cupFixtureArena(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "soccer.cup");
        if (player == null) {
            return true;
        }
        if (args.length < 3) {
            plugin.messages().sendRaw(player, "&cUsage: /soccer cupfixturearena <cupId> <fixtureId> [arena]");
            return true;
        }
        Championship championship = plugin.championships().get(args[1]);
        if (championship == null) {
            plugin.messages().send(player, "champ-not-found");
            return true;
        }
        if (!plugin.championships().canManageChampionship(player, championship)) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        ChampFixture fixture = championship.getFixture(args[2]);
        if (fixture == null) {
            plugin.messages().send(player, "champ-fixture-not-found");
            return true;
        }
        if (args.length >= 4) {
            plugin.championships().setFixtureArena(player, championship, fixture, args[3]);
            return true;
        }
        ChampFixtureArenaMenu.open(plugin, player, championship.getId(), fixture.getId());
        return true;
    }

    private boolean stadium(CommandSender sender, String[] args) {
        if (!canUseStadium(sender)) {
            if (sender instanceof Player player) {
                plugin.messages().send(player, "no-permission");
            } else {
                plugin.messages().send(sender, "no-permission");
            }
            return true;
        }
        String arenaName = args.length >= 2 ? args[1] : resolveDefaultStadiumArena(sender);
        if (arenaName == null || arenaName.isBlank()) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer stadium <arena>");
            return true;
        }
        Arena arena = plugin.arenas().get(arenaName);
        if (arena == null) {
            plugin.messages().send(sender, "arena-not-found", "arena", arenaName);
            return true;
        }
        if (plugin.games().resolveStadiumSpot(arena) == null) {
            plugin.messages().send(sender, "arena-not-ready");
            return true;
        }
        Game watchGame = plugin.games().getGame(arena);
        plugin.games().teleportServerToStadium(arena, watchGame);
        plugin.messages().broadcast(plugin.messages().format("stadium-teleport",
                "arena", arena.getName(),
                "player", sender instanceof Player player ? player.getName() : "Console"));
        return true;
    }

    private boolean canUseStadium(CommandSender sender) {
        if (sender.hasPermission("soccer.admin")) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            return false;
        }
        for (Championship championship : plugin.championships().getAll()) {
            if (plugin.championships().canManageChampionship(player, championship)) {
                return true;
            }
        }
        return false;
    }

    private String resolveDefaultStadiumArena(CommandSender sender) {
        if (sender instanceof Player player) {
            Game game = plugin.games().getGame(player);
            if (game != null && game.getArena() != null) {
                return game.getArena().getName();
            }
            for (Championship championship : plugin.championships().getAll()) {
                if (plugin.championships().canManageChampionship(player, championship)) {
                    String arena = championship.getArenaName();
                    if (arena != null && !arena.isBlank()) {
                        return arena;
                    }
                }
            }
        }
        for (String name : plugin.arenas().getArenaNames()) {
            Arena arena = plugin.arenas().get(name);
            if (arena != null && plugin.games().resolveStadiumSpot(arena) != null) {
                return name;
            }
        }
        return null;
    }

    private boolean cupRename(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer cuprename <id> <name>");
            return true;
        }
        String name = joinArgs(args, 2);
        if (plugin.championships().renameChampionship(args[1], name)) {
            plugin.messages().send(sender, "champ-renamed", "name", name.trim());
        } else {
            plugin.messages().send(sender, "champ-rename-failed");
        }
        return true;
    }

    private boolean teamRename(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "soccer.play");
        if (player == null) {
            return true;
        }
        if (args.length < 3) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer teamrename <cupId> [teamId] <name>");
            return true;
        }
        Championship championship = plugin.championships().get(args[1]);
        if (championship == null) {
            plugin.messages().send(sender, "champ-not-found");
            return true;
        }

        boolean admin = player.hasPermission("soccer.admin");
        String teamId;
        String newName;
        if (admin && args.length >= 4 && championship.getTeam(args[2]) != null) {
            teamId = args[2];
            newName = joinArgs(args, 3);
        } else {
            ChampTeam team = championship.findTeamOf(player.getUniqueId());
            if (team == null || !player.getUniqueId().equals(team.getCaptain())) {
                plugin.messages().send(sender, "champ-team-rename-failed");
                return true;
            }
            teamId = team.getId();
            newName = joinArgs(args, 2);
        }

        if (plugin.championships().renameTeam(championship, teamId, newName, admin)) {
            plugin.messages().send(sender, "champ-team-renamed", "name", newName.trim());
        } else {
            plugin.messages().send(sender, "champ-team-rename-failed");
        }
        return true;
    }

    private void help(CommandSender sender) {
        plugin.messages().sendRaw(sender, "&a&l=== AdvancedSoccer ===");
        plugin.messages().sendRaw(sender, "&f/soccer quickjoin [red|blue] &7- solo or teams matchmaking");
        plugin.messages().sendRaw(sender, "&f/soccer join <arena> [referee] &7- join a specific arena");
        plugin.messages().sendRaw(sender, "&f/soccer leave &7- leave your match");
        plugin.messages().sendRaw(sender, "&f/soccer list &7- list arenas");
        plugin.messages().sendRaw(sender, "&f/soccer stats [player] &7- view stats");
        plugin.messages().sendRaw(sender, "&f/soccer top [category|all] &7- leaderboard column");
        plugin.messages().sendRaw(sender, "&f/soccer leaderboard [category|all] &7- full leaderboards");
        plugin.messages().sendRaw(sender, "&f/leaderboard [category|all] &7- same as above");
        plugin.messages().sendRaw(sender, "&f/soccer cup &7- open championships GUI");
        plugin.messages().sendRaw(sender, "&7Controls: &fLeft-click&7 shoot, &fRight-click&7 pass, "
                + "&fF&7 loft pass (5–7 blocks), &fQ&7 low cross (3 blocks, 15–30 far), "
                + "&fHotbar slot&7 = shot power (&f7&7 = 70%), &fSneak+Sprint&7 tackle.");
        if (sender.hasPermission("soccer.admin")) {
            plugin.messages().sendRaw(sender, "&c/soccer create|delete|setfield|setredgoal|setbluegoal");
            plugin.messages().sendRaw(sender, "&c/soccer setballspawn|setredspawn|setbluespawn|setlobby");
            plugin.messages().sendRaw(sender, "&c/soccer setredpenalty|setbluepenalty <arena>");
            plugin.messages().sendRaw(sender, "&c/soccer pos1|pos2|enable|disable|start|stop|penalty|reload");
            plugin.messages().sendRaw(sender, "&c/soccer botmatch <arena> [easy|medium|hard] [easy|medium|hard] &7- AI vs AI");
            plugin.messages().sendRaw(sender, "&c/soccer resetstats <player|all> &7- reset lifetime stats");
            plugin.messages().sendRaw(sender, "&c/soccer addoutline|clearoutlines|listoutlines <arena>");
            plugin.messages().sendRaw(sender, "&c/soccer cupcreate <id> <league|knockout> [name] [arena]");
            plugin.messages().sendRaw(sender, "&c/soccer cupdelete <id> | cupstart <id>");
            plugin.messages().sendRaw(sender, "&c/soccer cuprename <id> <name> | teamrename <cupId> [teamId] <name>");
            plugin.messages().sendRaw(sender, "&c/soccer cupbotmatch <cupId> <fixtureId> &7- 11v11 bot match");
            plugin.messages().sendRaw(sender, "&c/soccer cupfixturearena <cupId> <fixtureId> [arena] &7- match arena");
            plugin.messages().sendRaw(sender, "&c/soccer stadium [arena] &7- teleport everyone to the stadium");
        } else if (sender instanceof Player player && canUseStadium(sender)) {
            plugin.messages().sendRaw(sender, "&e/soccer cupbotmatch <cupId> <fixtureId> &7- start 11v11 bots");
            plugin.messages().sendRaw(sender, "&e/soccer cupfixturearena <cupId> <fixtureId> [arena] &7- pick match arena");
            plugin.messages().sendRaw(sender, "&e/soccer stadium [arena] &7- teleport everyone to the stadium");
        }
    }

    private Player requirePlayer(CommandSender sender, String permission) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return null;
        }
        if (!player.hasPermission(permission)) {
            plugin.messages().send(player, "no-permission");
            return null;
        }
        return player;
    }

    private boolean notAdmin(CommandSender sender) {
        if (!sender.hasPermission("soccer.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        return false;
    }

    private Arena requireArena(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "&cUsage: /soccer " + args[0] + " <arena>");
            return null;
        }
        Arena arena = plugin.arenas().get(args[1]);
        if (arena == null) {
            plugin.messages().send(sender, "arena-not-found", "arena", args[1]);
        }
        return arena;
    }

    // ==================================================================
    // Tab completion
    // ==================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(PLAYER_SUBS);
            if (sender.hasPermission("soccer.admin")) {
                options.addAll(ADMIN_SUBS);
            } else if (canUseStadium(sender)) {
                options.add("cupbotmatch");
                options.add("cupfixturearena");
                options.add("stadium");
            }
            return filter(options, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("top") || sub.equals("leaderboard")) {
                return filter(ChatLeaderboard.tabCompleteCategories(args[1]), args[1]);
            }
            if (sub.equals("quickjoin") && plugin.settings().quickjoinTeamPick) {
                return filter(Arrays.asList("red", "blue"), args[1]);
            }
            if (sub.equals("cupcreate")) {
                return filter(Arrays.asList("league", "knockout"), args[1]);
            }
            if (sub.equals("cupdelete") || sub.equals("cupstart") || sub.equals("cuprename")
                    || sub.equals("cupbotmatch") || sub.equals("cupfixturearena")) {
                List<String> ids = new ArrayList<>();
                plugin.championships().getAll().forEach(c -> ids.add(c.getId()));
                return filter(ids, args[1]);
            }
            if (sub.equals("stadium")) {
                return filter(plugin.arenas().getArenaNames(), args[1]);
            }
            if (sub.equals("teamrename")) {
                List<String> ids = new ArrayList<>();
                plugin.championships().getAll().forEach(c -> ids.add(c.getId()));
                return filter(ids, args[1]);
            }
            if (sub.equals("stats") || sub.equals("resetstats")) {
                if (sub.equals("resetstats") && sender.hasPermission("soccer.admin")) {
                    List<String> names = new ArrayList<>(onlinePlayerNames());
                    names.add("all");
                    return filter(names, args[1]);
                }
                return filter(onlinePlayerNames(), args[1]);
            }
            if (needsArena(sub)) {
                return filter(plugin.arenas().getArenaNames(), args[1]);
            }
        }
        if ((args.length == 3 || args.length == 4) && args[0].equalsIgnoreCase("botmatch")) {
            return filter(Arrays.asList("easy", "medium", "hard"), args[args.length - 1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cupbotmatch")) {
            Championship championship = plugin.championships().get(args[1]);
            if (championship == null) {
                return new ArrayList<>();
            }
            List<String> fixtureIds = new ArrayList<>();
            championship.getFixtures().forEach(f -> fixtureIds.add(f.getId()));
            return filter(fixtureIds, args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cupfixturearena")) {
            Championship championship = plugin.championships().get(args[1]);
            if (championship == null) {
                return new ArrayList<>();
            }
            List<String> fixtureIds = new ArrayList<>();
            championship.getFixtures().forEach(f -> fixtureIds.add(f.getId()));
            return filter(fixtureIds, args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("cupfixturearena")) {
            return filter(plugin.arenas().getArenaNames(), args[3]);
        }
        return new ArrayList<>();
    }

    private boolean needsArena(String sub) {
        return Arrays.asList("join", "delete", "setfield", "setredgoal", "setbluegoal",
                "setballspawn", "setredspawn", "setbluespawn", "setlobby", "setspectator",
                "setredpenalty", "setbluepenalty",
                "enable", "disable", "start", "botmatch", "stop", "penalty", "cleanup",
                "addoutline", "clearoutlines", "listoutlines").contains(sub);
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }

    private static String joinArgs(String[] args, int from) {
        if (from >= args.length) {
            return "";
        }
        StringBuilder builder = new StringBuilder(args[from]);
        for (int i = from + 1; i < args.length; i++) {
            builder.append(' ').append(args[i]);
        }
        return builder.toString();
    }
}
