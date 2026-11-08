package com.ballgame.soccer.cinematic;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.bot.BotRole;
import com.ballgame.soccer.bot.MatchBot;
import com.ballgame.soccer.bot.StandInPlayer;
import com.ballgame.soccer.config.Messages;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.crowd.CrowdAudio;
import com.ballgame.soccer.fifa.FifaPlayer;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.SoccerPlayer;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.referee.RefereeActor;
import com.ballgame.soccer.referee.RefereeController;
import com.ballgame.soccer.util.ActionBar;
import com.ballgame.soccer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Broadcast-style pre-match show, modelled on the FC presentation:
 * <ol>
 *   <li>aerial flyover of the stadium with the fixture title;</li>
 *   <li>tunnel walkout — officials lead both teams onto the pitch, low tracking
 *       camera that cranes up as the line forms;</li>
 *   <li>line-up pans along each team with name captions;</li>
 *   <li>wide broadcast shot while everyone jogs to their kickoff spots;</li>
 *   <li>low shot behind the ball rising over the kickoff taker.</li>
 * </ol>
 * Humans watch through the {@link CameraRig}; on the pitch they are played by
 * stand-ins wearing their skin. Every scene change is a hard cut.
 */
public final class MatchIntro {

    private enum Scene {
        ESTABLISH(120), WALKOUT(170), LINEUP_HOME(110), LINEUP_AWAY(110), BREAK(90), KICKOFF(70);

        private final int baseTicks;

        Scene(int baseTicks) {
            this.baseTicks = baseTicks;
        }
    }

    private static final double WALK_PACE = 0.155;
    private static final double JOG_PACE = 0.23;
    private static final int SKIP_GRACE_TICKS = 40;
    private static final double SPECTATOR_RANGE_SQ = 160.0 * 160.0;
    private static final double ARRIVE_EPSILON = 0.03;

    private final SoccerPlugin plugin;
    private final Game game;
    private final SoccerSettings settings;
    private final Messages messages;
    private final StadiumFrame frame;
    private final CameraRig rig;
    private final double scale;
    private final double walkPace;
    private final double jogPace;

    private final List<Actor> actors = new ArrayList<>();
    private final List<Actor> officials = new ArrayList<>();
    private final EnumMap<TeamSide, List<Actor>> lineups = new EnumMap<>(TeamSide.class);
    private final EnumMap<Scene, Integer> lengths = new EnumMap<>(Scene.class);
    private final Set<UUID> skipVotes = new HashSet<>();
    private final Set<UUID> invitedSpectators = new HashSet<>();

    private BossBar bossBar;
    private Scene scene;
    private int sceneTick;
    private int elapsed;
    private int totalTicks;
    private boolean skipRequested;
    private boolean stopped;

    private double rowAcross;
    private double spacing;
    private TeamSide kickoffTeam;
    private Location ballSpot;
    private String lastCaption = "";
    private int captionAge;

