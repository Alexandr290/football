package com.ballgame.soccer.command;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.arena.Arena;
import com.ballgame.soccer.championship.Championship;
import com.ballgame.soccer.championship.ChampionshipType;
import com.ballgame.soccer.championship.ChampTeam;
import com.ballgame.soccer.crowd.CrowdPackMode;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.stats.PlayerStats;
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
import java.util.function.ToIntFunction;

/**
 * Single-entry command dispatcher for {@code /soccer}. Handles both player-facing
 * commands (join/leave/stats) and admin arena setup, plus tab completion.
 */
public final class SoccerCommand implements CommandExecutor, TabCompleter {

    private final SoccerPlugin plugin;
    private final Map<UUID, Location[]> selections = new HashMap<>();

    private static final List<String> PLAYER_SUBS = Arrays.asList(
            "join", "quickjoin", "leave", "list", "stats", "top", "cup", "championship", "crowdpack", "help");
    private static final List<String> ADMIN_SUBS = Arrays.asList(
            "create", "delete", "pos1", "pos2", "setfield", "setredgoal", "setbluegoal",
            "setballspawn", "setredspawn", "setbluespawn", "setlobby", "setspectator",
            "enable", "disable", "start", "stop", "reload", "crowdpack",
            "cupcreate", "cupdelete", "cupstart", "cuprename", "teamrename");

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
            case "quickjoin":   return quickJoin(sender);
            case "leave":       return leave(sender);
            case "list":        return list(sender);
            case "stats":       return stats(sender, args);
            case "top":         return top(sender, args);
            case "cup":
            case "championship": return openChampionshipGui(sender);
            case "crowdpack":   return crowdPack(sender);
            case "help":        help(sender); return true;

