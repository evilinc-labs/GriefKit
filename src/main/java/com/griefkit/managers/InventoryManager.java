package com.griefkit.managers;

import java.util.function.Predicate;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * InventoryManager
 *
 * Purpose:
 * - Utility helpers for selecting hotbar slots + checking for required items.
 * - Maintains a simple "slot change packet resend throttler" so slot selection
 *   changes are reliably communicated even if packets are dropped / delayed.
 * - Provides a synchronized "offhand swap lock" that ensures only one routine
 *   does SWAP_ITEM_WITH_OFFHAND at a time, and automatically recovers from a
 *   stuck/forgotten swap after OFFHAND_SWAP_STALE_MS.
 *
 * Important behavior notes:
 * - beginOffhandSwap() sends SWAP_ITEM_WITH_OFFHAND and sets offhandSwapped=true
 * - endOffhandSwap() sends SWAP_ITEM_WITH_OFFHAND again to revert and clears the flag
 * - offhandSwapped here is purely your *local* state; it does not prove the server
 *   accepted the swap. It’s used as a guard to avoid double-swapping.
 */
public class InventoryManager {
    /**
     * Mutex protecting offhandSwapped/offhandSwapStartedMs so that concurrent code
     * doesn't interleave swaps (which would desync your assumptions about what's in hand).
     */
    private final Object offhandLock = new Object();

    /** Whether we believe we've swapped items between mainhand and offhand. */
    private boolean offhandSwapped = false;

    /** When we started the current swap session (for stale recovery). */
    private long offhandSwapStartedMs = 0L;

    /**
     * If a swap stays "active" longer than this, we assume something went wrong
     * (e.g., code forgot to call endOffhandSwap) and allow a new swap session.
     */
    private static final long OFFHAND_SWAP_STALE_MS = 1500L;

    /**
     * Slot resend tracking:
     * Some servers/clients can be finicky about slot packets, so this lets you resend
     * if:
     * - we changed the slot locally, OR
     * - we haven't sent this slot recently, OR
     * - enough time has passed that a resend is useful.
     */
    private int lastSentSlot = -1;
    private long lastSentSlotMs = 0L;

    /** Cooldown before we consider resending the selected slot packet. */
    private static final long RESEND_SLOT_AFTER_MS = 400L;

    /**
     * Finds the first hotbar slot [0..8] whose Item matches the predicate.
     * Returns -1 if not found.
     */
    public int findHotbarSlot(PlayerInventory inv, Predicate<Item> match) {
        for (int i = 0; i < 9; ++i) {
            Item item = inv.getStack(i).getItem();
            if (!match.test(item)) continue;
            return i;
        }
        return -1;
    }

