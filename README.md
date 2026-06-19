# AdvancedSoccer

A comprehensive, physics-driven **soccer / football minigame** plugin for
Spigot & Paper (Minecraft 1.20.x, Java 17).

The ball is **not** a vanilla entity — it runs a custom per-tick physics
simulation (gravity, air drag, rolling friction, bouncing, and the **Magnus
effect** for curving shots), so the game feels closer to real football than the
usual "shoot a snowball" plugins.

---

## Features

### Realistic ball physics
- Custom integrator running every tick on an `ItemDisplay` (rendered as a
  classic black/white soccer ball head that visibly **rolls**).
- Gravity, configurable **air resistance** and **ground (rolling) friction**.
- **Restitution-based bouncing** off walls/floors with per-axis, sub-stepped
  collision resolution (fast shots never tunnel through walls).
- **Magnus effect**: side-spin imparted by striking across the ball makes it
  **curve** in flight — bend free kicks around defenders.
- Speed clamp, rest threshold and spin decay for stable, believable motion.

### Gameplay mechanics
- **Dribbling** — walk with the ball at your feet and it follows you.
- **Shooting** (left-click) — power scales with charge & sprint, arcs with lift.
- **Passing** (right-click) — flatter, controlled, lower power.
- **Charged shots** — hold **Sneak** to build power (shown on a HUD power bar).
- **Slide tackles** — **Sneak + Sprint** to lunge and dispossess opponents.
- **Goalkeeping** — players inside their own goal box get save deflections.
- **Deflections & headers** — fast balls bounce off players (and can cause own
  goals!).
- **Stamina system** — sprinting, charging and tackling drain stamina; run out
  and you're slowed.

### Full match flow & rules
- Lobby countdown → kickoff countdown → play → **half time** → second half.
- Auto team balancing (Red vs Blue), configurable team sizes.
- **Goals, assists, own goals**, out-of-bounds resets.
- **Golden-goal extra time** and a **penalty-shootout** tiebreaker.
- Optional **mercy rule**.
- Per-match clock, possession tracking, live sidebar scoreboard.

### Presentation
- Flicker-free, fully configurable **sidebar scoreboard** with placeholders.
- Action-bar HUD with **stamina** and **shot-power** bars.
- Goal / kickoff / half-time / winner **titles**, sounds and **fireworks**.
- Team-colored leather kits.

### Architecture & extensibility
- Clean manager separation: `ArenaManager`, `GameManager`, `StatsManager`,
  `Messages`, `SoccerSettings`.
- Custom **Bukkit events**: `GoalScoredEvent`, `BallKickEvent` (cancellable &
  mutable), `MatchStateChangeEvent` — hook your own logic/power-ups.
- Persistent **per-player stats** (`stats.yml`) with leaderboards.
- Everything tunable from `config.yml` (physics, mechanics, timings, messages).
- Player state (inventory, location, gamemode, health…) is snapshotted on join
  and perfectly restored on leave/quit/disconnect.

---

## Building

Requires JDK 17 and Maven.

```bash
mvn clean package
```

The runnable plugin jar is produced at `target/AdvancedSoccer-1.0.0.jar`.
Drop it into your server's `plugins/` folder and restart.

---

## Quick start (admin)

Stand where you want each feature and run:

```
/soccer create <arena>

# Field boundary (whole pitch):
/soccer pos1            # stand at one corner
/soccer pos2            # stand at the opposite corner
/soccer setfield <arena>

# Goals (the goal a team DEFENDS; opponents score in it):
/soccer pos1 ; /soccer pos2 ; /soccer setredgoal <arena>
/soccer pos1 ; /soccer pos2 ; /soccer setbluegoal <arena>

# Spawns (stand at the spot):
/soccer setballspawn <arena>   # center / kickoff point
/soccer setredspawn <arena>
/soccer setbluespawn <arena>
/soccer setlobby <arena>       # optional waiting area
```

When all required pieces are set the plugin confirms the arena is ready.

---

## Playing

| Action | Control |
|--------|---------|
| Shoot  | **Left-click** |
| Pass   | **Right-click** |
| Charge power | Hold **Sneak** |
| Slide tackle | **Sneak + Sprint** |
| Dribble | Just run into/with the ball |

```
/soccer quickjoin            # join the best available match
/soccer join <arena>         # join a specific arena
/soccer leave
/soccer list                 # list arenas & status
/soccer stats [player]
/soccer top [goals|wins|assists|tackles|saves]
```

---

## Commands

### Player
- `/soccer quickjoin` — join the best available match
- `/soccer join <arena>` — join a specific arena
- `/soccer leave` — leave your match
- `/soccer list` — list arenas and their status
- `/soccer stats [player]` — lifetime statistics
- `/soccer top [category]` — leaderboards
- `/soccer help`

### Admin (`soccer.admin`)
- `/soccer create|delete <arena>`
- `/soccer pos1 | pos2` — region corner selection
- `/soccer setfield|setredgoal|setbluegoal <arena>`
- `/soccer setballspawn|setredspawn|setbluespawn|setlobby|setspectator <arena>`
- `/soccer enable|disable <arena>`
- `/soccer start|stop <arena>` — force-start / force-stop a match
- `/soccer reload` — reload config & arenas

## Permissions
- `soccer.play` — join and play (default: everyone)
- `soccer.admin` — manage arenas & matches (default: op)
- `soccer.*` — everything

---

## Configuration

All gameplay values live in `config.yml`, grouped into `settings`, `game`,
`ball`, `mechanics`, `scoreboard`, `teams` and `messages`. The physics block in
particular lets you completely change the feel of the ball (bouncier, heavier,
more/less curve, etc.). See the inline comments in `config.yml`.

---

## Developer API

```java
@EventHandler
public void onGoal(GoalScoredEvent event) {
    TeamSide team = event.getScoringTeam();
    SoccerPlayer scorer = event.getScorer(); // may be null
    // reward the scorer, update a hologram, post to Discord, ...
}

@EventHandler
public void onKick(BallKickEvent event) {
    // Power-up: double the power of every shot.
    if (event.getType() == BallKickEvent.KickType.SHOT) {
        event.setImpulse(event.getImpulse().multiply(2));
    }
}
```

## Project layout

```
com.ballgame.soccer
├── SoccerPlugin            # entry point + manager wiring
├── arena/                  # Arena model + ArenaManager (arenas.yml)
├── ball/                   # Ball entity + physics simulation
├── command/                # /soccer dispatcher + tab completion
├── config/                 # SoccerSettings + Messages
├── event/                  # custom Bukkit events
├── game/                   # Game engine, GameManager, players, state
├── listener/               # input, protection & connection listeners
├── mechanics/              # kick/spin math
├── scoreboard/             # sidebar scoreboard
├── stats/                  # persistent stats + leaderboards (stats.yml)
└── util/                   # Text, Cuboid, Locations, Items, ActionBar
```