            case "cupcreate":   return cupCreate(sender, args);
            case "cupdelete":   return cupDelete(sender, args);
            case "cupstart":    return cupStart(sender, args);
            case "cuprename":   return cupRename(sender, args);
            case "teamrename":  return teamRename(sender, args);

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
            case "enable":      return toggle(sender, args, true);
            case "disable":     return toggle(sender, args, false);
            case "start":       return adminStart(sender, args);
            case "stop":        return adminStop(sender, args);
            case "reload":      return reload(sender);
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
            plugin.messages().sendRaw(player, "&cUsage: /soccer join <arena>");
            return true;
        }
        Arena arena = plugin.arenas().get(args[1]);
        if (arena == null) {
            plugin.messages().send(player, "arena-not-found", "arena", args[1]);
            return true;
        }
        plugin.games().join(player, arena);
        return true;
    }

    private boolean quickJoin(CommandSender sender) {
        Player player = requirePlayer(sender, "soccer.play");
        if (player == null) {
            return true;
        }
        plugin.games().quickJoin(player);
        return true;
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
        String category = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "goals";
        ToIntFunction<PlayerStats> key;
        switch (category) {
            case "wins":    key = PlayerStats::getWins; break;
            case "assists": key = PlayerStats::getAssists; break;
            case "tackles": key = PlayerStats::getTackles; break;
            case "saves":   key = PlayerStats::getSaves; break;
            case "goals":
            default:        key = PlayerStats::getGoals; category = "goals"; break;
        }
        List<PlayerStats> top = plugin.stats().leaderboard(key, 10);
        plugin.messages().sendRaw(sender, "&a&lTop 10 - " + category);
        if (top.isEmpty()) {
            plugin.messages().sendRaw(sender, "&7  (no data yet)");
            return true;
        }
        int rank = 1;
        for (PlayerStats s : top) {
            sender.sendMessage(Text.color("&e#" + rank + " &f" + s.getName()
                    + " &7- &a" + key.applyAsInt(s)));
            rank++;
        }
        return true;
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
    private enum PointTarget { BALL, RED, BLUE, LOBBY, SPECTATOR }

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
        double maxY = Math.max(a.getY(), b.getY());
        double minZ = Math.min(a.getZ(), b.getZ());
        double maxZ = Math.max(a.getZ(), b.getZ());
        if (maxY - minY < 2.5) {
            maxY = minY + 3.0;
        }
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
        }
        plugin.arenas().save();
        plugin.messages().sendRaw(player, "&aSet " + target.name().toLowerCase(Locale.ROOT)
                + " spawn for &f" + arena.getName() + "&a.");
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

    private boolean crowdPack(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().sendRaw(sender, "&cPlayers only.");
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
            }
            case DOWNLOAD, URL -> {
                String url = plugin.crowdPack().sampleDownloadUrl(player);
                plugin.messages().send(player, "crowd-pack-url", "url", url != null ? url : "n/a");
                plugin.messages().send(player, "crowd-pack-hint");
                plugin.crowdPack().offer(player);
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
        plugin.messages().send(sender, "reload-success");
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
        plugin.messages().sendRaw(sender, "&8 6. &f/soccer setlobby " + arena + " &7(optional)");
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
        Championship created = plugin.championships().create(id, name, type, arena);
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
        plugin.messages().sendRaw(sender, "&f/soccer quickjoin &7- join the best available match");
        plugin.messages().sendRaw(sender, "&f/soccer join <arena> &7- join a specific arena");
        plugin.messages().sendRaw(sender, "&f/soccer leave &7- leave your match");
        plugin.messages().sendRaw(sender, "&f/soccer list &7- list arenas");
        plugin.messages().sendRaw(sender, "&f/soccer stats [player] &7- view stats");
        plugin.messages().sendRaw(sender, "&f/soccer top [goals|wins|assists|tackles|saves]");
        plugin.messages().sendRaw(sender, "&f/soccer cup &7- open championships GUI");
        plugin.messages().sendRaw(sender, "&7Controls: &fLeft-click&7 shoot, &fRight-click&7 pass, "
                + "&fSneak&7 charge, &fSneak+Sprint&7 tackle.");
        if (sender.hasPermission("soccer.admin")) {
            plugin.messages().sendRaw(sender, "&c/soccer create|delete|setfield|setredgoal|setbluegoal");
            plugin.messages().sendRaw(sender, "&c/soccer setballspawn|setredspawn|setbluespawn|setlobby");
            plugin.messages().sendRaw(sender, "&c/soccer pos1|pos2|enable|disable|start|stop|reload");
            plugin.messages().sendRaw(sender, "&c/soccer cupcreate <id> <league|knockout> [name] [arena]");
            plugin.messages().sendRaw(sender, "&c/soccer cupdelete <id> | cupstart <id>");
            plugin.messages().sendRaw(sender, "&c/soccer cuprename <id> <name> | teamrename <cupId> [teamId] <name>");
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
            }
            return filter(options, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("top")) {
                return filter(Arrays.asList("goals", "wins", "assists", "tackles", "saves"), args[1]);
            }
            if (sub.equals("cupcreate")) {
                return filter(Arrays.asList("league", "knockout"), args[1]);
            }
            if (sub.equals("cupdelete") || sub.equals("cupstart") || sub.equals("cuprename")) {
                List<String> ids = new ArrayList<>();
                plugin.championships().getAll().forEach(c -> ids.add(c.getId()));
                return filter(ids, args[1]);
            }
            if (sub.equals("teamrename")) {
                List<String> ids = new ArrayList<>();
                plugin.championships().getAll().forEach(c -> ids.add(c.getId()));
                return filter(ids, args[1]);
            }
            if (sub.equals("stats")) {
                return filter(onlinePlayerNames(), args[1]);
            }
            if (needsArena(sub)) {
                return filter(plugin.arenas().getArenaNames(), args[1]);
            }
        }
        return new ArrayList<>();
    }

    private boolean needsArena(String sub) {
        return Arrays.asList("join", "delete", "setfield", "setredgoal", "setbluegoal",
                "setballspawn", "setredspawn", "setbluespawn", "setlobby", "setspectator",
                "enable", "disable", "start", "stop").contains(sub);
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
