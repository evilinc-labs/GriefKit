package com.griefkit.managers;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

import java.util.function.Predicate;


//@todo add silent swap and make inv management not shit

public class InventoryManager {
    public int findHotbarSlot(PlayerInventory inv, Predicate<Item> match) {
        for (int i = 0; i < 9; i++) {
            Item item = inv.getStack(i).getItem();
            if (match.test(item)) return i;
        }
        return -1;
    }

    public boolean ensureSelectedSlot(ClientPlayerEntity player, int slot) {
        PlayerInventory inv = player.getInventory();
        if (slot < 0 || slot > 8) return false;

        if (inv.getSelectedSlot() != slot) {
            inv.setSelectedSlot(slot);
            player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
        return true;
    }
}
