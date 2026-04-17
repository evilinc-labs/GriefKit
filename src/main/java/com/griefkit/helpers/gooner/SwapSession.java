package com.griefkit.helpers.gooner;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

// Scoped server-side hotbar spoof — the client's visible slot stays,
// but the server thinks we're holding whatever is in the spoofed slot.
// GoonerPlacement wraps its air-place in one of these so Replenish/HotbarSupply
// never sees a client slot change.
public final class SwapSession implements AutoCloseable {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final int originalSlot;
    private boolean active;

    private SwapSession(int originalSlot) {
        this.originalSlot = originalSlot;
        this.active = true;
    }

    public static SwapSession begin(int desiredSlot) {
        if (mc.player == null || desiredSlot < 0 || desiredSlot > 8) return null;
        //? if >=1.21.5 {
        int current = mc.player.getInventory().getSelectedSlot();
        //?} else
        /*int current = mc.player.getInventory().selectedSlot;*/
        if (desiredSlot != current) {
            spoofSlot(desiredSlot);
        }
        return new SwapSession(current);
    }

    public static void spoofSlot(int slot) {
        if (mc.player != null && slot >= 0 && slot <= 8) {
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    public void end() {
        if (active) {
            spoofSlot(originalSlot);
            active = false;
        }
    }

    @Override
    public void close() { end(); }

    public int getOriginalSlot() { return originalSlot; }
    public boolean isActive() { return active; }
}
