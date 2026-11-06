package com.ballgame.soccer.bot;

import com.ballgame.soccer.SoccerPlugin;
import com.ballgame.soccer.config.SoccerSettings;
import com.ballgame.soccer.game.Game;
import com.ballgame.soccer.game.TeamSide;
import com.ballgame.soccer.replay.MannequinSupport;
import com.ballgame.soccer.util.Items;
import com.ballgame.soccer.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Visible body for a match bot — Citizens player NPC when available, otherwise
 * Mannequin (1.21.4+) or team-colored armor stand.
 */
final class BotVisual {

    private enum Backend {
        CITIZENS, PLAYER_NPC, MANNEQUIN, ARMOR_STAND
    }

    private final SoccerPlugin plugin;
    private final SoccerSettings settings;
    private final String displayName;
    private final TeamSide team;
    private final int squadIndex;
    private final String skinOverride;

    private Backend backend = Backend.ARMOR_STAND;
    private CitizensSupport.CitizensNpc citizensNpc;
    private PlayerNpcSupport.NpcHandle playerNpc;
    private Entity entity;
    private ArmorStand fallback;
    private Location lastPos;
    private ItemStack[] armorKit;
    private ItemStack[] armorOverride;

    private final Game game;

    BotVisual(SoccerPlugin plugin, SoccerSettings settings, String displayName,
              TeamSide team, int squadIndex, Game game) {
        this(plugin, settings, displayName, team, squadIndex, null, game);
    }

    BotVisual(SoccerPlugin plugin, SoccerSettings settings, String displayName,
              TeamSide team, int squadIndex, String skinOverride, Game game) {
        this.plugin = plugin;
        this.settings = settings;
        this.displayName = displayName;
        this.team = team;
        this.squadIndex = squadIndex;
        this.skinOverride = skinOverride;
        this.game = game;
        this.armorKit = teamArmor();
    }

    /** Fixed kit instead of the team colours (e.g. the referee's black strip). */
    void setArmorOverride(ItemStack[] kit) {
        armorOverride = kit;
        armorKit = teamArmor();
    }

    void refreshArmorKit() {
        armorKit = teamArmor();
        refreshDisplayName();
        if (entity instanceof org.bukkit.entity.LivingEntity living) {
            equip(living);
        }
    }

    private void refreshDisplayName() {
        String label = Text.color(teamColor() + displayName);
        if (entity instanceof LivingEntity living && living.isValid()) {
            living.setCustomName(label);
            living.setCustomNameVisible(true);
        }
        if (fallback != null && fallback.isValid()) {
            fallback.setCustomName(label);
            fallback.setCustomNameVisible(true);
        }
    }

    /**
     * Citizens pathfinding is optional. On custom pitches it often never starts;
     * default is plugin-driven movement (same physics as armor-stand bots) with
     * the NPC teleported each tick so they visibly run and sprint.
     */
    boolean usesCitizensNavigation() {
        return backend == Backend.CITIZENS
                && citizensNpc != null
                && settings != null
                && settings.botCitizensPathfind;
    }

    void spawn(Location location) {
        spawn(location, 1);
    }

    void spawn(Location location, int projectedMatchBots) {
        remove();
        if (location == null || location.getWorld() == null) {
            return;
        }
        String mode = settings.botVisualType == null ? "auto" : settings.botVisualType.toLowerCase();
        if (shouldUseCitizens(mode) && trySpawnCitizens(location)) {
            return;
        }
        if (shouldUsePlayerNpc(mode) && trySpawnPlayerNpc(location)) {
            return;
        }
        if (shouldUseMannequin(mode) && trySpawnMannequin(location)) {
            return;
        }
        spawnArmorStand(location);
    }

    private boolean shouldUseCitizens(String mode) {
        // Citizens only when explicitly requested — auto must not pick it just because
        // the plugin jar is on the server (most servers use the built-in packet NPC).
        return "citizens".equals(mode);
    }

    private boolean shouldUsePlayerNpc(String mode) {
        return switch (mode) {
            case "player", "npc" -> PlayerNpcSupport.isAvailable();
            case "citizens", "mannequin", "armor_stand", "armorstand" -> false;
            // auto: try after Citizens; do not skip just because Citizens is installed.
            default -> PlayerNpcSupport.isAvailable();
        };
    }

