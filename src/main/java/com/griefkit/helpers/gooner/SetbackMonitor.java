package com.griefkit.helpers.gooner;

import net.minecraft.client.MinecraftClient;

public final class SetbackMonitor {

    private static final double SETBACK_THRESHOLD_BLOCKS = 0.6;

    private static final int CALM_WINDOW_TICKS = 12;

    private static final int HISTORY_SIZE = 64;

    private static final double STATIONARY_DELTA_BLOCKS = 0.025;

    private boolean primed;
    private double  lastX, lastY, lastZ;

    private int  ticksSinceSetback = CALM_WINDOW_TICKS;
    private int  stationaryTicks;
    private int  totalSetbacks;

    private final long[] setbackTicks = new long[HISTORY_SIZE];
    private int  historyHead;
    private long currentTick;

    private static final SetbackMonitor INSTANCE = new SetbackMonitor();

    public static SetbackMonitor get() { return INSTANCE; }

    private SetbackMonitor() {}

    public void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            primed = false;
            ticksSinceSetback = CALM_WINDOW_TICKS;
            return;
        }

        currentTick++;
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();

        if (!primed) {
            lastX = x; lastY = y; lastZ = z;
            primed = true;
            return;
        }

        double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        double stationarySq = STATIONARY_DELTA_BLOCKS * STATIONARY_DELTA_BLOCKS;
        lastX = x; lastY = y; lastZ = z;

        if (distSq <= stationarySq) {
            stationaryTicks++;
        } else {
            stationaryTicks = 0;
        }

        if (distSq > SETBACK_THRESHOLD_BLOCKS * SETBACK_THRESHOLD_BLOCKS) {
            ticksSinceSetback = 0;
            totalSetbacks++;
            setbackTicks[historyHead] = currentTick;
            historyHead = (historyHead + 1) % HISTORY_SIZE;
        } else if (ticksSinceSetback < CALM_WINDOW_TICKS) {
            ticksSinceSetback++;
        }
    }

    public boolean isCalm() { return ticksSinceSetback >= CALM_WINDOW_TICKS; }

    public int ticksSinceSetback() { return ticksSinceSetback; }

    public int recentSetbackCount(int windowTicks) {
        if (windowTicks <= 0) return 0;
        long cutoff = currentTick - windowTicks;
        int count = 0;
        for (long t : setbackTicks) {
            if (t > cutoff && t > 0) count++;
        }
        return count;
    }

    public int totalSetbacks() { return totalSetbacks; }

    public boolean isStationaryFor(int minTicks) {
        return stationaryTicks >= minTicks;
    }

    public void reset() {
        primed = false;
        ticksSinceSetback = CALM_WINDOW_TICKS;
        totalSetbacks = 0;
        stationaryTicks = 0;
        currentTick = 0;
        historyHead = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) setbackTicks[i] = 0;
    }
}
