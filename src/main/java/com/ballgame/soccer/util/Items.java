package com.ballgame.soccer.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Item construction helpers, including building a custom-textured player head
 * from a Base64 texture value using only the public Bukkit profile API
 * (no NMS / reflection required on 1.18.1+).
 */
public final class Items {

    private static final Pattern URL_PATTERN =
            Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"");

    private Items() {
    }

    public static ItemStack named(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.setDisplayName(Text.color(name));
            }
            if (lore != null && lore.length > 0) {
                List<String> colored = new ArrayList<>();
                for (String line : lore) {
                    colored.add(Text.color(line));
                }
                meta.setLore(colored);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Build a {@link PlayerProfile} from a Base64 skin property (same format as
     * skull textures). Returns a named profile with no custom skin if decoding fails.
     */
    public static PlayerProfile profileFromBase64(String base64, UUID id, String name) {
        PlayerProfile profile = Bukkit.createPlayerProfile(id, name);
        if (base64 == null || base64.isEmpty()) {
            return profile;
        }
        try {
            String json = new String(Base64.getDecoder().decode(base64));
            Matcher matcher = URL_PATTERN.matcher(json);
            if (!matcher.find()) {
                return profile;
            }
            URL url = new URL(matcher.group(1));
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(url);
            profile.setTextures(textures);
        } catch (Exception ignored) {
            // Plain Steve/Alex profile.
        }
        return profile;
    }

    public static ItemStack headFromBase64(String base64) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (base64 == null || base64.isEmpty()) {
            return head;
        }
        PlayerProfile profile = profileFromBase64(base64, UUID.randomUUID(), null);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwnerProfile(profile);
            head.setItemMeta(meta);
        }
        return head;
    }

    public static ItemStack of(Material material) {
        return new ItemStack(material);
    }

    public static List<String> lore(String... lines) {
        List<String> list = new ArrayList<>();
        for (String line : Arrays.asList(lines)) {
            list.add(Text.color(line));
        }
        return list;
    }
}