    public MatchIntro(SoccerPlugin plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
        this.settings = plugin.settings();
        this.messages = plugin.messages();
        this.frame = StadiumFrame.of(game.getArena());
        this.rig = new CameraRig(plugin, settings.introCameraSmoothing);
        this.scale = settings.introDurationScale;
        this.walkPace = clamp(WALK_PACE / scale, 0.09, 0.3);
        this.jogPace = clamp(JOG_PACE / scale, 0.15, 0.42);
        for (TeamSide side : TeamSide.values()) {
            lineups.put(side, new ArrayList<>());
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** @return false when the show cannot run (no audience, no pitch geometry) */
    public boolean start() {
        if (frame == null || !hasAudience()) {
            return false;
        }
        kickoffTeam = game.getKickoffTeam() != null ? game.getKickoffTeam() : TeamSide.RED;
        ballSpot = game.getBall() != null && game.getBall().getLocation() != null
                ? game.getBall().getLocation().clone()
                : frame.at(0, 0, 0);
        castActors();
        if (actors.isEmpty()) {
            removeStandIns();
            return false;
        }
        planRoutes();
        for (Scene s : Scene.values()) {
            int length = Math.max(20, (int) Math.round(s.baseTicks * scale));
            lengths.put(s, length);
            totalTicks += length;
        }
        if (!rig.start(shotPose(Scene.ESTABLISH, 0.0, 0))) {
            removeStandIns();
            return false;
        }
        bossBar = Bukkit.createBossBar(Text.color(messages.raw("intro-boss-bar-noskip")),
                BarColor.WHITE, BarStyle.SOLID);
        bossBar.setProgress(0.0);
        refreshAudience(true);
        if (rig.onlineViewers().isEmpty()) {
            stop();
            return false;
        }
        for (Actor actor : actors) {
            actor.body.place(actor.queueSpot, queueLook(actor));
        }
        if (settings.introLineupChat) {
            sendLineupCard();
        }
        CrowdAudio crowd = game.getCrowd();
        if (crowd != null) {
            crowd.onMatchAnticipation();
        }
        enterScene(Scene.ESTABLISH);
        updateBossBar();
        return true;
    }

    /** Advances the show by one tick. @return true once it is over */
    public boolean tick() {
        if (stopped || skipRequested) {
            return true;
        }
        elapsed++;
        sceneTick++;
        if (sceneTick >= lengths.get(scene)) {
            Scene next = next(scene);
            if (next == null) {
                return true;
            }
            enterScene(next);
        }
        if (elapsed % 10 == 0) {
            refreshAudience(elapsed % 40 == 0);
        }
        moveActors();
        double u = progress();
        rig.frame(shotPose(scene, u, sceneTick), false);
        rig.tick();
        updateCaptions(u);
        if (elapsed % 5 == 0) {
            updateBossBar();
        }
        return false;
    }

    /** Releases every viewer, removes stand-ins and HUD. Safe to call twice. */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        List<Player> viewers = rig.onlineViewers();
        rig.stop();
        for (Player player : viewers) {
            player.resetTitle();
            ActionBar.send(player, "");
        }
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        removeStandIns();
    }

    public boolean wasSkipped() {
        return skipRequested;
    }

    public void onParticipantLeft(Player player) {
        if (player == null) {
            return;
        }
        rig.removeViewer(player, false);
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
        skipVotes.remove(player.getUniqueId());
        for (Actor actor : actors) {
            if (!actor.gone && player.getUniqueId().equals(actor.humanId)) {
                actor.body.remove();
                actor.gone = true;
            }
        }
    }

    public void voteSkip(Player player) {
        if (!settings.introSkipBySneak || stopped || skipRequested || elapsed < SKIP_GRACE_TICKS
                || player == null || !rig.isLockedViewer(player.getUniqueId())) {
            return;
        }
        if (!skipVotes.add(player.getUniqueId())) {
            return;
        }
        int needed = votesNeeded();
        announce(messages.format("intro-skip-voted",
                "player", player.getName(),
                "votes", String.valueOf(skipVotes.size()),
                "needed", String.valueOf(needed)));
        if (skipVotes.size() >= needed) {
            skipRequested = true;
            announce(messages.format("intro-skipped"));
        }
        updateBossBar();
    }

    // ------------------------------------------------------------------
    // Cast and choreography
    // ------------------------------------------------------------------

    private void castActors() {
        Location backstage = frame.at(0, frame.halfWidth() + 2.0, 0);
        int bodyIndex = 0;
        for (SoccerPlayer sp : new ArrayList<>(game.getHumanParticipants())) {
            Player player = sp.getBukkitPlayer();
            if (player == null || !player.isOnline() || sp.isSentOff()) {
                continue;
            }
            if (sp.isReferee()) {
                StandInPlayer body = StandInPlayer.spawn(plugin, game, player, TeamSide.RED,
                        bodyIndex++, backstage, refereeKit());
                if (body != null) {
                    Actor actor = new Actor(new StandInBody(body), null, player.getUniqueId(),
                            player.getName(), "", "ref", true, 0);
                    actor.kickoffSpot = frame.at(0, -3.0, 0);
                    officials.add(actor);
                }
                continue;
            }
            TeamSide team = sp.getTeam();
            if (team == null) {
                continue;
            }
            int squadIndex = game.findClaimedSquadIndex(sp.getUuid(), team);
            FifaPlayer fifa = squadIndex >= 0 ? game.fifaStarter(team, squadIndex) : null;
            StandInPlayer body = StandInPlayer.spawn(plugin, game, player, team, bodyIndex++, backstage, null);
            if (body == null) {
                continue;
            }
            String number = fifa != null && fifa.getNumber() > 0 ? String.valueOf(fifa.getNumber()) : "";
            String position;
            if (fifa != null) {
                position = fifa.getPosition().name().toLowerCase(Locale.ROOT);
            } else if (squadIndex >= 0) {
                position = roleKey(game.getFormation(team).roleForSquadIndex(squadIndex, false));
            } else {
                position = "fw";
            }
            Actor actor = new Actor(new StandInBody(body), team, player.getUniqueId(), player.getName(),
                    number, position, true, squadIndex >= 0 ? squadIndex : 99);
            actor.kickoffSpot = game.kickoffSpotFor(sp);
            lineups.get(team).add(actor);
        }
        for (MatchBot bot : new ArrayList<>(game.getBots())) {
            TeamSide team = bot.getTeam();
            if (team == null || bot.getLocation() == null) {
                continue;
            }
            FifaPlayer fifa = game.fifaStarter(team, bot.getSquadIndex());
            String number = fifa != null && fifa.getNumber() > 0
                    ? String.valueOf(fifa.getNumber())
                    : String.valueOf(bot.getSquadIndex() + 1);
            String position = fifa != null
                    ? fifa.getPosition().name().toLowerCase(Locale.ROOT)
                    : roleKey(bot.getRole());
            Actor actor = new Actor(new BotBody(bot), team, null, bot.getName(), number, position,
                    false, bot.getSquadIndex());
            actor.kickoffSpot = game.kickoffSpotFor(bot);
            lineups.get(team).add(actor);
        }
        Comparator<Actor> order = Comparator.comparingInt((Actor a) -> a.human ? 0 : 1)
                .thenComparingInt(a -> a.squadIndex);
        for (List<Actor> list : lineups.values()) {
            list.sort(order);
        }

        RefereeController crew = game.getReferee();
        if (officials.isEmpty() && crew != null && crew.isStageable()) {
            for (RefereeActor.Role role : new RefereeActor.Role[] {
                    RefereeActor.Role.CENTER, RefereeActor.Role.ASSISTANT_LEFT, RefereeActor.Role.ASSISTANT_RIGHT}) {
                Actor actor = new Actor(new CrewBody(crew, role), null, null, crew.officialName(role), "",
                        role == RefereeActor.Role.CENTER ? "ref" : "ar", false, officials.size());
                actor.kickoffSpot = crew.kickoffTarget(role);
                officials.add(actor);
            }
        }

        actors.addAll(officials);
        actors.addAll(lineups.get(TeamSide.RED));
        actors.addAll(lineups.get(TeamSide.BLUE));
        for (Actor actor : actors) {
            actor.glancePhase = ThreadLocalRandom.current().nextInt(80);
            actor.glanceSide = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            if (actor.kickoffSpot == null) {
                actor.kickoffSpot = frame.at(0, -3.0, 0);
            }
        }
    }

    /**
     * Tunnel queue at the main-stand touchline, then a straight walk in past
     * the line-up row, along it to the player's spot, and a last step back
     * toward the stand so the body squares up to the cameras.
     */
    private void planRoutes() {
        double w = frame.halfWidth();
        double rowDepth = clamp(w * 0.38, 4.0, 10.0);
        rowAcross = w - rowDepth;
        int longest = Math.max(lineups.get(TeamSide.RED).size(), lineups.get(TeamSide.BLUE).size());
        spacing = clamp((frame.halfLength() * 0.85 - 2.6) / Math.max(1, longest - 1), 0.85, 1.25);
        Vector intoPitch = frame.side().clone().multiply(-1);

        double[] officialAlong = {0.0, -0.95, 0.95};
        for (int i = 0; i < officials.size(); i++) {
            Actor actor = officials.get(i);
            double along = officialAlong[Math.min(i, officialAlong.length - 1)];
            actor.queueSpot = facing(frame.at(along, w + 0.5, 0), intoPitch);
            actor.path.add(frame.at(along, rowAcross - 1.2, 0));
            actor.path.add(frame.at(along, rowAcross, 0));
            actor.lineAlong = along;
        }
        for (TeamSide side : TeamSide.values()) {
            double sign = side == TeamSide.RED ? -1.0 : 1.0;
            List<Actor> list = lineups.get(side);
            for (int i = 0; i < list.size(); i++) {
                Actor actor = list.get(i);
                double spot = sign * (2.6 + i * spacing);
                actor.queueSpot = facing(frame.at(sign * 1.7, w + 1.7 + i * 0.95, 0), intoPitch);
                actor.path.add(frame.at(sign * 1.7, rowAcross - 1.2, 0));
                actor.path.add(frame.at(spot, rowAcross - 1.2, 0));
                actor.path.add(frame.at(spot, rowAcross, 0));
                actor.lineAlong = spot;
            }
        }
    }

    private void enterScene(Scene next) {
        boolean cut = scene != null;
        scene = next;
        sceneTick = 0;
        if (cut) {
            rig.frame(shotPose(next, 0.0, 0), true);
        }
        CrowdAudio crowd = game.getCrowd();
        switch (next) {
            case ESTABLISH -> showTitle(
                    messages.format("intro-title-match",
                            "red_color", game.getTeamKitChatColor(TeamSide.RED),
                            "red", game.teamDisplayName(TeamSide.RED),
                            "blue_color", game.getTeamKitChatColor(TeamSide.BLUE),
                            "blue", game.teamDisplayName(TeamSide.BLUE)),
                    messages.format("intro-subtitle-match", "arena", game.getArena().getName()),
                    15, 75, 20);
            case WALKOUT -> {
                if (crowd != null) {
                    crowd.onTeamsWalkOut();
                }
                showTitle(" ", messages.format("intro-subtitle-walkout"), 10, 50, 15);
            }
            case LINEUP_HOME, LINEUP_AWAY -> {
                TeamSide side = next == Scene.LINEUP_HOME ? TeamSide.RED : TeamSide.BLUE;
                if (crowd != null && next == Scene.LINEUP_HOME) {
                    crowd.onLineUp();
                }
                showTitle(messages.format("intro-title-lineup",
                                "team_color", game.getTeamKitChatColor(side),
                                "team", game.teamDisplayName(side)),
                        messages.format("intro-subtitle-lineup",
                                "formation", game.getFormation(side).displayName()),
                        8, 45, 12);
            }
            case BREAK -> {
                for (Player player : rig.onlineViewers()) {
                    player.resetTitle();
                }
            }
            case KICKOFF -> {
                Location ballEye = ballSpot.clone().add(0, 0.3, 0);
                for (Actor actor : actors) {
                    if (!actor.gone) {
                        actor.body.place(actor.kickoffSpot, ballEye);
                    }
                }
                showTitle(" ", messages.format("intro-subtitle-kickoff",
                                "team_color", game.getTeamKitChatColor(kickoffTeam),
                                "team", game.teamDisplayName(kickoffTeam)),
                        8, 40, 12);
            }
        }
    }

    private void moveActors() {
        Location ballEye = ballSpot.clone().add(0, 0.3, 0);
        for (Actor actor : actors) {
            if (actor.gone) {
                continue;
            }
            switch (scene) {
                case ESTABLISH -> actor.body.step(actor.queueSpot, 0.0, queueLook(actor));
                case WALKOUT, LINEUP_HOME, LINEUP_AWAY -> walkRoute(actor);
                case BREAK -> {
                    Location here = actor.body.location();
                    boolean far = here != null && horizontal(here, actor.kickoffSpot) > 1.5;
                    Location look = far ? ahead(here, actor.kickoffSpot, 1.55) : ballEye;
                    actor.body.step(actor.kickoffSpot, jogPace, look);
                }
                case KICKOFF -> actor.body.step(actor.kickoffSpot, 0.0, ballEye);
            }
        }
    }

    private void walkRoute(Actor actor) {
        Location here = actor.body.location();
        if (here == null) {
            return;
        }
        if (actor.pathIndex >= actor.path.size()) {
            Location last = actor.path.get(actor.path.size() - 1);
            actor.body.step(last, 0.0, lineupLook(actor));
            return;
        }
        Location target = actor.path.get(actor.pathIndex);
        boolean lastLeg = actor.pathIndex == actor.path.size() - 1;
        Location look = lastLeg ? standLook(actor) : walkLook(actor, here, target);
        actor.body.step(target, walkPace, look);
        Location after = actor.body.location();
        if (after != null && horizontal(after, target) <= ARRIVE_EPSILON) {
            actor.pathIndex++;
        }
    }

    private Location queueLook(Actor actor) {
        return actor.queueSpot.clone().add(frame.side().clone().multiply(-6.0)).add(0, 1.6, 0);
    }

    /** Eyes ahead while walking, with the odd glance up at the crowd. */
    private Location walkLook(Actor actor, Location here, Location target) {
        Vector dir = target.toVector().subtract(here.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 1.0e-6) {
            return standLook(actor);
        }
        dir.normalize();
        Location look = here.clone().add(dir.clone().multiply(6.0)).add(0, 1.55, 0);
        if ((elapsed + actor.glancePhase) % 80 < 22) {
            Vector lateral = new Vector(-dir.getZ(), 0, dir.getX()).multiply(3.6 * actor.glanceSide);
            look.add(lateral).add(0, 2.6, 0);
        }
        return look;
    }

    private Location standLook(Actor actor) {
        return frame.at(actor.lineAlong, frame.halfWidth() + 12.0, 4.5);
    }

    /** Facing the main stand; meets the lens as the pan camera passes. */
    private Location lineupLook(Actor actor) {
        if (scene == Scene.LINEUP_HOME || scene == Scene.LINEUP_AWAY) {
            double camAlong = panAlong(scene, progress());
            if (Math.abs(camAlong - actor.lineAlong) < 2.2) {
                return frame.at(camAlong, rowAcross + 2.7, 1.55);
            }
        }
        return standLook(actor);
    }

    private static Location ahead(Location here, Location target, double eyeHeight) {
        Vector dir = target.toVector().subtract(here.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 1.0e-6) {
            return target.clone().add(0, eyeHeight, 0);
        }
        dir.normalize().multiply(6.0);
        return here.clone().add(dir).add(0, eyeHeight, 0);
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private Location shotPose(Scene s, double u, int t) {
        double w = frame.halfWidth();
        double l = frame.halfLength();
        switch (s) {
            case ESTABLISH: {
                // Spidercam sweep over the far half, settling on the tunnel side.
                double e = StadiumFrame.ease(u);
                double theta = Math.toRadians(lerp(235.0, 160.0, e));
                double radius = Math.max(l, w);
                double height = Math.max(8.0, Math.min(42.0, lerp(radius * 0.55, radius * 0.36, e)));
                Location eye = frame.at(Math.sin(theta) * l * 0.92, Math.cos(theta) * w * 0.9, height);
                Location target = frame.at(lerp(0.0, 2.0, e), lerp(0.0, w * 0.4, e), 1.5);
                return StadiumFrame.look(eye, target);
            }
            case WALKOUT: {
                // Low dolly retreating in front of the procession, then a crane-up reveal.
                double head = w + 0.5 - walkPace * t;
                double soft = softplus(head - rowAcross, 1.4);
                Location eye0 = frame.at(1.7, rowAcross - 5.5 + soft, 1.35);
                Location look0 = frame.at(0.0, rowAcross + soft + 1.0, 1.45);
                if (u < 0.6) {
                    return StadiumFrame.look(eye0, look0);
                }
                double c = StadiumFrame.ease((u - 0.6) / 0.4);
                Location eye1 = frame.at(2.8, Math.max(-w + 1.0, rowAcross - 13.0), 7.5);
                Location look1 = frame.at(0.0, rowAcross + 0.8, 0.9);
                return StadiumFrame.look(StadiumFrame.lerp(eye0, eye1, c), StadiumFrame.lerp(look0, look1, c));
            }
            case LINEUP_HOME:
            case LINEUP_AWAY: {
                double sign = s == Scene.LINEUP_HOME ? -1.0 : 1.0;
                double along = panAlong(s, u);
                Location eye = frame.at(along, rowAcross + 2.7, 1.55);
                Location target = frame.at(along + sign * 0.9, rowAcross, 1.45);
                return StadiumFrame.look(eye, target);
            }
            case BREAK: {
                double e = StadiumFrame.ease(u);
                Location eye = frame.at(lerp(-5.0, 5.0, e), w - 1.0, lerp(15.0, 12.0, e));
                Location target = frame.at(lerp(-3.0, 3.0, e), -w * 0.25, 0.0);
                return StadiumFrame.look(eye, target);
            }
            case KICKOFF:
            default: {
                double d = kickoffTeam == TeamSide.BLUE ? -1.0 : 1.0;
                double a0 = frame.alongOf(ballSpot);
                double c0 = frame.acrossOf(ballSpot);
                double e = StadiumFrame.ease(u);
                Location eyeA = frame.at(a0 - d * 2.4, c0 + 0.9, 0.55);
                Location eyeB = frame.at(a0 - d * 10.0, c0 + 2.2, 6.5);
                Location lookA = frame.at(a0, c0, 0.25);
                Location lookB = frame.at(a0 + d * 12.0, c0, 0.5);
                return StadiumFrame.look(StadiumFrame.lerp(eyeA, eyeB, e), StadiumFrame.lerp(lookA, lookB, e));
            }
        }
    }

    private double panAlong(Scene s, double u) {
        TeamSide side = s == Scene.LINEUP_HOME ? TeamSide.RED : TeamSide.BLUE;
        double sign = side == TeamSide.RED ? -1.0 : 1.0;
        int n = Math.max(1, lineups.get(side).size());
        double start = sign * 0.6;
        double end = sign * (2.6 + (n - 1) * spacing + 0.9);
        double e = 0.5 - 0.5 * Math.cos(Math.PI * clamp(u, 0.0, 1.0));
        return lerp(start, end, e);
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    private void updateCaptions(double u) {
        String caption = "";
        if (scene == Scene.LINEUP_HOME || scene == Scene.LINEUP_AWAY) {
            TeamSide side = scene == Scene.LINEUP_HOME ? TeamSide.RED : TeamSide.BLUE;
            double camAlong = panAlong(scene, u);
            Actor nearest = null;
            double best = Math.max(0.6, spacing * 0.6);
            List<Actor> candidates = new ArrayList<>(officials);
            candidates.addAll(lineups.get(side));
            for (Actor actor : candidates) {
                if (actor.gone) {
                    continue;
                }
                double d = Math.abs(actor.lineAlong - camAlong);
                if (d < best) {
                    best = d;
                    nearest = actor;
                }
            }
            if (nearest != null) {
                caption = caption(nearest);
            }
        }
        captionAge++;
        if (caption.equals(lastCaption) && (caption.isEmpty() || captionAge < 20)) {
            return;
        }
        lastCaption = caption;
        captionAge = 0;
        for (Player player : rig.onlineViewers()) {
            ActionBar.send(player, caption);
        }
    }

    private String caption(Actor actor) {
        String color = actor.team != null ? game.getTeamKitChatColor(actor.team) : "&f";
        String name = actor.human && actor.team != null ? "★ " + actor.name : actor.name;
        return messages.format("intro-lower-third",
                "team_color", color,
                "number", actor.number.isEmpty() ? "" : "#" + actor.number,
                "name", name,
                "position", positionLabel(actor.positionKey));
    }

    private void updateBossBar() {
        if (bossBar == null) {
            return;
        }
        bossBar.setProgress(clamp((double) elapsed / Math.max(1, totalTicks), 0.0, 1.0));
        if (settings.introSkipBySneak && rig.lockedViewerCount() > 0) {
            bossBar.setTitle(messages.format("intro-boss-bar",
                    "votes", String.valueOf(skipVotes.size()),
                    "needed", String.valueOf(votesNeeded())));
        } else {
            bossBar.setTitle(messages.format("intro-boss-bar-noskip"));
        }
    }

    private void showTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        for (Player player : rig.onlineViewers()) {
            player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        }
    }

    private void announce(String line) {
        String message = messages.prefix() + line;
        for (Player player : rig.onlineViewers()) {
            player.sendMessage(message);
        }
    }

    private void sendLineupCard() {
        List<String> lines = new ArrayList<>();
        for (TeamSide side : TeamSide.values()) {
            lines.add(messages.format("intro-lineup-header",
                    "team_color", game.getTeamKitChatColor(side),
                    "team", game.teamDisplayName(side),
                    "formation", game.getFormation(side).displayName()));
            List<Actor> sorted = new ArrayList<>(lineups.get(side));
            sorted.sort(Comparator.comparingInt(a -> a.squadIndex));
            for (Actor actor : sorted) {
                lines.add(messages.format("intro-lineup-entry",
                        "number", actor.number.isEmpty() ? "--" : actor.number,
                        "name", actor.human ? "★ " + actor.name : actor.name,
                        "position", positionLabel(actor.positionKey)));
            }
        }
        if (!officials.isEmpty()) {
            lines.add(messages.format("intro-lineup-referee", "name", officials.get(0).name));
        }
        for (Player player : rig.onlineViewers()) {
            for (String line : lines) {
                player.sendMessage(line);
            }
        }
    }

    private String positionLabel(String key) {
        return Text.color(messages.raw("intro-position-" + key));
    }

    // ------------------------------------------------------------------
    // Audience
    // ------------------------------------------------------------------

    private boolean hasAudience() {
        for (SoccerPlayer sp : game.getHumanParticipants()) {
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                return true;
            }
        }
        return !spectatorCandidates().isEmpty();
    }

    private void refreshAudience(boolean includeSpectators) {
        for (SoccerPlayer sp : new ArrayList<>(game.getHumanParticipants())) {
            Player player = sp.getBukkitPlayer();
            if (player != null && player.isOnline() && !rig.hasViewer(player.getUniqueId())) {
                rig.addViewer(player, true);
                if (bossBar != null) {
                    bossBar.addPlayer(player);
                }
            }
        }
        if (includeSpectators) {
            for (Player player : spectatorCandidates()) {
                invitedSpectators.add(player.getUniqueId());
                rig.addViewer(player, false);
                if (bossBar != null) {
                    bossBar.addPlayer(player);
                }
                player.sendMessage(messages.prefix() + messages.format("intro-spectator-hint"));
            }
        }
        if (bossBar != null) {
            for (Player player : new ArrayList<>(bossBar.getPlayers())) {
                if (!rig.hasViewer(player.getUniqueId())) {
                    bossBar.removePlayer(player);
                }
            }
        }
    }

    private List<Player> spectatorCandidates() {
        List<Player> list = new ArrayList<>();
        if (!settings.introIncludeSpectators || frame == null) {
            return list;
        }
        Location center = frame.at(0, 0, 0);
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (invitedSpectators.contains(id) || rig.hasViewer(id)
                    || player.getGameMode() != GameMode.SPECTATOR
                    || player.getSpectatorTarget() != null
                    || !player.getWorld().equals(frame.world())
                    || plugin.games().getGame(player) != null
                    || plugin.games().isFreeSpectator(player)
                    || player.getLocation().distanceSquared(center) > SPECTATOR_RANGE_SQ) {
                continue;
            }
            list.add(player);
        }
        return list;
    }

