package com.ballgame.soccer.bot;

import java.util.Locale;

/**
 * Predefined 11-a-side shapes. Slot 0 is always the goalkeeper; slots 1–10 are
 * outfield positions with depth (0 = own goal line, ~0.5 = halfway) and lane
 * (-1 = left touchline side, +1 = right).
 */
public enum BotFormation {

    FOUR_FOUR_TWO("4-4-2",
            slot(BotRole.GOALKEEPER, 0.06, 0.0),
            slot(BotRole.DEFENDER, 0.22, -0.82),
            slot(BotRole.DEFENDER, 0.22, -0.28),
            slot(BotRole.DEFENDER, 0.22, 0.28),
            slot(BotRole.DEFENDER, 0.22, 0.82),
            slot(BotRole.MIDFIELDER, 0.38, -0.84),
            slot(BotRole.MIDFIELDER, 0.38, -0.28),
            slot(BotRole.MIDFIELDER, 0.38, 0.28),
            slot(BotRole.MIDFIELDER, 0.38, 0.84),
            slot(BotRole.STRIKER, 0.46, -0.26),
            slot(BotRole.STRIKER, 0.46, 0.26)),

    FOUR_THREE_THREE("4-3-3",
            slot(BotRole.GOALKEEPER, 0.06, 0.0),
            slot(BotRole.DEFENDER, 0.22, -0.82),
            slot(BotRole.DEFENDER, 0.22, -0.28),
            slot(BotRole.DEFENDER, 0.22, 0.28),
            slot(BotRole.DEFENDER, 0.22, 0.82),
            slot(BotRole.MIDFIELDER, 0.36, -0.35),
            slot(BotRole.PLAYMAKER, 0.38, 0.0),
            slot(BotRole.MIDFIELDER, 0.36, 0.35),
            slot(BotRole.WINGER, 0.48, -0.78),
            slot(BotRole.STRIKER, 0.50, 0.0),
            slot(BotRole.WINGER, 0.48, 0.78)),

    FOUR_TWO_THREE_ONE("4-2-3-1",
            slot(BotRole.GOALKEEPER, 0.06, 0.0),
            slot(BotRole.DEFENDER, 0.22, -0.82),
            slot(BotRole.DEFENDER, 0.22, -0.28),
            slot(BotRole.DEFENDER, 0.22, 0.28),
            slot(BotRole.DEFENDER, 0.22, 0.82),
            slot(BotRole.MIDFIELDER, 0.32, -0.30),
            slot(BotRole.MIDFIELDER, 0.32, 0.30),
            slot(BotRole.WINGER, 0.44, -0.78),
            slot(BotRole.PLAYMAKER, 0.44, 0.0),
            slot(BotRole.WINGER, 0.44, 0.78),
            slot(BotRole.STRIKER, 0.50, 0.0)),

    FOUR_TWO_TWO_TWO("4-2-2-2",
            slot(BotRole.GOALKEEPER, 0.06, 0.0),
            slot(BotRole.DEFENDER, 0.22, -0.82),
            slot(BotRole.DEFENDER, 0.22, -0.28),
            slot(BotRole.DEFENDER, 0.22, 0.28),
            slot(BotRole.DEFENDER, 0.22, 0.82),
            slot(BotRole.MIDFIELDER, 0.32, -0.32),
            slot(BotRole.MIDFIELDER, 0.32, 0.32),
            slot(BotRole.WINGER, 0.42, -0.55),
            slot(BotRole.WINGER, 0.42, 0.55),
            slot(BotRole.STRIKER, 0.48, -0.28),
            slot(BotRole.STRIKER, 0.48, 0.28)),

    THREE_FIVE_TWO("3-5-2",
            slot(BotRole.GOALKEEPER, 0.06, 0.0),
            slot(BotRole.DEFENDER, 0.22, -0.55),
            slot(BotRole.DEFENDER, 0.22, 0.0),
            slot(BotRole.DEFENDER, 0.22, 0.55),
            slot(BotRole.WINGER, 0.36, -0.88),
            slot(BotRole.MIDFIELDER, 0.36, -0.35),
            slot(BotRole.PLAYMAKER, 0.38, 0.0),
            slot(BotRole.MIDFIELDER, 0.36, 0.35),
            slot(BotRole.WINGER, 0.36, 0.88),
            slot(BotRole.STRIKER, 0.48, -0.28),
            slot(BotRole.STRIKER, 0.48, 0.28)),

    FIVE_THREE_TWO("5-3-2",
            slot(BotRole.GOALKEEPER, 0.06, 0.0),
            slot(BotRole.DEFENDER, 0.22, -0.88),
            slot(BotRole.DEFENDER, 0.22, -0.42),
            slot(BotRole.DEFENDER, 0.22, 0.0),
            slot(BotRole.DEFENDER, 0.22, 0.42),
            slot(BotRole.DEFENDER, 0.22, 0.88),
            slot(BotRole.MIDFIELDER, 0.36, -0.35),
            slot(BotRole.PLAYMAKER, 0.38, 0.0),
            slot(BotRole.MIDFIELDER, 0.36, 0.35),
            slot(BotRole.STRIKER, 0.48, -0.28),
            slot(BotRole.STRIKER, 0.48, 0.28));

    public static final int MAX_SQUAD = 11;

    private final String displayName;
    private final FormationSlot[] slots;

    BotFormation(String displayName, FormationSlot... slots) {
        this.displayName = displayName;
        this.slots = slots;
    }

    public String displayName() {
        return displayName;
    }

    public String langKey() {
        return "formation-" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public FormationSlot slot(int index) {
        if (index < 0 || index >= slots.length) {
            return slots[Math.min(Math.max(0, index), slots.length - 1)];
        }
        return slots[index];
    }

    public int strikerSlotIndex() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].role() == BotRole.STRIKER) {
                return i;
            }
        }
        return slots.length - 1;
    }

    /**
     * Maps a per-team bot squad index (0 = GK) to a formation slot index.
     * When {@code reserveStrikerForHuman} is true, the main striker slot is left
     * for the human player.
     */
    public int formationSlotForSquadIndex(int squadIndex, boolean reserveStrikerForHuman) {
        if (squadIndex <= 0) {
            return 0;
        }
        int strikerSlot = strikerSlotIndex();
        int outfieldNeeded = squadIndex;
        int found = 0;
        for (int fi = 1; fi < slots.length; fi++) {
            if (reserveStrikerForHuman && fi == strikerSlot) {
                continue;
            }
            found++;
            if (found == outfieldNeeded) {
                return fi;
            }
        }
        return Math.min(squadIndex, slots.length - 1);
    }

    public BotRole roleForSquadIndex(int squadIndex, boolean reserveStrikerForHuman) {
        return slot(formationSlotForSquadIndex(squadIndex, reserveStrikerForHuman)).role();
    }

    public BotFormation next() {
        BotFormation[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public BotFormation previous() {
        BotFormation[] all = values();
        return all[(ordinal() + all.length - 1) % all.length];
    }

    private static FormationSlot slot(BotRole role, double depth, double lane) {
        return new FormationSlot(role, depth, lane);
    }

    public record FormationSlot(BotRole role, double depth, double lane) {
    }
}
