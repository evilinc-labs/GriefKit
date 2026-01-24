package com.griefkit.helpers;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

import java.util.function.Predicate;

// ** Helper methods for managing hotbar item supply and replenishment.
// 1.21.8 tested only, sorry hackware :L
public final class HotbarSupply {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int DEFAULT_THRESHOLD = 32;

    private HotbarSupply() {}

    /**
     * Ensure a stack that matches `matcher` exists in hotbar; if its count < threshold,
     * replace it with a full (or largest) stack from inventory.
     *
     * @param matcher          predicate to match target items (e.g., a specific BlockItem)
     * @param refillThreshold  when current hotbar count < this, pull a full / largest stack
     * @param selectSlot       if true, switch held item to the matched hotbar slot
     * @return hotbar slot [0..8], or -1 if none found anywhere
     */
    public static int ensureHotbarStack(Predicate<ItemStack> matcher, int refillThreshold, boolean selectSlot) {
        if (mc.player == null) return -1;
        var inv = mc.player.getInventory();

        // Find in hotbar
        FindItemResult inHotbar = InvUtils.findInHotbar(matcher);

        // Move best stack from main inventory into hotbar.
        if (!inHotbar.found()) {
            int bestInv = findBestInventoryStack(matcher);
            if (bestInv == -1) return -1;

            int dest = findEmptyHotbarSlot();
            if (dest == -1) dest = findSmallestHotbarStack(matcher);
            if (dest == -1) dest = inv.getSelectedSlot();

            InvUtils.move().from(bestInv).toHotbar(dest);
            if (selectSlot) inv.setSelectedSlot(dest);
            return dest;
        }

        int slot = inHotbar.slot();
        int count = inv.getStack(slot).getCount();

        // Refill if low (prefer full stack; else largest)
        if (count < refillThreshold) {
            int bestInv = findBestInventoryStack(matcher);
            if (bestInv != -1) {
                InvUtils.move().from(bestInv).to(slot);
            }
        }

        if (selectSlot) inv.setSelectedSlot(slot);
        return slot;
    }

    /**
     * Overload with default threshold of 32.
     * 
     */
    public static int ensureHotbarStack(Predicate<ItemStack> matcher, boolean selectSlot) {
        return ensureHotbarStack(matcher, DEFAULT_THRESHOLD, selectSlot);
    }

    /**
     * Check if replenishment is possible for a given block.
     * Returns true if the block exists somewhere in inventory (hotbar or main inventory).
     *
     * @param matcher predicate to match target items
     * @return true if block is available, false otherwise
     */
    public static boolean canReplenish(Predicate<ItemStack> matcher) {
        if (mc.player == null) return false;

        // Check hotbar
        FindItemResult inHotbar = InvUtils.findInHotbar(matcher);
        if (inHotbar.found()) return true;

        // Check main inventory
        return findBestInventoryStack(matcher) != -1;
    }

    /**
     * Check if replenishment is possible and count meets threshold.
     *
     * @param matcher          predicate to match target items
     * @param refillThreshold  minimum count required
     * @return true if total count >= threshold, false otherwise
     */
    public static boolean canReplenish(Predicate<ItemStack> matcher, int refillThreshold) {
        if (mc.player == null) return false;
        
        int totalCount = getTotalCount(matcher);
        return totalCount >= refillThreshold;
    }

    /**
     * Overload with default threshold of 32.
     */
    public static boolean canReplenish(net.minecraft.block.Block block) {
        return canReplenish(blockIs(block), DEFAULT_THRESHOLD);
    }

    /**
     * Check if replenishment is possible for a specific block with custom threshold.
     */
    public static boolean canReplenish(net.minecraft.block.Block block, int refillThreshold) {
        return canReplenish(blockIs(block), refillThreshold);
    }

    /**
     * Get total count of matching items across hotbar and inventory.
     *
     * @param matcher predicate to match target items
     * @return total count of matching items
     */
    public static int getTotalCount(Predicate<ItemStack> matcher) {
        if (mc.player == null) return 0;
        
        var inv = mc.player.getInventory();
        int total = 0;

        // Count in hotbar (0-8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (matcher.test(stack)) {
                total += stack.getCount();
            }
        }

        // Count in main inventory (9-35)
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = inv.getStack(i);
            if (matcher.test(stack)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    /**
     * Get total count for a specific block.
     */
    public static int getTotalCount(net.minecraft.block.Block block) {
        return getTotalCount(blockIs(block));
    }

    /** Convenience predicate for a specific Block. */
    public static Predicate<ItemStack> blockIs(net.minecraft.block.Block block) {
        return s -> !s.isEmpty() && s.getItem() instanceof BlockItem bi && bi.getBlock() == block;
    }

    // ----- internals -----
    private static int findEmptyHotbarSlot() {
        if (mc.player == null) return -1;
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getStack(i).isEmpty()) return i;
        return -1;
    }

    private static int findSmallestHotbarStack(Predicate<ItemStack> matcher) {
        if (mc.player == null) return -1;
        var inv = mc.player.getInventory();
        int slot = -1, min = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (matcher.test(s)) {
                int c = s.getCount();
                if (c < min) { min = c; slot = i; }
            }
        }
        return slot;
    }

    /** Search main inventory (9..35) for full stack first, else largest. */
    private static int findBestInventoryStack(Predicate<ItemStack> matcher) {
        if (mc.player == null) return -1;
        var inv = mc.player.getInventory();
        int best = -1, bestCount = -1;
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack s = inv.getStack(slot);
            if (!s.isEmpty() && matcher.test(s)) {
                int c = s.getCount();
                if (c == s.getMaxCount()) return slot; // full stack wins
                if (c > bestCount) { bestCount = c; best = slot; }
            }
        }
        return best;
    }
}