    /**
     * Finds the first hotbar slot [0..8] whose stack:
     * - is not empty
     * - has at least minCount
     * - and whose Item matches the predicate
     *
     * Returns -1 if no slot qualifies.
     */
    public int findHotbarSlotWithCountAtLeast(PlayerInventory inv, Predicate<Item> match, int minCount) {
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = inv.getStack(i);

            // Skip empty or insufficient stacks early.
            if (stack.isEmpty() || stack.getCount() < minCount) continue;

            Item item = stack.getItem();
            if (!match.test(item)) continue;

            return i;
        }
        return -1;
    }

    /**
     * Convenience: does the hotbar contain at least minCount of an item matching match?
     */
    public boolean hasHotbarStackAtLeast(PlayerInventory inv, Predicate<Item> match, int minCount) {
        return this.findHotbarSlotWithCountAtLeast(inv, match, minCount) != -1;
    }

    /**
     * Ensures the client's selected hotbar slot is set to 'slot' and (if needed)
     * sends UpdateSelectedSlotC2SPacket to notify the server.
     *
     * Why resend logic exists:
     * - If you call setSelectedSlot() client-side, it changes local state, but the server
     *   needs the packet to agree on your held item.
     * - Re-sending after RESEND_SLOT_AFTER_MS helps keep things in sync if a packet was
     *   dropped or if other code changed the slot without updating tracking.
     *
     * Returns true if inputs are valid and we performed the action; false if player null
     * or slot outside 0..8.
     */
    public boolean ensureSelectedSlot(ClientPlayerEntity player, int slot) {
        if (player == null) return false;
        if (slot < 0 || slot > 8) return false;

        PlayerInventory inv = player.getInventory();

        int selected = inv.getSelectedSlot();
        if (selected != slot) {
            // Update local selection immediately.
            inv.setSelectedSlot(slot);
        }

        long now = System.currentTimeMillis();

        // Decide whether we should send the slot packet:
        // - If selection changed locally, send.
        // - Or if we haven't sent this slot before, send.
        // - Or if enough time has passed, resend.
        boolean shouldSend =
            selected != slot ||
                this.lastSentSlot != slot ||
                now - this.lastSentSlotMs > RESEND_SLOT_AFTER_MS;

        if (shouldSend) {
            player.networkHandler.sendPacket((Packet<?>) new UpdateSelectedSlotC2SPacket(slot));
            this.lastSentSlot = slot;
            this.lastSentSlotMs = now;
        }

        return true;
    }

    /**
     * Attempts to begin an "offhand swap session".
     *
     * What it does:
     * - Uses a synchronized lock to ensure only one swap session can be active.
     * - If a session is already active:
     *   - If it is "stale" (older than OFFHAND_SWAP_STALE_MS), it resets the flag and continues.
     *   - Otherwise returns false to indicate "can't swap right now".
     * - Sends PlayerActionC2SPacket(Action.SWAP_ITEM_WITH_OFFHAND) to swap mainhand/offhand.
     * - Sets offhandSwapped=true and records start time.
     *
     * Returns:
     * - true if swap packet was sent and the session is now active
     * - false if player null or swap locked (non-stale)
     *
     * NOTE: This does not wait for server confirmation; it's optimistic.
     */
    public boolean beginOffhandSwap(ClientPlayerEntity player) {
        if (player == null) return false;

        synchronized (this.offhandLock) {
            if (this.offhandSwapped) {
                long now = System.currentTimeMillis();

                // If this swap session has been active too long, treat it as stuck and reset.
                if (this.offhandSwapStartedMs != 0L && now - this.offhandSwapStartedMs > OFFHAND_SWAP_STALE_MS) {
                    this.offhandSwapped = false;
                    this.offhandSwapStartedMs = 0L;
                } else {
                    // Another routine is currently in a swap session (or we believe it is).
                    return false;
                }
            }

            // Swap mainhand <-> offhand on the server.
            // BlockPos.ORIGIN + Direction.DOWN is commonly used as a dummy payload for this action.
            player.networkHandler.sendPacket(
                (Packet<?>) new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ORIGIN,
                    Direction.DOWN
                )
            );

            this.offhandSwapped = true;
            this.offhandSwapStartedMs = System.currentTimeMillis();
            return true;
        }
    }

    /**
     * Ends an active offhand swap session by sending SWAP_ITEM_WITH_OFFHAND again,
     * returning hands to the original state, and clearing the local flag.
     */
    public void endOffhandSwap(ClientPlayerEntity player) {
        if (player == null) return;

        synchronized (this.offhandLock) {
            if (!this.offhandSwapped) return;

            player.networkHandler.sendPacket(
                (Packet<?>) new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ORIGIN,
                    Direction.DOWN
                )
            );

            this.offhandSwapped = false;
            this.offhandSwapStartedMs = 0L;
        }
    }

    /**
     * Returns whether a swap session is currently "active" according to our local flag.
     * (This is not guaranteed to match server state.)
     */
    public boolean isOffhandSwapped() {
        synchronized (this.offhandLock) {
            return this.offhandSwapped;
        }
    }

    /**
     * If we believe we're swapped, attempt to end the swap session.
     * This is useful as a safety cleanup method from other parts of the addon.
     */
    public void forceEndOffhandSwap(ClientPlayerEntity player) {
        synchronized (this.offhandLock) {
            if (!this.offhandSwapped) return;
        }
        // Call end outside the first synchronized block so the logic stays centralized.
        this.endOffhandSwap(player);
    }

    /**
     * Clears transient tracking:
     * - slot resend tracking
     * - offhand swap session state
     *
     * Useful when resetting modules or on world change, etc.
     */
    public void resetTransientState() {
        this.lastSentSlot = -1;
        this.lastSentSlotMs = 0L;

        synchronized (this.offhandLock) {
            this.offhandSwapped = false;
            this.offhandSwapStartedMs = 0L;
        }
    }
}
