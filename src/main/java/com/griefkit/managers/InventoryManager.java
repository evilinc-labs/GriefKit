package com.griefkit.managers;

import java.util.function.Predicate;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
 * - Provides a basic "hotbar swap session" so modules like PlacementManager
 *   can temporarily take over the selected hotbar slot and restore it later.
 *
 * Important behavior notes:
 * - beginOffhandSwap() sends SWAP_ITEM_WITH_OFFHAND and sets offhandSwapped=true
 * - endOffhandSwap() sends SWAP_ITEM_WITH_OFFHAND again to revert and clears the flag
 * - beginHotbarSwapSession() remembers the original selectedSlot for this player
 * - endHotbarSwapSession() restores that slot
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
     * Simple hotbar swap session:
     * - hotbarSwapActive: true while a module "owns" the selected slot and expects it restored later
     * - hotbarOriginalSlot: the selected slot when the session began
     *
     * This is inspired by the SwapManager pattern: one module can temporarily change
     * selectedSlot many times, then restore it when done.
     */
    private final Object hotbarSwapLock = new Object();
    private boolean hotbarSwapActive = false;
    private int hotbarOriginalSlot = -1;

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
     * Returns true if inputs are valid and we performed the action; false if player null
     * or slot outside 0..8.
     */
    public boolean ensureSelectedSlot(ClientPlayerEntity player, int slot) {
        if (player == null) return false;
        if (slot < 0 || slot > 8) return false;

        PlayerInventory inv = player.getInventory();

        //? if >=1.21.5 {
        int selected = inv.getSelectedSlot();
        if (selected != slot) {
            // Update local selection immediately.
            inv.setSelectedSlot(slot);
        }
        //?} else
        /*int selected = inv.selectedSlot;
        if (selected != slot) {
            inv.selectedSlot = slot;
        }*/

        long now = System.currentTimeMillis();

        boolean shouldSend =
            selected != slot ||
                this.lastSentSlot != slot ||
                now - this.lastSentSlotMs > RESEND_SLOT_AFTER_MS;

        if (shouldSend) {
            player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            this.lastSentSlot = slot;
            this.lastSentSlotMs = now;
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Hotbar swap session (inspired by SwapManager, but simplified)
    // -------------------------------------------------------------------------

    /**
     * Begins a "hotbar swap session":
     *
     * - Remembers the player's current selectedSlot as the original.
     * - Does NOT itself change the selectedSlot; callers still use ensureSelectedSlot
     *   for each target. This just centralizes the "remember & restore" behavior.
     *
     * Returns:
     * - true if we successfully began a session (or one is already active).
     * - false only if player is null.
     *
     * You can safely call this multiple times; only the first call actually
     * snapshots the original slot.
     */
    public boolean beginHotbarSwapSession(ClientPlayerEntity player) {
        if (player == null) return false;

        synchronized (this.hotbarSwapLock) {
            if (!this.hotbarSwapActive) {
                this.hotbarSwapActive = true;
                //? if >=1.21.5 {
                this.hotbarOriginalSlot = player.getInventory().getSelectedSlot();
                //?} else
                /*this.hotbarOriginalSlot = player.getInventory().selectedSlot;*/
            }
        }

        return true;
    }

    /**
     * Ends a hotbar swap session:
     * - Restores the original selectedSlot captured at beginHotbarSwapSession.
     * - Sends an UpdateSelectedSlotC2SPacket via ensureSelectedSlot.
     */
    public void endHotbarSwapSession(ClientPlayerEntity player) {
        if (player == null) return;

        int original;
        synchronized (this.hotbarSwapLock) {
            if (!this.hotbarSwapActive) return;

            original = this.hotbarOriginalSlot;
            this.hotbarSwapActive = false;
            this.hotbarOriginalSlot = -1;
        }

        // Restore original slot and notify server.
        this.ensureSelectedSlot(player, original);
    }

    public boolean isHotbarSwapActive() {
        synchronized (this.hotbarSwapLock) {
            return this.hotbarSwapActive;
        }
    }

    // -------------------------------------------------------------------------
    // Offhand swap logic (unchanged, but cleaned casts)
    // -------------------------------------------------------------------------

    /**
     * Attempts to begin an "offhand swap session".
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
            player.networkHandler.sendPacket(
                new PlayerActionC2SPacket(
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
                new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ORIGIN,
                    Direction.DOWN
                )
            );

            this.offhandSwapped = false;
            this.offhandSwapStartedMs = 0L;
        }
    }

    public boolean isOffhandSwapped() {
        synchronized (this.offhandLock) {
            return this.offhandSwapped;
        }
    }

    public void forceEndOffhandSwap(ClientPlayerEntity player) {
        synchronized (this.offhandLock) {
            if (!this.offhandSwapped) return;
        }
        this.endOffhandSwap(player);
    }

    /**
     * Clears transient tracking:
     * - slot resend tracking
     * - offhand swap session state
     * - hotbar swap session state
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

        synchronized (this.hotbarSwapLock) {
            this.hotbarSwapActive = false;
            this.hotbarOriginalSlot = -1;
        }
    }
}
