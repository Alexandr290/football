package com.ballgame.soccer.fifa;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable league descriptor used by the club catalog.
 */
public final class FifaLeague {

    private final String code;
    private final String name;
    private final String nameRu;

    public FifaLeague(String code, String name, String nameRu) {
        this.code = Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
        this.name = Objects.requireNonNull(name, "name").trim();
        this.nameRu = nameRu != null && !nameRu.isBlank() ? nameRu.trim() : this.name;
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

    @Override
    public boolean equals(Object other) {
        return other instanceof FifaLeague league && code.equals(league.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }

    @Override
    public String toString() {
        return code + " (" + name + ")";
    }
}