    private boolean shouldUseMannequin(String mode) {
        return switch (mode) {
            case "mannequin" -> MannequinSupport.isAvailable();
            case "citizens", "player", "npc", "armor_stand", "armorstand" -> false;
            default -> MannequinSupport.isAvailable();
        };
    }

    private boolean trySpawnPlayerNpc(Location location) {
        BotSkins.Skin skin = BotSkins.cached(resolveSkinName());
        playerNpc = PlayerNpcSupport.spawn(location.getWorld(), location, displayName, skin, armorKit);
        if (playerNpc == null) {
            if (squadIndex == 0) {
                plugin.getLogger().warning("Packet player NPC unavailable for bot \""
                        + displayName + "\": " + PlayerNpcSupport.unavailableReason()
                        + " — falling back to armor stand.");
            }
            return false;
        }
        backend = Backend.PLAYER_NPC;
        lastPos = location.clone();
        // Pull the real skin in the background; swap it in once Mojang responds.
        if (skin == null) {
            String skinName = resolveSkinName();
            BotSkins.resolve(skinName, resolved -> plugin.getServer().getScheduler()
                    .runTask(plugin, () -> applyResolvedSkin(resolved)));
        }
        return true;
    }

    private void applyResolvedSkin(BotSkins.Skin skin) {
        if (backend != Backend.PLAYER_NPC || playerNpc == null || skin == null) {
            return;
        }
        Location at = playerNpc.getLocation();
        if (at == null) {
            at = lastPos;
        }
        if (at == null || at.getWorld() == null) {
            return;
        }
        playerNpc.despawn();
        PlayerNpcSupport.NpcHandle replacement =
                PlayerNpcSupport.spawn(at.getWorld(), at, displayName, skin, armorKit);
        if (replacement != null) {
            playerNpc = replacement;
        }
    }

    private boolean trySpawnCitizens(Location location) {
        if (!CitizensSupport.isAvailable()) {
            return false;
        }
        citizensNpc = CitizensSupport.spawnNpc(
                Text.color(teamColor() + displayName),
                location,
                resolveSkinName());
        if (citizensNpc == null) {
            return false;
        }
        Entity ent = citizensNpc.getEntity();
        if (ent instanceof LivingEntity living) {
            equip(living);
        }
        entity = ent;
        backend = Backend.CITIZENS;
        lastPos = location.clone();
        return entity != null && entity.isValid();
    }

    private boolean trySpawnMannequin(Location location) {
        if (!MannequinSupport.isAvailable()) {
            return false;
        }
        try {
            entity = location.getWorld().spawnEntity(location, MannequinSupport.entityType());
            configureMannequin(entity);
            backend = Backend.MANNEQUIN;
            lastPos = location.clone();
            return true;
        } catch (Exception ignored) {
            entity = null;
            return false;
        }
    }

