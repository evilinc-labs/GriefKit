package com.griefkit.modules;

import com.griefkit.GriefKit;
import com.griefkit.managers.ToolManager;
import com.griefkit.mixin.ClientWorldAccessor;

import java.util.ArrayDeque;
import java.util.Deque;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

public class PacketMine extends Module {
    static final MinecraftClient mc = MinecraftClient.getInstance();
    public static PacketMine INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> doubleBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("double-break").description("Mine two blocks at once").defaultValue(true).build());

    private final Setting<Boolean> grimBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("grim-bypass").description("Send STOP before START to reset server state").defaultValue(true).build());

    private final Setting<Double> breakThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("break-threshold").description("Progress threshold for primary block")
        .defaultValue(0.7).range(0.1, 1.0).sliderRange(0.1, 1.0).build());

    public final Setting<Boolean> autoRebreak = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-rebreak").description("Re-mine if last block reappears").defaultValue(true).build());

    final Setting<Boolean> silentSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-swap").description("Break without visibly holding pickaxe").defaultValue(false).build());

    private final Setting<Boolean> validateBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("validate-break").description("Wait for server confirmation before removing block client-side").defaultValue(false).build());

    private final Setting<Boolean> globalRendering = sgRender.add(new BoolSetting.Builder()
        .name("global-rendering").defaultValue(true).description("Use default colors").build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").defaultValue(new SettingColor(0, 255, 255, 40)).visible(() -> !globalRendering.get()).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(0, 255, 255, 200)).visible(() -> !globalRendering.get()).build());

    private MineContext primaryMine;
    private MineContext secondaryMine;
    public BlockPos lastBrokenPos;
    public final Deque<BlockPos> mineQueue = new ArrayDeque<>();

    public PacketMine() {
        super(GriefKit.CATEGORY, "packet-mine", "Grim-safe packet miner with queue and double break.");
        INSTANCE = this;
    }

    public void enqueueBlock(BlockPos pos) {
        if (mc.world == null) return;
        if (!BlockUtils.canBreak(pos, mc.world.getBlockState(pos))) return;
        if (isOutOfRange(pos)) return;
        if (isAlreadyQueued(pos)) return;
        startMining(pos, mc.world.getBlockState(pos));
    }

    public boolean isAlreadyQueued(BlockPos pos) {
        if (primaryMine != null && primaryMine.pos.equals(pos)) return true;
        if (secondaryMine != null && secondaryMine.pos.equals(pos)) return true;
        return mineQueue.contains(pos);
    }

    public boolean isBusy() {
        return primaryMine == null && secondaryMine == null && !mineQueue.isEmpty();
    }

    public static void instaBreak(BlockPos pos) {
        if (INSTANCE.isAlreadyQueued(pos)) return;
        if (pos != null) {
            MineContext ctx = new MineContext(pos, mc.world.getBlockState(pos), true);
            INSTANCE.sendMineStartPackets(pos);
            INSTANCE.finishBreak(ctx, INSTANCE.silentSwap.get());
        }
    }

    @Override
    public void onDeactivate() {
        primaryMine = null;
        secondaryMine = null;
        mineQueue.clear();
        lastBrokenPos = null;
    }

    public void startMining(BlockPos pos, BlockState state) {
        if (isAlreadyQueued(pos)) return;

        if (!(primaryMine == null || secondaryMine == null && doubleBreak.get())) {
            if (!mineQueue.contains(pos)) mineQueue.addLast(pos);
            return;
        }

        if (primaryMine == null) {
            primaryMine = new MineContext(pos, state, true);
            sendMineStartPackets(pos);
        } else if (doubleBreak.get() && secondaryMine == null) {
            sendActionPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, primaryMine.pos);
            secondaryMine = new MineContext(primaryMine.pos, primaryMine.blockState, false);
            primaryMine = new MineContext(pos, state, true);
            sendMineStartPackets(primaryMine.pos);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (lastBrokenPos != null && autoRebreak.get()
                && primaryMine == null && secondaryMine == null
                && !mc.world.getBlockState(lastBrokenPos).isAir()) {
            breakWithSwap(new MineContext(lastBrokenPos, mc.world.getBlockState(lastBrokenPos), false), silentSwap.get());
            return;
        }

        cleanupBrokenBlocks();

        if (secondaryMine != null && secondaryMine.getBreakProgress() >= 1.0) {
            finishBreak(secondaryMine, silentSwap.get());
        }
        if (primaryMine != null && primaryMine.getBreakProgress() >= 1.0) {
            finishBreak(primaryMine, silentSwap.get());
        }

        processQueue();
    }

    private void cleanupBrokenBlocks() {
        if (primaryMine != null && isBlockGoneOrOutOfRange(primaryMine.pos)) primaryMine = null;
        if (secondaryMine != null && isBlockGoneOrOutOfRange(secondaryMine.pos)) secondaryMine = null;
        mineQueue.removeIf(this::isBlockGoneOrOutOfRange);
    }

    private boolean isBlockGoneOrOutOfRange(BlockPos pos) {
        return mc.world.getBlockState(pos).isAir() || isOutOfRange(pos);
    }

    private void processQueue() {
        if (mineQueue.isEmpty()) return;

        if (primaryMine == null) {
            BlockPos pos = mineQueue.pollFirst();
            primaryMine = new MineContext(pos, mc.world.getBlockState(pos), true);
            sendMineStartPackets(pos);
        } else if (doubleBreak.get() && secondaryMine == null) {
            sendActionPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, primaryMine.pos);
            BlockPos pos = mineQueue.pollFirst();
            secondaryMine = new MineContext(primaryMine.pos, primaryMine.blockState, false);
            primaryMine = new MineContext(pos, mc.world.getBlockState(pos), true);
            sendMineStartPackets(primaryMine.pos);
        }
    }

    private void sendMineStartPackets(BlockPos pos) {
        if (grimBypass.get()) {
            sendActionPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos);
        }
        sendActionPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos);
    }

    private void breakWithSwap(MineContext ctx, boolean silent) {
        if (mc.world == null || mc.player == null) return;

        int bestSlot = mc.player.getInventory().getSlotWithStack(ToolManager.findBestTool(ctx.blockState));
        //? if >=1.21.5 {
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        //?} else
        /*int currentSlot = mc.player.getInventory().selectedSlot;*/
        boolean needSwap = bestSlot != currentSlot;

        if (silent && needSwap) sendSlotChange(bestSlot);
        sendActionPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, ctx.pos);
        if (silent && needSwap) sendSlotChange(currentSlot);
    }

    private void finishBreak(MineContext ctx, boolean silent) {
        if (mc.world == null || mc.player == null) return;

        int bestSlot = mc.player.getInventory().getSlotWithStack(ToolManager.findBestTool(ctx.blockState));

        if (!ctx.isInstaBreak) {
            if (silent) {
                //? if >=1.21.5 {
                int currentSlot = mc.player.getInventory().getSelectedSlot();
                //?} else
                /*int currentSlot = mc.player.getInventory().selectedSlot;*/
                boolean needSwap = bestSlot != currentSlot && bestSlot >= 0;
                if (needSwap) sendSlotChange(bestSlot);
                sendActionPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, ctx.pos);
                if (needSwap) sendSlotChange(currentSlot);
            } else {
                sendActionPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, ctx.pos);
            }
        }

        if ((ctx.isInstaBreak || ctx.isAboveThreshold) && !validateBreak.get()) {
            mc.world.syncWorldEvent(2001, ctx.pos, Block.getRawIdFromState(ctx.blockState));
            mc.world.setBlockState(ctx.pos, Blocks.AIR.getDefaultState(), 3);
        }

        lastBrokenPos = ctx.pos;
        ctx.active = false;

        if (ctx == primaryMine) primaryMine = null;
        else if (ctx == secondaryMine) secondaryMine = null;
    }

    private static int nextSeq() {
        if (mc.world != null) {
            return ((ClientWorldAccessor) mc.world).griefkit$getPendingUpdateManager()
                    .incrementSequence().getSequence();
        }
        return 0;
    }

    public void sendActionPacket(PlayerActionC2SPacket.Action action, BlockPos pos) {
        if (mc.player == null || mc.world == null) return;
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(action, pos, Direction.UP, nextSeq()));
    }

    public void sendSlotChange(int slot) {
        if (mc.player == null || slot < 0) return;
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    public boolean isOutOfRange(BlockPos pos) {
        return !(mc.player.getEyePos().distanceTo(pos.toCenterPos()) <= 5.5);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        Color line = globalRendering.get() ? new Color(0, 255, 255, 200) : lineColor.get();
        Color side = globalRendering.get() ? new Color(0, 255, 255, 40) : sideColor.get();

        for (BlockPos pos : mineQueue) {
            event.renderer.box(pos, Color.WHITE, Color.WHITE, ShapeMode.Lines, 0);
        }

        if (secondaryMine != null) renderMineProgress(event, secondaryMine, side, line);
        if (primaryMine != null) renderMineProgress(event, primaryMine, side, line);

        if (lastBrokenPos != null && autoRebreak.get() && !mc.world.getBlockState(lastBrokenPos).isAir()) {
            event.renderer.box(lastBrokenPos, side, line, ShapeMode.Both, 0);
        }
    }

    private void renderMineProgress(Render3DEvent event, MineContext ctx, Color side, Color line) {
        double d = (1.0 - ctx.getBreakProgress()) / 2.0;
        Box box = new Box(
            ctx.pos.getX() + d, ctx.pos.getY() + d, ctx.pos.getZ() + d,
            ctx.pos.getX() + 1.0 - d, ctx.pos.getY() + 1.0 - d, ctx.pos.getZ() + 1.0 - d);
        event.renderer.box(box, side, line, ShapeMode.Both, 0);
    }

    public static class MineContext {
        public final BlockPos pos;
        public final BlockState blockState;
        public long startTimeMs;
        public final float hardness;
        public boolean active = true;
        public final boolean isPrimary;
        public final boolean isInstaBreak;
        public final boolean isAboveThreshold;

        public MineContext(BlockPos pos, BlockState state, boolean isPrimary) {
            this.pos = pos.toImmutable();
            this.blockState = state;
            this.hardness = state.getHardness(mc.world, pos);
            this.isPrimary = isPrimary;
            this.startTimeMs = System.currentTimeMillis();
            this.isInstaBreak = BlockUtils.canInstaBreak(pos);
            this.isAboveThreshold = calculateBreakSpeed() / INSTANCE.breakThreshold.get().floatValue() >= 1.0;
        }

        private float calculateBreakSpeed() {
            float baseDelta = blockState.getHardness(mc.world, pos);
            ItemStack bestTool = ToolManager.findBestTool(blockState);

            if (!INSTANCE.silentSwap.get() && !bestTool.isEmpty()) {
                int slot = mc.player.getInventory().getSlotWithStack(bestTool);
                if (slot >= 0 && slot < 9) {
                    //? if >=1.21.5 {
                    if (mc.player.getInventory().getSelectedSlot() != slot) {
                    //?} else
                    /*if (mc.player.getInventory().selectedSlot != slot) {*/
                        ToolManager.findBestPickaxeSlot(pos);
                    }
                } else if (slot >= 9) {
                    InvUtils.move().from(slot).to(ToolManager.findEmptyOrRandomHotbarSlot());
                }
            }

            int breakDivisor = (!blockState.isToolRequired() || bestTool.isSuitableFor(blockState)) ? 30 : 100;
            float breakSpeed = mc.player.getBlockBreakingSpeed(blockState);

            if (bestTool != null && !bestTool.isEmpty()) {
                float toolSpeed = bestTool.getMiningSpeedMultiplier(blockState);
                if (toolSpeed > 1.0f) {
                    breakSpeed = toolSpeed;
                    int efficiency = Utils.getEnchantmentLevel(bestTool, net.minecraft.enchantment.Enchantments.EFFICIENCY);
                    if (efficiency > 0 && !bestTool.isEmpty()) {
                        breakSpeed += (float)(efficiency * efficiency + 1);
                    }
                }
            }

            if (StatusEffectUtil.hasHaste(mc.player)) {
                breakSpeed *= 1.0f + (float)(StatusEffectUtil.getHasteAmplifier(mc.player) + 1) * 0.2f;
            }

            if (mc.player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
                float penalty = switch (mc.player.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier()) {
                    case 0 -> 0.3f;
                    case 1 -> 0.09f;
                    case 2 -> 0.0027f;
                    default -> 8.1E-4f;
                };
                breakSpeed *= penalty;
            }

            if (mc.player.isSubmergedIn(FluidTags.WATER)) {
                breakSpeed *= (float) mc.player.getAttributeValue(EntityAttributes.SUBMERGED_MINING_SPEED);
            }

            if (!mc.player.isOnGround()) {
                breakSpeed /= 5.0f;
            }

            return breakSpeed / baseDelta / (float) breakDivisor;
        }

        double getBreakProgress() {
            if (mc.player == null || mc.world == null || hardness < 0.0f) return 0.0;
            float speed = calculateBreakSpeed();
            if (speed <= 0.0f) return Integer.MAX_VALUE;
            float ticks = Math.max((float)(System.currentTimeMillis() - startTimeMs) / 50.0f + 1.0f, 1.0f);
            float progress = speed * ticks;
            float threshold = isPrimary ? INSTANCE.breakThreshold.get().floatValue() : 1.0f;
            return Math.min((double)(progress / threshold), 1.0);
        }
    }
}
