package com.griefkit.modules;

import com.griefkit.GriefKit;
import com.griefkit.managers.ToolManager;
import com.griefkit.mixin.ClientWorldAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * GoonerWither — Builds a wither using air-place technique with per-tick block placement control.
 * Ported from GriefKit to anarchy-client module framework.
 */
public class GoonerWither extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to air-place per tick.")
        .defaultValue(4)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Boolean> silentMode = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-notifications")
        .description("Suppress chat messages.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoReplenish = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-replenish")
        .description("Pull wither materials into the pinned hotbar slots as they run low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> soulSandHotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("soul-sand-slot")
        .description("Hotbar slot (1-9) pinned for soul sand.")
        .defaultValue(1)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> skullHotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("skull-slot")
        .description("Hotbar slot (1-9) pinned for wither skulls.")
        .defaultValue(2)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> soulSandThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("soul-sand-threshold")
        .description("Minimum soul sand required to start a wither.")
        .defaultValue(16)
        .range(4, 64)
        .sliderRange(4, 64)
        .build()
    );

    private final Setting<Integer> skullThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("skull-threshold")
        .description("Minimum wither skulls required to start a wither.")
        .defaultValue(6)
        .range(3, 64)
        .sliderRange(3, 64)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw preview boxes on the wither pattern.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 50, 50, 25))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 50, 50, 255))
        .build()
    );

    private final Setting<SettingColor> placedSideColor = sgRender.add(new ColorSetting.Builder()
        .name("placed-side-color")
        .defaultValue(new SettingColor(50, 255, 50, 25))
        .build()
    );

    private final Setting<SettingColor> placedLineColor = sgRender.add(new ColorSetting.Builder()
        .name("placed-line-color")
        .defaultValue(new SettingColor(50, 255, 50, 255))
        .build()
    );

    private final List<Step> steps = new ArrayList<>();
    private int currentIndex = 0;
    private boolean prepared = false;

    private int runtimeSoulSandSlot = -1;
    private int runtimeSkullSlot = -1;
    private ItemStack savedSoulSandSlotItem = ItemStack.EMPTY;
    private ItemStack savedSkullSlotItem = ItemStack.EMPTY;
    // True when the slot already held the right material at activation — don't restore/swap back.
    private boolean soulSandSlotReused = false;
    private boolean skullSlotReused = false;

    /** True if the last wither placement succeeded. False if obstructed/no position/no materials. */
    public boolean lastPlacementSucceeded = false;

    public GoonerWither() {
        super(GriefKit.HELPERS, "gooner-wither", "INSTANT wither air-place via hotbar-swap. Placement is instantaneous — the whole wither is sent in one tick. Used by HighwayGoonerV2 as its wither engine, but also works standalone: toggle on, it air-places a wither in front of you and toggles itself off.");
    }

    public int getSoulSandThreshold() { return soulSandThreshold.get(); }
    public int getSkullThreshold() { return skullThreshold.get(); }

    @Override
    public void onActivate() {
        steps.clear();
        currentIndex = 0;
        prepared = false;
        runtimeSoulSandSlot = -1;
        runtimeSkullSlot = -1;
        savedSoulSandSlotItem = ItemStack.EMPTY;
        savedSkullSlotItem = ItemStack.EMPTY;

        if (mc.player == null || mc.world == null) {
            if (!silentMode.get()) ChatUtils.warning("GoonerWither: player/world not loaded");
            toggle();
            return;
        }

        acquireHotbarSlots();
        if (runtimeSoulSandSlot == -1 || runtimeSkullSlot == -1) {
            if (!silentMode.get()) ChatUtils.warning("GoonerWither: could not find 2 safe hotbar slots.");
            toggle();
            return;
        }

        preparePattern();
        if (!ensureMaterialsOrError()) { lastPlacementSucceeded = false; restoreHotbarSlots(); toggle(); return; }
        if (!canPlaceWholeWither()) {
            lastPlacementSucceeded = false;
            if (!silentMode.get()) ChatUtils.warning("GoonerWither: wither pattern obstructed/unsupported");
            toggle();
            return;
        }
        if (steps.isEmpty()) {
            lastPlacementSucceeded = false;
            if (!silentMode.get()) ChatUtils.warning("GoonerWither: no valid build position found");
            toggle();
            return;
        }

        lastPlacementSucceeded = true;
        prepared = true;
        if (!silentMode.get()) ChatUtils.info("GoonerWither: withering...");
    }

    @Override
    public void onDeactivate() {
        restoreHotbarSlots();
        steps.clear();
        currentIndex = 0;
        prepared = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!prepared || mc.player == null || mc.world == null) return;

        if (currentIndex >= steps.size()) {
            if (!silentMode.get()) ChatUtils.info("GoonerWither: wither done");
            toggle();
            return;
        }

        int placedThisTick = 0;
        while (currentIndex < steps.size() && placedThisTick < blocksPerTick.get()) {
            Step step = steps.get(currentIndex);
            if (mc.world.getBlockState(step.pos).getBlock() == step.block) {
                currentIndex++;
                continue;
            }
            placeStepAirplace(step);
            currentIndex++;
            placedThisTick++;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || !prepared || mc.world == null) return;

        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            boolean alreadyPlaced = i < currentIndex
                    || mc.world.getBlockState(step.pos).getBlock() == step.block;
            SettingColor side = alreadyPlaced ? placedSideColor.get() : sideColor.get();
            SettingColor line = alreadyPlaced ? placedLineColor.get() : lineColor.get();
            event.renderer.box(step.pos, side, line, ShapeMode.Both, 0);
        }
    }

    private void preparePattern() {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        Direction facing = player.getHorizontalFacing();
        BlockPos inFront = player.getBlockPos().offset(facing, 2);

        int stemY = inFront.getY();
        int bodyY = stemY + 1;
        int headY = bodyY + 1;

        BlockPos centerBody = new BlockPos(inFront.getX(), bodyY, inFront.getZ());
        Direction left = facing.rotateYCounterclockwise();
        Direction right = facing.rotateYClockwise();

        BlockPos stem = new BlockPos(inFront.getX(), stemY, inFront.getZ());
        BlockPos leftArm = centerBody.offset(left);
        BlockPos rightArm = centerBody.offset(right);

        BlockPos headCenter = new BlockPos(centerBody.getX(), headY, centerBody.getZ());
        BlockPos headLeft = new BlockPos(leftArm.getX(), headY, leftArm.getZ());
        BlockPos headRight = new BlockPos(rightArm.getX(), headY, rightArm.getZ());

        steps.clear();
        steps.add(new Step(stem, Blocks.SOUL_SAND));
        steps.add(new Step(centerBody, Blocks.SOUL_SAND));
        steps.add(new Step(leftArm, Blocks.SOUL_SAND));
        steps.add(new Step(rightArm, Blocks.SOUL_SAND));
        steps.add(new Step(headLeft, Blocks.WITHER_SKELETON_SKULL));
        steps.add(new Step(headCenter, Blocks.WITHER_SKELETON_SKULL));
        steps.add(new Step(headRight, Blocks.WITHER_SKELETON_SKULL));
    }

    private boolean placeStepAirplace(Step step) {
        if (mc.player == null || mc.world == null || mc.player.networkHandler == null) return false;

        PlayerInventory inv = mc.player.getInventory();
        int slot = (step.block == Blocks.SOUL_SAND) ? soulSandSlotIdx() : skullSlotIdx();

        if (step.block == Blocks.SOUL_SAND) {
            if (!ensurePinnedSlotAndThreshold(Blocks.SOUL_SAND, slot, 1, "Soul Sand")) return false;
        } else {
            if (!ensurePinnedSlotAndThreshold(Blocks.WITHER_SKELETON_SKULL, slot, 1, "Wither Skeleton Skull"))
                return false;
        }

        //? if >=1.21.5 {
        inv.setSelectedSlot(slot);
        //?} else
        /*inv.selectedSlot = slot;*/
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));

        if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) return false;

        BlockPos target = step.pos;
        if (!mc.world.getBlockState(target).isReplaceable()) return false;

        BlockPos supportPos = (step.block == Blocks.WITHER_SKELETON_SKULL) ? target.down() : target;
        Vec3d hitVec = Vec3d.ofCenter(supportPos);
        BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, supportPos, false);

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        int seq = ((ClientWorldAccessor) mc.world).griefkit$getPendingUpdateManager()
                .incrementSequence().getSequence();
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.OFF_HAND, bhr, seq));
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    // === Material management ===

    private int soulSandSlotIdx() {
        return runtimeSoulSandSlot >= 0 ? runtimeSoulSandSlot
                : Math.max(0, Math.min(8, soulSandHotbarSlot.get() - 1));
    }

    private int skullSlotIdx() {
        return runtimeSkullSlot >= 0 ? runtimeSkullSlot
                : Math.max(0, Math.min(8, skullHotbarSlot.get() - 1));
    }

    private void acquireHotbarSlots() {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();

        soulSandSlotReused = false;
        skullSlotReused = false;

        // Priority 1: if the user already has soul sand / wither skulls in ANY hotbar slot,
        // reuse those slots and leave them alone on deactivate.
        int existingSand = findHotbarStack(inv, Blocks.SOUL_SAND);
        int existingSkull = findHotbarStack(inv, Blocks.WITHER_SKELETON_SKULL);

        if (existingSand >= 0) {
            runtimeSoulSandSlot = existingSand;
            soulSandSlotReused = true;
        } else {
            int configSand = Math.max(0, Math.min(8, soulSandHotbarSlot.get() - 1));
            runtimeSoulSandSlot = isSafeForWither(inv, configSand) ? configSand : findSafeSlot(inv, -1);
        }

        if (existingSkull >= 0 && existingSkull != runtimeSoulSandSlot) {
            runtimeSkullSlot = existingSkull;
            skullSlotReused = true;
        } else {
            int configSkull = Math.max(0, Math.min(8, skullHotbarSlot.get() - 1));
            runtimeSkullSlot = isSafeForWither(inv, configSkull) && configSkull != runtimeSoulSandSlot
                    ? configSkull : findSafeSlot(inv, runtimeSoulSandSlot);
        }

        if (runtimeSoulSandSlot >= 0) savedSoulSandSlotItem = inv.getStack(runtimeSoulSandSlot).copy();
        if (runtimeSkullSlot >= 0) savedSkullSlotItem = inv.getStack(runtimeSkullSlot).copy();
    }

    /** Find a hotbar slot (0-8) already holding the given block, or -1. */
    private int findHotbarStack(PlayerInventory inv, Block block) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem bi && bi.getBlock() == block) return i;
        }
        return -1;
    }

    private void restoreHotbarSlots() {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();

        // Only swap back when we actually borrowed the slot (took it from something else).
        // If the user already had the material pinned, leave it alone.
        if (runtimeSoulSandSlot >= 0 && !soulSandSlotReused) {
            swapSlotToInventory(inv, runtimeSoulSandSlot, savedSoulSandSlotItem);
        }
        if (runtimeSkullSlot >= 0 && !skullSlotReused) {
            swapSlotToInventory(inv, runtimeSkullSlot, savedSkullSlotItem);
        }

        runtimeSoulSandSlot = -1;
        runtimeSkullSlot = -1;
        savedSoulSandSlotItem = ItemStack.EMPTY;
        savedSkullSlotItem = ItemStack.EMPTY;
        soulSandSlotReused = false;
        skullSlotReused = false;
    }

    private void swapSlotToInventory(PlayerInventory inv, int hotbarSlot, ItemStack savedItem) {
        ItemStack current = inv.getStack(hotbarSlot);
        if (current.isEmpty()) return;

        if (current.getItem() instanceof BlockItem bi &&
                (bi.getBlock() == Blocks.SOUL_SAND || bi.getBlock() == Blocks.WITHER_SKELETON_SKULL)) {
            for (int i = 9; i < 36; i++) {
                if (inv.getStack(i).isEmpty()) {
                    ToolManager.moveToHotbar(toWindowSlot(i), hotbarSlot);
                    break;
                }
            }
        }

        if (!savedItem.isEmpty() && !(savedItem.getItem() instanceof BlockItem sbi &&
                (sbi.getBlock() == Blocks.SOUL_SAND || sbi.getBlock() == Blocks.WITHER_SKELETON_SKULL))) {
            for (int i = 9; i < 36; i++) {
                ItemStack s = inv.getStack(i);
                if (!s.isEmpty() && ItemStack.areItemsEqual(s, savedItem)) {
                    ToolManager.moveToHotbar(toWindowSlot(i), hotbarSlot);
                    break;
                }
            }
        }
    }

    private boolean isSafeForWither(PlayerInventory inv, int slot) {
        ItemStack stack = inv.getStack(slot);
        if (stack.isEmpty()) return true;
        if (stack.getItem() instanceof BlockItem bi) {
            Block b = bi.getBlock();
            if (b == Blocks.SOUL_SAND || b == Blocks.WITHER_SKELETON_SKULL) return true;
            return !isProtectedItem(stack);
        }
        return false;
    }

    private int findSafeSlot(PlayerInventory inv, int excludeSlot) {
        for (int i = 0; i < 9; i++) {
            if (i == excludeSlot) continue;
            if (inv.getStack(i).isEmpty()) return i;
        }
        for (int i = 8; i >= 0; i--) {
            if (i == excludeSlot) continue;
            if (!isProtectedItem(inv.getStack(i))) return i;
        }
        return -1;
    }

    private boolean isProtectedItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) return true;
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) return true;
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.AXES)) return true;
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.SHOVELS)) return true;
        net.minecraft.item.Item item = stack.getItem();
        if (item == Items.TOTEM_OF_UNDYING || item == Items.SHIELD) return true;
        if (item == Items.END_CRYSTAL || item == Items.FIREWORK_ROCKET) return true;
        if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) return true;
        if (item == Items.ENDER_PEARL || item == Items.BOW || item == Items.CROSSBOW) return true;
        if (stack.get(net.minecraft.component.DataComponentTypes.FOOD) != null) return true;
        if (item instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) return true;
        return false;
    }

    private boolean ensureMaterialsOrError() {
        if (mc.player == null) return false;
        boolean okSand = ensurePinnedSlotAndThreshold(
                Blocks.SOUL_SAND, soulSandSlotIdx(), soulSandThreshold.get(), "Soul Sand");
        boolean okSkull = ensurePinnedSlotAndThreshold(
                Blocks.WITHER_SKELETON_SKULL, skullSlotIdx(), skullThreshold.get(), "Wither Skeleton Skull");
        return okSand && okSkull;
    }

    private boolean ensurePinnedSlotAndThreshold(Block block, int pinnedSlot, int threshold, String prettyName) {
        PlayerInventory inv = mc.player.getInventory();
        ItemStack pinned = inv.getStack(pinnedSlot);

        if (pinned.isEmpty() || !(pinned.getItem() instanceof BlockItem bi) || bi.getBlock() != block) {
            int best = findLargestStackSlot(inv, block);
            if (best == -1) {
                if (!silentMode.get()) ChatUtils.warning("GoonerWither: missing %s (need at least %d).", prettyName, threshold);
                return false;
            }
            ToolManager.moveToHotbar(toWindowSlot(best), pinnedSlot);
            pinned = inv.getStack(pinnedSlot);
        }

        String key = stackKey(pinned);
        int totalCompatible = countTotal(inv, block, key);

        if (totalCompatible < threshold) {
            if (!silentMode.get()) ChatUtils.warning("GoonerWither: not enough %s (need %d).", prettyName, threshold);
            return false;
        }

        if (autoReplenish.get()) {
            topUpPinnedSlot(inv, block, pinnedSlot, key, threshold);
        }
        return true;
    }

    private void topUpPinnedSlot(PlayerInventory inv, Block block, int pinnedSlot, String key, int targetCount) {
        ItemStack pinned = inv.getStack(pinnedSlot);
        int count = pinned.getCount();
        if (count >= targetCount) return;

        for (int i = 0; i < inv.size(); i++) {
            if (i == pinnedSlot) continue;
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof BlockItem bi) || bi.getBlock() != block) continue;
            if (!Objects.equals(stackKey(s), key)) continue;
            ToolManager.moveToHotbar(toWindowSlot(i), pinnedSlot);
            pinned = inv.getStack(pinnedSlot);
            count = pinned.getCount();
            if (count >= targetCount) return;
        }
    }

    private int findLargestStackSlot(PlayerInventory inv, Block block) {
        int best = -1, bestCount = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof BlockItem bi) || bi.getBlock() != block) continue;
            if (s.getCount() > bestCount) { bestCount = s.getCount(); best = i; }
        }
        return best;
    }

    private int countTotal(PlayerInventory inv, Block block, String keyOrNull) {
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof BlockItem bi) || bi.getBlock() != block) continue;
            if (keyOrNull != null && !Objects.equals(stackKey(s), keyOrNull)) continue;
            total += s.getCount();
        }
        return total;
    }

    private String stackKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return stack.getName().getString();
    }

    private boolean canPlaceWholeWither() {
        if (mc.world == null || steps.size() < 7) return false;

        BlockPos stem = steps.get(0).pos;
        BlockPos centerBody = steps.get(1).pos;
        BlockPos leftArm = steps.get(2).pos;
        BlockPos rightArm = steps.get(3).pos;

        BlockPos leftDelta = leftArm.subtract(centerBody);
        BlockPos rightDelta = rightArm.subtract(centerBody);

        if (!mc.world.getBlockState(stem.add(leftDelta.getX(), 0, leftDelta.getZ())).isAir()) return false;
        if (!mc.world.getBlockState(stem.add(rightDelta.getX(), 0, rightDelta.getZ())).isAir()) return false;

        BlockPos[] soul = {stem, centerBody, leftArm, rightArm};
        for (BlockPos pos : soul) {
            if (!isReplaceableOrSame(pos, Blocks.SOUL_SAND)) return false;
        }

        for (int i = 4; i < 7; i++) {
            BlockPos pos = steps.get(i).pos;
            if (!isReplaceableOrSame(pos, Blocks.WITHER_SKELETON_SKULL)) return false;
            BlockPos below = pos.down();
            Block belowBlock = mc.world.getBlockState(below).getBlock();
            if (belowBlock != Blocks.SOUL_SAND && !isReplaceableOrSame(below, Blocks.SOUL_SAND)) return false;
        }
        return true;
    }

    private boolean isReplaceableOrSame(BlockPos pos, Block expected) {
        if (mc.world == null) return false;
        Block current = mc.world.getBlockState(pos).getBlock();
        return current == expected || mc.world.getBlockState(pos).isReplaceable();
    }

    private static int toWindowSlot(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;
        return invSlot;
    }

    private static class Step {
        public final BlockPos pos;
        public final Block block;
        public Step(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
        }
    }
}