    private void configureMannequin(Entity spawned) throws ReflectiveOperationException {
        UUID id = UUID.nameUUIDFromBytes(
                ("advancedsoccer-bot-" + team.name() + "-" + squadIndex)
                        .getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Items.profileFromBase64(null, id, displayName);
        spawned.setPersistent(false);

        Class<?> mannequinClass = Class.forName("org.bukkit.entity.Mannequin");
        Method setProfile = findMethod(mannequinClass, "setProfile", PlayerProfile.class);
        if (setProfile == null) {
            setProfile = findMethod(mannequinClass, "setPlayerProfile", PlayerProfile.class);
        }
        if (setProfile != null) {
            setProfile.invoke(spawned, profile);
        }

        if (spawned instanceof LivingEntity living) {
            living.setCustomNameVisible(true);
            living.setCustomName(Text.color(teamColor() + displayName));
            living.setCollidable(false);
            living.setGravity(false);
            living.setSilent(true);
            living.setInvulnerable(true);
            living.setAI(false);
            living.setRemoveWhenFarAway(false);
            equip(living);
        }
    }

    private void spawnArmorStand(Location location) {
        if (squadIndex == 0) {
            String mode = settings.botVisualType == null ? "auto" : settings.botVisualType.toLowerCase();
            plugin.getLogger().warning("Bot visual fallback: armor stand (mode=" + mode
                    + ", citizens=" + CitizensSupport.isAvailable()
                    + ", packetNpc=" + PlayerNpcSupport.isAvailable()
                    + (PlayerNpcSupport.isAvailable() ? "" : ", reason=" + PlayerNpcSupport.unavailableReason())
                    + ", mannequin=" + MannequinSupport.isAvailable()
                    + "). Use Paper 1.20.5+ and bots.visual: player to force packet NPCs.");
        }
        fallback = location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(true);
            stand.setSmall(false);
            stand.setMarker(false);
            stand.setCollidable(false);
            stand.setCustomNameVisible(true);
            stand.setCustomName(Text.color(teamColor() + displayName));
            stand.setCanPickupItems(false);
            equip(stand);
        });
        entity = fallback;
        backend = Backend.ARMOR_STAND;
        lastPos = location.clone();
    }

    private ItemStack[] teamArmor() {
        if (armorOverride != null) {
            return armorOverride;
        }
        Color color = game != null ? game.getTeamKitColor(team)
                : (team == TeamSide.RED ? Color.fromRGB(210, 45, 45) : Color.fromRGB(45, 85, 210));
        return new ItemStack[] {
                dyed(Material.LEATHER_HELMET, color),
                dyed(Material.LEATHER_CHESTPLATE, color),
                dyed(Material.LEATHER_LEGGINGS, color),
                dyed(Material.LEATHER_BOOTS, color)
        };
    }

    private void equip(LivingEntity living) {
        if (armorKit == null) {
            armorKit = teamArmor();
        }
        var eq = living.getEquipment();
        if (eq == null) {
            return;
        }
        eq.setHelmet(armorKit[0]);
        eq.setChestplate(armorKit[1]);
        eq.setLeggings(armorKit[2]);
        eq.setBoots(armorKit[3]);
    }

    private ItemStack dyed(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String resolveSkinName() {
        if (skinOverride != null && !skinOverride.isBlank()) {
            return skinOverride;
        }
        List<String> skins = settings.botSkinNames;
        if (skins == null || skins.isEmpty()) {
            return displayName;
        }
        return skins.get(Math.floorMod(squadIndex, skins.size()));
    }

    private String teamColor() {
        if (game != null) {
            return game.getTeamKitChatColor(team);
        }
        return team == TeamSide.RED ? settings.redChatColor : settings.blueChatColor;
    }

    Location navigate(Location target, boolean sprinting, Vector velocity) {
        if (target == null) {
            return null;
        }
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            float speedMod = sprinting ? 1.0f : 1.0f;
            citizensNpc.navigateTo(target, sprinting, speedMod);
            citizensNpc.applyLocomotion(sprinting, velocity);
            Location synced = citizensNpc.readLocation();
            if (synced != null) {
                lastPos = synced.clone();
            }
            return synced;
        }
        move(target, sprinting, velocity);
        return target;
    }

    Location readSyncedLocation() {
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            Location synced = citizensNpc.readLocation();
            if (synced != null) {
                lastPos = synced.clone();
            }
            return synced;
        }
        if (entity != null && entity.isValid()) {
            Location at = entity.getLocation();
            at.setPitch(0f);
            lastPos = at.clone();
            return at;
        }
        return null;
    }

    void applyFacing(float yaw, boolean sprinting, Vector velocity) {
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            citizensNpc.face(yaw);
            citizensNpc.applyLocomotion(sprinting, velocity);
            return;
        }
        if (backend == Backend.PLAYER_NPC && playerNpc != null) {
            playerNpc.faceYaw(yaw);
            return;
        }
        if (entity == null || !entity.isValid()) {
            return;
        }
        Location at = entity.getLocation().clone();
        at.setYaw(yaw);
        at.setPitch(0f);
        entity.teleport(at);
        if (entity instanceof LivingEntity living) {
            invokeBoolean(living, "setSprinting", sprinting);
            applyVelocity(living, velocity, sprinting);
        }
    }

    void move(Location location, boolean sprinting, Vector velocity) {
        move(location, sprinting, velocity, location != null ? location.getYaw() : 0f, 0f);
    }

    /**
     * Moves the body; {@code headYaw}/{@code headPitch} aim the head independently
     * of the direction of travel (packet NPC and mannequin backends).
     */
    void move(Location location, boolean sprinting, Vector velocity, float headYaw, float headPitch) {
        if (location == null) {
            return;
        }
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            citizensNpc.teleport(location, sprinting, new CitizensSupport.VectorHelper(velocity));
            lastPos = location.clone();
            return;
        }
        if (backend == Backend.PLAYER_NPC && playerNpc != null) {
            playerNpc.update(location, sprinting, headYaw, headPitch);
            lastPos = location.clone();
            return;
        }
        if (entity == null || !entity.isValid()) {
            return;
        }
        Location at = location.clone();
        at.setPitch(backend == Backend.MANNEQUIN ? headPitch : 0f);
        entity.teleport(at);
        if (entity instanceof LivingEntity living) {
            invokeBoolean(living, "setSprinting", sprinting);
            applyVelocity(living, velocity, sprinting);
        }
        lastPos = location.clone();
    }

    private void applyVelocity(LivingEntity living, Vector velocity, boolean sprinting) {
        if (living instanceof ArmorStand) {
            return;
        }
        if (velocity == null) {
            return;
        }
        Vector flat = velocity.clone();
        flat.setY(0);
        if (flat.lengthSquared() < 1.0e-4) {
            return;
        }
        living.setVelocity(flat.multiply(0.35));
    }

    void swingArm() {
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            citizensNpc.swingArm();
            return;
        }
        if (backend == Backend.PLAYER_NPC && playerNpc != null) {
            playerNpc.swing();
            return;
        }
        if (entity instanceof LivingEntity living) {
            try {
                Method swing = living.getClass().getMethod("swingMainHand");
                swing.invoke(living);
            } catch (ReflectiveOperationException ignored) {
            }
            if (living instanceof Player player) {
                player.swingMainHand();
            }
        }
    }

    void applyDivePose() {
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            citizensNpc.applySneak(true);
            return;
        }
        if (backend == Backend.PLAYER_NPC && playerNpc != null) {
            playerNpc.setPose("SWIMMING");
            return;
        }
        if (entity instanceof LivingEntity living) {
            invokeBoolean(living, "setSneaking", true);
            invokeBoolean(living, "setSwimming", true);
        }
    }

    void clearDivePose() {
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            citizensNpc.applySneak(false);
            return;
        }
        if (backend == Backend.PLAYER_NPC && playerNpc != null) {
            playerNpc.setPose("STANDING");
            return;
        }
        if (entity instanceof LivingEntity living) {
            invokeBoolean(living, "setSneaking", false);
            invokeBoolean(living, "setSwimming", false);
        }
    }

    /** Knees bent: keeper's set position, players catching their breath. */
    void applyCrouch(boolean crouch) {
        if (backend == Backend.CITIZENS && citizensNpc != null) {
            citizensNpc.applySneak(crouch);
            return;
        }
        if (backend == Backend.PLAYER_NPC && playerNpc != null) {
            playerNpc.setPose(crouch ? "CROUCHING" : "STANDING");
            return;
        }
        if (entity instanceof LivingEntity living) {
            invokeBoolean(living, "setSneaking", crouch);
        }
    }

    boolean ownsEntity(Entity other) {
        if (other == null) {
            return false;
        }
        if (entity != null && entity.getUniqueId().equals(other.getUniqueId())) {
            return true;
        }
        if (fallback != null && fallback.getUniqueId().equals(other.getUniqueId())) {
            return true;
        }
        if (citizensNpc != null) {
            Entity npcEntity = citizensNpc.getEntity();
            return npcEntity != null && npcEntity.getUniqueId().equals(other.getUniqueId());
        }
        return false;
    }

    void remove() {
        if (citizensNpc != null) {
            citizensNpc.destroy();
            citizensNpc = null;
        }
        if (playerNpc != null) {
            playerNpc.despawn();
            playerNpc = null;
        }
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        entity = null;
        fallback = null;
        lastPos = null;
        backend = Backend.ARMOR_STAND;
    }

    private static void invokeBoolean(LivingEntity living, String method, boolean value) {
        try {
            Method m = living.getClass().getMethod(method, boolean.class);
            m.invoke(living, value);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }
}
