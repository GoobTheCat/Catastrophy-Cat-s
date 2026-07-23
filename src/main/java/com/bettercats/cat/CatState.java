package com.bettercats.cat;

import java.util.UUID;

/**
 * Per-cat runtime combat state. Lives only in memory - if the server restarts
 * mid-fight, cats just settle back into normal vanilla behavior, which is the
 * right call (nobody wants a cat still furiously charging a target that
 * might not even be loaded yet).
 */
public final class CatState {

    /** Who this cat is currently chasing/attacking, or null if none. */
    public UUID targetId;

    /** Wall-clock timestamp (millis) - this cat can't attack again until then. */
    public long nextAttackReadyAtMillis = 0L;

    /** Counts up each landed hit; every 3rd one is a pounce instead of a normal attack. */
    public int hitCounter = 0;

    /** Last time we re-issued a pathfinding move order, so we don't spam it every tick. */
    public long lastPathUpdateMillis = 0L;
}
