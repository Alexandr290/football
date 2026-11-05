package com.ballgame.soccer.bot;

import com.ballgame.soccer.config.SoccerSettings;

import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Per-bot physical and personality traits. Each bot is seeded deterministically
 * from its identity so it behaves like a distinct NPC with its own pace, stamina
 * reserves, reaction time and decision-making temperament — instead of every bot
 * being a perfect clone. Role nudges the baselines so a striker is naturally
 * quicker and a defender naturally more disciplined.
 */
final class BotAttributes {

    /** Multiplier on top movement speed (walk and sprint). */
    final double topSpeedMul;
    /** Multiplier on acceleration/braking responsiveness. */
    final double accelMul;
    /** How sharply this bot can change direction (turn agility). */
    final double agility;
    /** Maximum stamina pool. */
    final double maxStamina;
    /** Stamina recovered per tick while not sprinting. */
    final double staminaRegen;
    /** Stamina burned per tick while sprinting. */
    final double sprintDrain;
    /** Ticks between AI re-evaluations — the bot's reaction lag. */
    final int reactionTicks;
    /** 0..1 finishing quality: higher = cleaner first touch and shots. */
    final double composure;
    /** 0..1 pressing/tackling eagerness. */
    final double aggression;
    /** 0..1 willingness to make tiring off-ball sprints. */
    final double workRate;
    /** 0..1 anticipation — how far ahead of the ball it reads the play. */
    final double anticipation;

    private BotAttributes(double topSpeedMul, double accelMul, double agility, double maxStamina,
                          double staminaRegen, double sprintDrain, int reactionTicks,
                          double composure, double aggression, double workRate, double anticipation) {
        this.topSpeedMul = topSpeedMul;
        this.accelMul = accelMul;
        this.agility = agility;
        this.maxStamina = maxStamina;
        this.staminaRegen = staminaRegen;
        this.sprintDrain = sprintDrain;
        this.reactionTicks = reactionTicks;
        this.composure = composure;
        this.aggression = aggression;
        this.workRate = workRate;
        this.anticipation = anticipation;
    }

    static BotAttributes generate(UUID id, BotRole role, SoccerSettings settings) {
        long seed = id.getMostSignificantBits() ^ id.getLeastSignificantBits()
                ^ ((long) role.ordinal() * 0x9E3779B97F4A7C15L);
        RandomGenerator rng = RandomGeneratorFactory.getDefault().create(seed);

        double baseMaxStamina = settings != null && settings.botMaxStamina > 0
                ? settings.botMaxStamina : 100.0;
        double baseRegen = settings != null && settings.botStaminaRegen > 0
                ? settings.botStaminaRegen : 0.65;
        double baseDrain = settings != null && settings.botSprintDrain > 0
                ? settings.botSprintDrain : 0.5;

        double topSpeed = lerp(0.94, 1.02, rng.nextDouble());
        double accel = lerp(1.0, 1.26, rng.nextDouble());
        double agility = lerp(0.88, 1.16, rng.nextDouble());
        double stamina = baseMaxStamina * lerp(0.88, 1.12, rng.nextDouble());
        double regen = baseRegen * lerp(0.85, 1.15, rng.nextDouble());
        double drain = baseDrain * lerp(0.9, 1.1, rng.nextDouble());
        int reaction = 1 + rng.nextInt(2); // 1..2 ticks
        double composure = lerp(0.55, 0.92, rng.nextDouble());
        double aggression = lerp(0.4, 0.85, rng.nextDouble());
        double workRate = lerp(0.5, 0.92, rng.nextDouble());
        double anticipation = lerp(0.55, 0.92, rng.nextDouble());

        switch (role) {
            case GOALKEEPER -> {
                anticipation += 0.16;
                composure += 0.10;
                reaction = 1;
                workRate -= 0.1;
            }
            case STRIKER -> {
                topSpeed += 0.01;
                composure += 0.10;
                anticipation += 0.06;
                workRate += 0.05;
                reaction = 1;
            }
            case WINGER -> {
                topSpeed += 0.015;
                agility += 0.05;
                anticipation += 0.05;
                workRate += 0.08;
                stamina *= 1.05;
                reaction = 1;
            }
            case MIDFIELDER -> {
                stamina *= 1.1;
                regen *= 1.08;
                anticipation += 0.10;
                composure += 0.08;
                workRate += 0.1;
                reaction = 1;
            }
            case PLAYMAKER -> {
                composure += 0.14;
                anticipation += 0.14;
                accel += 0.04;
                reaction = 1;
            }
            case DEFENDER -> {
                aggression += 0.1;
                anticipation += 0.10;
                composure += 0.06;
                stamina *= 1.05;
                reaction = 1;
            }
            default -> {
            }
        }

        return new BotAttributes(
                clamp(topSpeed, 0.94, 1.16),
                clamp(accel, 0.95, 1.34),
                clamp(agility, 0.85, 1.25),
                Math.max(40.0, stamina),
                Math.max(0.2, regen),
                Math.max(0.2, drain),
                reaction,
                clamp01(composure),
                clamp01(aggression),
                clamp01(workRate),
                clamp01(anticipation));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }
}
