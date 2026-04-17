package com.griefkit.helpers.gooner;

/**
 * Global mining state — mirrors Meteor's BlockUtils.breaking pattern.
 * Checked by mixins every tick — must be cheap (no reflection).
 */
public final class BaritoneState {
    private BaritoneState() {}

    /**
     * When true, vanilla cancelBlockBreaking() is suppressed.
     * This prevents mining progress from resetting when Baritone moves the player.
     * Mirrors Meteor's BlockUtils.breaking flag exactly.
     *
     * Set to true by: vanilla attackBlock (via mixin) when mining starts
     * Set to false by: tick post-event when no mining happened this tick
     */
    public static volatile boolean breaking = false;
    public static volatile boolean breakingThisTick = false;

    /**
     * Cached isBaritonePathing() result. Refreshed once per tick at tick HEAD
     * ONLY when {@link #needsPathingCheck} is true. When false, zero reflection runs.
     * All mixins read this cheap boolean instead of doing reflection.
     */
    public static volatile boolean pathing = false;

    /**
     * Set true by BaritoneInterface when a ground pathing process starts
     * (setGoal, setGoalGetTo, setGoalNear, setGoalXZ).
     * Cleared by BaritoneInterface.cancelAll().
     * NOT set by elytra — elytra doesn't need mixin bypasses.
     */
    public static volatile boolean needsPathingCheck = false;

    /**
     * General flag: true when ANY module is using Baritone for ground walking.
     * Set by ElytraBot (WALKING_FINAL), HighwayGoonerV2 (stepRelative), etc.
     * Read by PacketMineInteractionMixin (skip SpeedMine, suppress cancelBlockBreaking)
     * and AutoArmor (suppress double-jump elytra equip).
     */
    public static volatile boolean groundWalking = false;
}
