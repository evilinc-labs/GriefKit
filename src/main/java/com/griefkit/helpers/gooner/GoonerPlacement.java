package com.griefkit.helpers.gooner;

import com.griefkit.mixin.ClientWorldAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.Arrays;
import java.util.List;

/**
 * Resistant block placement helpers with grim-compatible air-place techniques.
 * Adapted from GriefKit — replaces Meteor InvUtils/Rotations with anarchy-client equivalents.
 */
public class GoonerPlacement {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static final List<Block> RESISTANT_BLOCKS = Arrays.asList(
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.ENDER_CHEST,
            Blocks.RESPAWN_ANCHOR, Blocks.ENCHANTING_TABLE, Blocks.ANVIL
    );

    private static int nextSeq() {
        if (mc.world != null) {
            return ((ClientWorldAccessor) mc.world).griefkit$getPendingUpdateManager()
                    .incrementSequence().getSequence();
        }
        return 0;
    }

    public static int findResistantBlockSlot() {
        if (mc.player == null) return -1;
        var inv = mc.player.getInventory();
        for (Block block : RESISTANT_BLOCKS) {
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(block.asItem())) return i;
            }
        }
        return -1;
    }

    public static boolean placeBlock(BlockPos pos, boolean rotate, boolean swing, boolean strictDirection) {
        int slot = findResistantBlockSlot();
        if (slot == -1) return false;
        return placeBlock(pos, slot, rotate, swing, strictDirection);
    }

    public static boolean placeBlock(BlockPos pos, int slot, boolean rotate, boolean swing, boolean strictDirection) {
        if (slot == -1 || !canPlace(pos, strictDirection)) return false;
        Direction side = getPlaceSide(pos);
        if (side == null) return false;

        BlockPos neighbor = pos.offset(side);
        Direction opposite = side.getOpposite();
        Vec3d hitPos = Vec3d.ofCenter(neighbor).add(Vec3d.of(opposite.getVector()).multiply(0.5));

        if (rotate) {
            float[] rot = RotationUtils.getRotationsTo(mc.player.getEyePos(), hitPos);
            RotationUtils.getInstance().setRotationSilent(rot[0], rot[1]);
        }

        // Server-only slot spoof — does NOT change client selectedSlot.
        // Uses SwapSession like Surround: spoof to clog slot, place, restore.
        // Client slot stays unchanged so Replenish's clickSlot never desyncs.
        try (SwapSession session = SwapSession.begin(slot)) {
            if (session == null) return false;

            BlockHitResult hitResult = new BlockHitResult(hitPos, opposite, neighbor, false);
            mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, nextSeq()));

            if (swing) {
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        }
        return true;
    }

    public static boolean canPlace(BlockPos pos, boolean strictDirection) {
        if (mc.world == null) return false;
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;
        if (!mc.world.canPlace(Blocks.OBSIDIAN.getDefaultState(), pos, ShapeContext.absent())) return false;

        Box checkBox = Box.from(Vec3d.ofCenter(pos));
        List<Entity> entities = mc.world.getOtherEntities(null, checkBox);
        for (Entity entity : entities) {
            if (!entity.isSpectator() && entity.isAlive()) return false;
        }
        return !strictDirection || getPlaceSide(pos) != null;
    }

    public static Direction getPlaceSide(BlockPos pos) {
        if (mc.world == null) return null;
        if (!mc.world.getBlockState(pos.down()).isReplaceable()) return Direction.DOWN;
        for (Direction side : Direction.Type.HORIZONTAL) {
            if (!mc.world.getBlockState(pos.offset(side)).isReplaceable()) return side;
        }
        if (!mc.world.getBlockState(pos.up()).isReplaceable()) return Direction.UP;
        return null;
    }

    public static BlockPos getDirectionalPlacement(float yaw, BlockPos basePos) {
        float n = yaw % 360.0f;
        if (n < 0.0f) n += 360.0f;
        if (n >= 22.5 && n < 67.5) return basePos.south().west();
        else if (n >= 67.5 && n < 112.5) return basePos.west();
        else if (n >= 112.5 && n < 157.5) return basePos.north().west();
        else if (n >= 157.5 && n < 202.5) return basePos.north();
        else if (n >= 202.5 && n < 247.5) return basePos.north().east();
        else if (n >= 247.5 && n < 292.5) return basePos.east();
        else if (n >= 292.5 && n < 337.5) return basePos.south().east();
        else return basePos.south();
    }

    public static boolean isPhasing() {
        if (mc.player == null) return false;
        Box bb = mc.player.getBoundingBox();
        int minX = MathHelper.floor(bb.minX);
        int maxX = MathHelper.floor(bb.maxX) + 1;
        int minY = MathHelper.floor(bb.minY);
        int maxY = MathHelper.floor(bb.maxY) + 1;
        int minZ = MathHelper.floor(bb.minZ);
        int maxZ = MathHelper.floor(bb.maxZ) + 1;
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(pos).blocksMovement()) {
                        Box blockBox = new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                        if (bb.intersects(blockBox)) return true;
                    }
                }
            }
        }
        return false;
    }

    /* ===================== GRIM-COMPATIBLE PLACEMENT ===================== */

    public static boolean newPlaceGrim(BlockPos pos, boolean doRotate) {
        if (mc.player == null || mc.world == null) return false;

        if (doRotate) {
            float[] rot = RotationUtils.getRotationsTo(mc.player.getEyePos(), Vec3d.ofCenter(pos));
            RotationUtils.getInstance().setRotationSilent(rot[0], rot[1]);
        }

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.OFF_HAND, hit, nextSeq()));
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    public static boolean newPlaceNormal(BlockPos pos, boolean doRotate) {
        if (mc.player == null || mc.world == null) return false;
        Direction side = getPlaceSide(pos);
        if (side == null) return false;

        BlockPos neighbor = pos.offset(side);
        Direction opposite = side.getOpposite();
        Vec3d hitPos = Vec3d.ofCenter(neighbor).add(Vec3d.of(opposite.getVector()).multiply(0.5));

        if (doRotate) {
            float[] rot = RotationUtils.getRotationsTo(mc.player.getEyePos(), hitPos);
            RotationUtils.getInstance().setRotationSilent(rot[0], rot[1]);
        }

        BlockHitResult hitResult = new BlockHitResult(hitPos, opposite, neighbor, false);
        if (mc.getNetworkHandler() == null) return false;
        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, nextSeq()));
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    /** Wall gooner air-place — offhand swap technique. */
    public static boolean wallGoonerPlace(BlockPos pos, boolean doRotate) {
        return wallGoonerPlace(pos, doRotate, findResistantBlockSlot());
    }

    public static boolean wallGoonerPlace(BlockPos pos, boolean doRotate, int slot) {
        if (mc.player == null || mc.world == null || slot == -1) return false;

        if (doRotate) {
            float[] rot = RotationUtils.getRotationsTo(mc.player.getEyePos(), Vec3d.ofCenter(pos));
            RotationUtils.getInstance().setRotationSilent(rot[0], rot[1]);
        }

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

        // Server-only spoof to clog block slot, swap to offhand, place, swap back, restore slot
        SwapSession.spoofSlot(slot);
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.OFF_HAND, hit, nextSeq()));
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        // Restore server slot to client's actual selected slot
        //? if >=1.21.5 {
        SwapSession.spoofSlot(mc.player.getInventory().getSelectedSlot());
        //?} else
        /*SwapSession.spoofSlot(mc.player.getInventory().selectedSlot);*/
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    public static int getEnderPearlSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 45; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.ENDER_PEARL) return i;
        }
        return -1;
    }

    public static void clickSlot(int slot, SlotActionType actionType) {
        if (mc.interactionManager != null && mc.player != null) {
            mc.interactionManager.clickSlot(0, slot, 0, actionType, mc.player);
        }
    }
}
