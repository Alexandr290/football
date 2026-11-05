package com.ballgame.soccer.fifa;

public final class FifaPlayer {

    private final String name;
    private final int number;
    private final FifaPosition position;
    private final boolean starter;
    private final String skin;

    public FifaPlayer(String name, int number, FifaPosition position, boolean starter, String skin) {
        this.name = name;
        this.number = number;
        this.position = position != null ? position : FifaPosition.MF;
        this.starter = starter;
        this.skin = skin;
    }

    public String getName() {
        return name;
    }

    public int getNumber() {
        return number;
    }

    public FifaPosition getPosition() {
        return position;
    }

    public boolean isStarter() {
        return starter;
    }

    public String getSkin() {
        return skin;
    }

    public String displayLabel() {
        if (number > 0) {
            return "#" + number + " " + name;
        }
        return name;
    }
}
