package com.ballgame.soccer.fifa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FifaNation {

    private final String code;
    private final String name;
    private final String nameRu;
    private final String confederation;
    private final int fifaRank;
    private final String wc2026Group;
    private final String primaryColor;
    private final String secondaryColor;
    private final List<FifaPlayer> squad;

    public FifaNation(String code, String name, String nameRu, String confederation,
                      int fifaRank, String wc2026Group,
                      String primaryColor, String secondaryColor,
                      List<FifaPlayer> squad) {
        this.code = code;
        this.name = name;
        this.nameRu = nameRu != null && !nameRu.isBlank() ? nameRu : name;
        this.confederation = confederation;
        this.fifaRank = fifaRank;
        this.wc2026Group = wc2026Group;
        this.primaryColor = primaryColor;
        this.secondaryColor = secondaryColor;
        this.squad = Collections.unmodifiableList(new ArrayList<>(squad));
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

    public String getConfederation() {
        return confederation;
    }

    public int getFifaRank() {
        return fifaRank;
    }

    public String getWc2026Group() {
        return wc2026Group;
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

    /** Starting XI in stable slot order (0 = GK, then outfield). */
    public List<FifaPlayer> startingXi() {
        List<FifaPlayer> starters = new ArrayList<>();
        FifaPlayer gk = null;
        List<FifaPlayer> outfield = new ArrayList<>();
        for (FifaPlayer player : squad) {
            if (!player.isStarter()) {
                continue;
            }
            if (player.getPosition() == FifaPosition.GK) {
                if (gk == null) {
                    gk = player;
                }
            } else {
                outfield.add(player);
            }
        }
        if (gk == null) {
            for (FifaPlayer player : squad) {
                if (player.getPosition() == FifaPosition.GK) {
                    gk = player;
                    break;
                }
            }
        }
        if (gk != null) {
            starters.add(gk);
        }
        starters.addAll(outfield);
        while (starters.size() < 11 && starters.size() < squad.size()) {
            for (FifaPlayer player : squad) {
                if (!starters.contains(player)) {
                    starters.add(player);
                    if (starters.size() >= 11) {
                        break;
                    }
                }
            }
            break;
        }
        if (starters.size() > 11) {
            return starters.subList(0, 11);
        }
        return starters;
    }

    public FifaPlayer starterAt(int slotIndex) {
        List<FifaPlayer> xi = startingXi();
        if (slotIndex < 0 || slotIndex >= xi.size()) {
            return null;
        }
        return xi.get(slotIndex);
    }
}
