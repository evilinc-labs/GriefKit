package com.griefkit.managers;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.Random;

/**
 * Tool selection and inventory manipulation utilities for GriefKit modules.
 * Adapted from GriefKit's ToolManager — uses anarchy-client's MiningUtil where possible.
 */
public final class ToolManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int MAIN_INV_SIZE = 36;
    private static final Random RANDOM = new Random();

    private ToolManager() {}

    /**
     * Find the best ItemStack in the player's ENTIRE inventory for breaking the given block state.
     */
    public static ItemStack findBestTool(BlockState state) {
        if (mc.player == null) return ItemStack.EMPTY;

        ItemStack best = mc.player.getMainHandStack();
        float bestSpeed = best.getMiningSpeedMultiplier(state);

        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = stack;
            }
        }
        return best;
    }

    /**
     * Find the hotbar slot [0..8] with the best tool for breaking the block at the given position.
     * Switches the selected slot and sends a packet if found.
     * Returns the slot index, or -1 if no suitable tool exists.
     */
    public static int findBestPickaxeSlot(BlockPos pos) {
        if (mc.world == null || mc.player == null) return -1;

        BlockState state = mc.world.getBlockState(pos);
        float bestSpeed = 0.0f;
        int bestSlot = -1;

        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        if (bestSlot != -1) {
            //? if >=1.21.5 {
            inv.setSelectedSlot(bestSlot);
            //?} else
            /*inv.selectedSlot = bestSlot;*/
            sendSlotChange(bestSlot);
        }
        return bestSlot;
    }

    private static boolean hasSilkTouch(ItemStack stack) {
        var e = stack.get(net.minecraft.component.DataComponentTypes.ENCHANTMENTS);
        if (e == null) return false;
        for (var entry : e.getEnchantmentEntries()) {
            if (entry.getKey().getIdAsString().equals("minecraft:silk_touch")) return true;
        }
        return false;
    }

    /**
     * Count total items of the given type across the player's main inventory.
     */
    public static int countItem(Item item) {
        if (mc.player == null) return 0;

        int count = 0;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < MAIN_INV_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Count items of the given type, including inside shulker boxes in inventory.
     */
    public static int countItemIncludingShulkers(Item item) {
        if (mc.player == null) return 0;

        int count = 0;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < MAIN_INV_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                count += countItemInContainer(stack, item);
            }
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Count items ONLY inside shulker boxes (not loose in inventory). */
    public static int countItemInShulkers(Item item) {
        return countItemIncludingShulkers(item) - countItem(item);
    }

    /** Find the hotbar slot (0-8) containing the given item, or -1. */
    public static int findItemInHotbar(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    /**
     * Find an empty hotbar slot, or return a random slot in [1..7].
     */
    public static int findEmptyOrRandomHotbarSlot() {
        if (mc.player == null) return 8;

        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i <= 8; i++) {
            if (inv.getStack(i).isEmpty()) return i;
        }
        return 1 + RANDOM.nextInt(7);
    }

    /**
     * Send an UpdateSelectedSlotC2SPacket to the server.
     */
    public static void sendSlotChange(int slot) {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    /**
     * Execute a runnable with a different hotbar slot selected, then swap back.
     * Uses click-swap for silent operation.
     */
    public static boolean silentSwap(int slot, Runnable action) {
        if (mc.player == null || action == null) return false;
        if (slot < 0 || slot > 35) return false;

        boolean swapped = performClickSwap(slot);
        try {
            action.run();
            return true;
        } finally {
            if (swapped) {
                performClickSwap(slot);
            }
        }
    }

    /**
     * Count empty slots in the player's main inventory (slots 0-35).
     */
    public static int countEmptySlots() {
        if (mc.player == null) return 0;

        int count = 0;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < MAIN_INV_SIZE; i++) {
            if (inv.getStack(i).isEmpty()) count++;
        }
        return count;
    }

    /**
     * Count the number of usable pickaxes in the player's inventory.
     */
    public static int countUsablePickaxes(boolean includeSilkTouch) {
        if (mc.player == null) return 0;

        int count = 0;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!isPickaxe(stack)) continue;
            if (!includeSilkTouch && hasSilkTouch(stack)) continue;
            if (!hasEnoughDurability(stack)) continue;
            count++;
        }
        return count;
    }

    /**
     * Check if the given ItemStack is a pickaxe.
     */
    public static boolean isPickaxe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.isIn(ItemTags.PICKAXES);
    }

    /**
     * Check if the given ItemStack has enough durability remaining (> 50).
     */
    public static boolean hasEnoughDurability(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable()) return false;
        return stack.getMaxDamage() - stack.getDamage() > 50;
    }

    /**
     * Move an item from main inventory to hotbar via player inventory screen.
     * Uses playerScreenHandler (syncId 0) — works silently without closing
     * mod menus, chat, or other client-side screens.
     */
    public static void moveToHotbar(int fromSlot, int toHotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(
            mc.player.playerScreenHandler.syncId,
            fromSlot,
            toHotbarSlot,
            SlotActionType.SWAP,
            mc.player
        );
    }

    // -- Internal helpers --

    private static boolean performClickSwap(int slot) {
        if (mc.player == null || mc.interactionManager == null) return false;

        //? if >=1.21.5 {
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        //?} else
        /*int currentSlot = mc.player.getInventory().selectedSlot;*/
        if (slot == currentSlot) return false;

        int windowSlot = toWindowSlot(slot);
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            windowSlot,
            currentSlot,
            SlotActionType.SWAP,
            mc.player
        );
        return true;
    }

    private static int toWindowSlot(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;
        return invSlot;
    }

    /**
     * Find a shulker box in the player's inventory that contains the given item.
     * Returns the shulker ItemStack, or null if none found.
     */
    public static ItemStack findShulkerContaining(Item item) {
        if (mc.player == null) return null;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < MAIN_INV_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof net.minecraft.item.BlockItem bi
                    && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
                if (countItemInContainer(stack, item) > 0) return stack;
            }
        }
        return null;
    }

    /**
     * Swap to the best pickaxe in hotbar for breaking (non-silk-touch preferred).
     */
    public static void swapToBestPickaxe(boolean includeSilkTouch) {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();
        float bestSpeed = 0.0f;
        int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!isPickaxe(stack)) continue;
            if (!includeSilkTouch && hasSilkTouch(stack)) continue;
            if (!hasEnoughDurability(stack)) continue;
            // Use obsidian as reference block for speed comparison
            float speed = stack.getMiningSpeedMultiplier(net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot != -1) {
            //? if >=1.21.5 {
            inv.setSelectedSlot(bestSlot);
            //?} else
            /*inv.selectedSlot = bestSlot;*/
            sendSlotChange(bestSlot);
        }
    }

    /**
     * Swap to the given item in hotbar.
     */
    public static void swapToItem(Item item) {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == item) {
                //? if >=1.21.5 {
                inv.setSelectedSlot(i);
                //?} else
                /*inv.selectedSlot = i;*/
                sendSlotChange(i);
                return;
            }
        }
    }

    /**
     * Find the shulker box with the MOST of a given item.
     * Returns the shulker ItemStack, or null if none found.
     */
    public static ItemStack findShulkerWithMost(Item item) {
        if (mc.player == null) return null;
        PlayerInventory inv = mc.player.getInventory();
        ItemStack best = null;
        int bestCount = 0;
        for (int i = 0; i < MAIN_INV_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                int count = countItemInContainer(stack, item);
                if (count > bestCount) {
                    bestCount = count;
                    best = stack;
                }
            }
        }
        return best;
    }

    /**
     * Find the shulker that can produce the MOST complete withers.
     * 1 wither = 3 wither skeleton skulls + 4 soul sand.
     * Score = min(skulls / 3, soul_sand / 4).
     * Returns the best shulker ItemStack, or null if none can produce a wither.
     */
    public static ItemStack findBestWitherShulker() {
        if (mc.player == null) return null;
        PlayerInventory inv = mc.player.getInventory();
        ItemStack best = null;
        int bestWithers = 0;
        for (int i = 0; i < MAIN_INV_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                int skulls = countItemInContainer(stack, net.minecraft.item.Items.WITHER_SKELETON_SKULL);
                int sand = countItemInContainer(stack, net.minecraft.block.Blocks.SOUL_SAND.asItem());
                int withers = Math.min(skulls / 3, sand / 4);
                if (withers > bestWithers) {
                    bestWithers = withers;
                    best = stack;
                }
            }
        }
        return best;
    }

    private static int countItemInContainer(ItemStack container, Item item) {
        ContainerComponent contents = container.get(DataComponentTypes.CONTAINER);
        if (contents == null) return 0;

        int count = 0;
        for (ItemStack stack : contents.iterateNonEmpty()) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
