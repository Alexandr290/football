package com.ballgame.soccer.fifa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable club entry with a stable starting lineup.
 */
public final class FifaClub {

    private final String code;
    private final String name;
    private final String nameRu;
    private final FifaLeague league;
    private final String primaryColor;
    private final String secondaryColor;
    private final List<FifaPlayer> squad;

    public FifaClub(String code, String name, String nameRu, FifaLeague league,
                    String primaryColor, String secondaryColor, List<FifaPlayer> squad) {
        this.code = Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
        this.name = Objects.requireNonNull(name, "name").trim();
        this.nameRu = nameRu != null && !nameRu.isBlank() ? nameRu.trim() : this.name;
        this.league = Objects.requireNonNull(league, "league");
        this.primaryColor = Objects.requireNonNull(primaryColor, "primaryColor").trim();
        this.secondaryColor = Objects.requireNonNull(secondaryColor, "secondaryColor").trim();
        this.squad = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(squad, "squad")));
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getNameRu() {
        return nameRu;
    }

    public String localizedName(boolean russian) {
        return russian ? nameRu : name;
    }

    public FifaLeague getLeague() {
        return league;
    }

    public String getLeagueCode() {
        return league.getCode();
    }

    public String getLeagueName() {
        return league.getName();
    }

    public String getLeagueNameRu() {
        return league.getNameRu();
    }

    public String getPrimaryColor() {
        return primaryColor;
    }

    public String getSecondaryColor() {
        return secondaryColor;
    }

    public List<FifaPlayer> getSquad() {
        return squad;
    }

    /**
     * Returns one goalkeeper followed by ten outfield starters in source order.
     */
    public List<FifaPlayer> startingXi() {
        FifaPlayer goalkeeper = null;
        List<FifaPlayer> outfield = new ArrayList<>(10);
        for (FifaPlayer player : squad) {
            if (!player.isStarter()) {
                continue;
            }
            if (player.getPosition() == FifaPosition.GK) {
                if (goalkeeper == null) {
                    goalkeeper = player;
                }
            } else if (outfield.size() < 10) {
                outfield.add(player);
            }
        }
        if (goalkeeper == null || outfield.size() < 10) {
            return Collections.emptyList();
        }
        List<FifaPlayer> result = new ArrayList<>(11);
        result.add(goalkeeper);
        result.addAll(outfield);
        return Collections.unmodifiableList(result);
    }

    public FifaPlayer starterAt(int slotIndex) {
        List<FifaPlayer> starters = startingXi();
        return slotIndex >= 0 && slotIndex < starters.size() ? starters.get(slotIndex) : null;
    }
}
