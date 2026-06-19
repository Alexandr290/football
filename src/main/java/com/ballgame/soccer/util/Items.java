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
     * Build a player head from a Base64-encoded texture property. The Base64
     * decodes to JSON containing a texture URL, which we feed into the modern
     * {@link PlayerProfile} API.
     *
     * @return a textured head, or a plain head if decoding fails.
     */
    public static ItemStack headFromBase64(String base64) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (base64 == null || base64.isEmpty()) {
            return head;
        }
        try {
            String json = new String(Base64.getDecoder().decode(base64));
            Matcher matcher = URL_PATTERN.matcher(json);
            if (!matcher.find()) {
                return head;
            }
            URL url = new URL(matcher.group(1));
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(url);
            profile.setTextures(textures);

            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwnerProfile(profile);
                head.setItemMeta(meta);
            }
        } catch (Exception ignored) {
            // Fall back to a plain head if anything goes wrong.
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