    private int votesNeeded() {
        int locked = rig.lockedViewerCount();
        return Math.max(1, (int) Math.ceil(locked * settings.introSkipVoteRatio));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Scene next(Scene current) {
        Scene[] all = Scene.values();
        for (int i = current.ordinal() + 1; i < all.length; i++) {
            Scene candidate = all[i];
            if (candidate == Scene.LINEUP_HOME && lineups.get(TeamSide.RED).isEmpty()) {
                continue;
            }
            if (candidate == Scene.LINEUP_AWAY && lineups.get(TeamSide.BLUE).isEmpty()) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private double progress() {
        return clamp((double) sceneTick / Math.max(1, lengths.get(scene)), 0.0, 1.0);
    }

    private void removeStandIns() {
        for (Actor actor : actors) {
            if (!actor.gone) {
                actor.body.remove();
                actor.gone = true;
            }
        }
    }

    private ItemStack[] refereeKit() {
        return new ItemStack[] {
                null,
                dyed(Material.LEATHER_CHESTPLATE, Color.fromRGB(30, 30, 30)),
                dyed(Material.LEATHER_LEGGINGS, Color.BLACK),
                dyed(Material.LEATHER_BOOTS, Color.BLACK)
        };
    }

    private static ItemStack dyed(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String roleKey(BotRole role) {
        if (role == null) {
            return "mf";
        }
        return switch (role) {
            case GOALKEEPER -> "gk";
            case DEFENDER -> "df";
            case STRIKER -> "fw";
            default -> "mf";
        };
    }

    private static Location facing(Location at, Vector direction) {
        Location spot = at.clone();
        if (direction.lengthSquared() > 1.0e-6) {
            spot.setDirection(direction);
        }
        spot.setPitch(0f);
        return spot;
    }

    private static double horizontal(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static double softplus(double x, double k) {
        if (x * k > 30.0) {
            return x;
        }
        return Math.log1p(Math.exp(x * k)) / k;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------
    // Cast members
    // ------------------------------------------------------------------

    private interface Body {
        Location location();

        void place(Location at, Location lookAt);

        void step(Location target, double pace, Location lookAt);

        default void remove() {
        }
    }

    private record BotBody(MatchBot bot) implements Body {
        @Override
        public Location location() {
            return bot.getLocation();
        }

        @Override
        public void place(Location at, Location lookAt) {
            bot.cinematicPlace(at, lookAt);
        }

        @Override
        public void step(Location target, double pace, Location lookAt) {
            bot.cinematicStep(target, pace, lookAt);
        }
    }

    private record StandInBody(StandInPlayer standIn) implements Body {
        @Override
        public Location location() {
            return standIn.getLocation();
        }

        @Override
        public void place(Location at, Location lookAt) {
            standIn.place(at, lookAt);
        }

        @Override
        public void step(Location target, double pace, Location lookAt) {
            standIn.step(target, pace, lookAt);
        }

        @Override
        public void remove() {
            standIn.remove();
        }
    }

    private record CrewBody(RefereeController crew, RefereeActor.Role role) implements Body {
        @Override
        public Location location() {
            return crew.stagedLocation(role);
        }

        @Override
        public void place(Location at, Location lookAt) {
            crew.stagePlace(role, at, lookAt);
        }

        @Override
        public void step(Location target, double pace, Location lookAt) {
            crew.stageStep(role, target, pace, lookAt);
        }
    }

    private static final class Actor {
        private final Body body;
        private final TeamSide team;
        private final UUID humanId;
        private final String name;
        private final String number;
        private final String positionKey;
        private final boolean human;
        private final int squadIndex;
        private final List<Location> path = new ArrayList<>();
        private int pathIndex;
        private Location queueSpot;
        private Location kickoffSpot;
        private double lineAlong;
        private int glancePhase;
        private int glanceSide = 1;
        private boolean gone;

        private Actor(Body body, TeamSide team, UUID humanId, String name, String number,
                      String positionKey, boolean human, int squadIndex) {
            this.body = body;
            this.team = team;
            this.humanId = humanId;
            this.name = name != null ? name : "";
            this.number = number != null ? number : "";
            this.positionKey = positionKey;
            this.human = human;
            this.squadIndex = squadIndex;
        }
    }
}
