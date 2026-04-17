package com.griefkit.helpers.gooner;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

/**
 * Lightweight runtime statistics handler.
 * Tracks distance, placements/sec, ETA, obsidian usage.
 * All tracking ONLY runs when connected to a 2b2t server.
 */
public class StatsHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static long tickCount = 0;
    private static long blocksPlaced = 0;
    private static long obbyPlaced = 0;
    private static BlockPos lastPlayerPos = null;
    private static double distanceTravelled = 0.0;

    private static long lifetimeBlocksPlaced = 0;
    private static long lifetimeObbyPlaced = 0;
    private static double lifetimeDistanceTravelled = 0.0;

    private static int obsidianCount = 0;
    private static int startingObsidian = -1;

    private static boolean isOn2b2t() {
        if (mc == null) return false;
        if (mc.isInSingleplayer()) return false;
        ServerInfo entry = mc.getCurrentServerEntry();
        if (entry == null) return false;
        String address = entry.address;
        if (address == null) return false;
        return address.toLowerCase().contains("2b2t");
    }

    public static void onTick() {
        if (!isOn2b2t()) return;
        tickCount++;
        ClientPlayerEntity player = mc.player;
        if (player == null) return;
        BlockPos current = player.getBlockPos();
        if (lastPlayerPos != null) {
            int step = current.getManhattanDistance(lastPlayerPos);
            distanceTravelled += step;
            lifetimeDistanceTravelled += step;
        }
        lastPlayerPos = current;
        updateObsidian(player);
    }

    public static void recordPlacement(Block blockPlaced) {
        if (!isOn2b2t()) return;
        blocksPlaced++;
        lifetimeBlocksPlaced++;
        if (blockPlaced == Blocks.OBSIDIAN) {
            obbyPlaced++;
            lifetimeObbyPlaced++;
        }
    }

    private static void updateObsidian(ClientPlayerEntity player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.OBSIDIAN) {
                total += stack.getCount();
            }
        }
        obsidianCount = total;
        if (startingObsidian < 0) startingObsidian = total;
    }

    public static int getObsidianCount() { return obsidianCount; }
    public static int getObsidianUsedFromInv() { return startingObsidian < 0 ? 0 : startingObsidian - obsidianCount; }
    public static long getBlocksPlaced() { return blocksPlaced; }
    public static long getObsidianBlocksPlaced() { return obbyPlaced; }
    public static long getLifetimeBlocksPlaced() { return lifetimeBlocksPlaced; }
    public static long getLifetimeObsidianBlocksPlaced() { return lifetimeObbyPlaced; }
    public static double getDistanceTravelled() { return distanceTravelled; }
    public static double getLifetimeDistanceTravelled() { return lifetimeDistanceTravelled; }

    public static double getPlacementsPerSecond() {
        if (tickCount == 0) return 0.0;
        return blocksPlaced / (tickCount / 20.0);
    }

    public static long getTicksPassed() { return tickCount; }

    public static long getETA(double totalDistance) {
        double remaining = Math.max(0, totalDistance - distanceTravelled);
        double speed = getPlacementsPerSecond();
        if (speed <= 0.0) return -1;
        return (long) ((remaining / speed) * 20);
    }

    public static String formatTime(long ticks) {
        if (ticks < 0) return "Calculating...";
        long totalSeconds = ticks / 20;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d:%02d", hours, minutes, seconds);
    }

    public static void reset() {
        tickCount = 0;
        blocksPlaced = 0;
        obbyPlaced = 0;
        distanceTravelled = 0.0;
        lastPlayerPos = null;
        obsidianCount = 0;
        startingObsidian = -1;
    }

    public static void hardResetAll() {
        reset();
        lifetimeBlocksPlaced = 0;
        lifetimeObbyPlaced = 0;
        lifetimeDistanceTravelled = 0.0;
    }
}
