package com.griefkit.modules;

import com.griefkit.GriefKit;
import com.griefkit.managers.ToolManager;
import com.griefkit.mixin.ClientWorldAccessor;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
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
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * DoubleMine — Experimental packet miner.
 *
 * Sends START_DESTROY packets to mine two blocks simultaneously. The server
 * tracks mining progress per-block, so sending START on block A then START on
 * block B lets both accumulate break progress in parallel. When a block's
 * progress hits the threshold, a STOP packet is sent and the server breaks it.
 *
 * Double-break: the primary block mines actively while the secondary (previous
 * primary) continues accumulating server-side progress from its earlier START.
 * When both are ready, both break within ticks of each other.
 *
 * Silent swap: sends the STOP packet while temporarily spoofing the selected
 * slot to the best tool, so the server sees the right tool without the client
 * visually switching hotbar slots.
 *
 * Grim bypass: optionally sends a STOP before each START, which resets the
 * server's mining state and avoids anti-cheat flags on some configurations.
 *
 * Queue: additional targets are queued and promoted to primary/secondary as
 * slots free up.
 */
public class DoubleMine extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // === Settings ===
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> doubleBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("double-break")
        .description("Mine two blocks simultaneously. Primary mines actively, secondary accumulates server-side progress from its earlier START.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> grimBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("grim-bypass")
        .description("Send STOP before START to reset server mining state. Helps avoid anti-cheat flags.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> breakThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("break-threshold")
        .description("Progress fraction (0.0–1.0) at which the primary block is considered broken. Lower = faster but riskier.")
        .defaultValue(0.7)
        .range(0.1, 1.0)
        .sliderRange(0.1, 1.0)
        .build()
    );

    private final Setting<Boolean> autoRebreak = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-rebreak")
        .description("If the last broken block reappears (e.g. server rejected), automatically re-mine it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> silentSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-swap")
        .description("Spoof the best tool for the STOP packet without visibly switching hotbar slots.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> validateBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("validate-break")
        .description("Wait for server confirmation before client-side block removal. Safer but slower visual feedback.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> globalRendering = sgRender.add(new BoolSetting.Builder()
        .name("global-rendering")
        .description("Use global render color for break progress boxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(0, 255, 255, 40))
        .visible(() -> !globalRendering.get())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .visible(() -> !globalRendering.get())
        .build()
    );

    public static DoubleMine INSTANCE;

    private MineContext primaryContext;
    private MineContext secondaryContext;
    public BlockPos lastBrokenPos;
    public final Deque<BlockPos> mineQueue = new ArrayDeque<>();

    public DoubleMine() {
        super(GriefKit.CATEGORY, "double-mine",
            "[Experimental] Packet miner — sends START/STOP destroy packets to break blocks faster than vanilla. " +
            "Double-break mines two blocks at once by keeping both server-side progress timers running. " +
            "Silent-swap spoofs the tool for the STOP packet without visible hotbar change. " +
            "Grim-bypass resets server mining state before each START to avoid anti-cheat flags.");
        INSTANCE = this;
    }

    // === Public API ===

    public void addTarget(BlockPos pos) {
        if (mc.world == null) return;
        if (!BlockUtils.canBreak(pos, mc.world.getBlockState(pos))) return;
        if (isOutOfRange(pos)) return;
        if (isTargeted(pos)) return;
        startMining(pos, mc.world.getBlockState(pos));
    }

    public boolean isTargeted(BlockPos pos) {
        if (primaryContext != null && primaryContext.pos.equals(pos)) return true;
        if (secondaryContext != null && secondaryContext.pos.equals(pos)) return true;
        return mineQueue.contains(pos);
    }

    public boolean isBusy() {
        return primaryContext != null && secondaryContext != null && !mineQueue.isEmpty();
    }

    public static void instaBreak(BlockPos pos) {
        if (INSTANCE == null || mc.world == null) return;
        if (INSTANCE.isTargeted(pos)) return;
        MineContext ctx = new MineContext(pos, mc.world.getBlockState(pos), true);
        INSTANCE.sendStartPackets(pos);
        INSTANCE.doFinishBreak(ctx, INSTANCE.silentSwap.get());
    }

    // === Lifecycle ===

    @Override
    public void onDeactivate() {
        primaryContext = null;
        secondaryContext = null;
        mineQueue.clear();
        lastBrokenPos = null;
    }

    // === Core Mining Logic ===

    private void startMining(BlockPos pos, BlockState state) {
        if (isTargeted(pos)) return;

        if (primaryContext != null && (secondaryContext != null || !doubleBreak.get())) {
            if (!mineQueue.contains(pos)) {
                mineQueue.addLast(pos);
            }
            return;
        }

        if (primaryContext == null) {
            primaryContext = new MineContext(pos, state, true);
            sendStartPackets(pos);
        } else if (doubleBreak.get() && secondaryContext == null) {
            // demote primary to secondary, new target becomes primary
            sendAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, primaryContext.pos);
            secondaryContext = new MineContext(primaryContext.pos, primaryContext.state, false);
            primaryContext = new MineContext(pos, state, true);
            sendStartPackets(primaryContext.pos);
        }
    }

    private void sendStartPackets(BlockPos pos) {
        if (grimBypass.get()) {
            sendAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos);
        }
        sendAction(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Auto-rebreak
        if (lastBrokenPos != null && autoRebreak.get()
            && primaryContext == null && secondaryContext == null
            && !mc.world.getBlockState(lastBrokenPos).isAir()) {
            doFinishBreak(new MineContext(lastBrokenPos, mc.world.getBlockState(lastBrokenPos), false), silentSwap.get());
            return;
        }

        pruneCompleted();

        // Equip best tool visibly when not using silent swap
        if (!silentSwap.get() && primaryContext != null && primaryContext.active) {
            equipBestTool(primaryContext.state);
        }

        if (secondaryContext != null && secondaryContext.getProgress() >= 1.0) {
            doFinishBreak(secondaryContext, silentSwap.get());
        }
        if (primaryContext != null && primaryContext.getProgress() >= 1.0) {
            doFinishBreak(primaryContext, silentSwap.get());
        }

        promoteFromQueue();
    }

    private void pruneCompleted() {
        if (primaryContext != null && shouldPrune(primaryContext.pos)) {
            primaryContext = null;
        }
        if (secondaryContext != null && shouldPrune(secondaryContext.pos)) {
            secondaryContext = null;
        }
        mineQueue.removeIf(this::shouldPrune);
    }

    private boolean shouldPrune(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.isAir() || isOutOfRange(pos);
    }

    private void promoteFromQueue() {
        if (mineQueue.isEmpty()) return;

        if (primaryContext == null) {
            BlockPos pos = mineQueue.pollFirst();
            primaryContext = new MineContext(pos, mc.world.getBlockState(pos), true);
            sendStartPackets(pos);
        } else if (doubleBreak.get() && secondaryContext == null) {
            sendAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, primaryContext.pos);
            BlockPos pos = mineQueue.pollFirst();
            secondaryContext = new MineContext(primaryContext.pos, primaryContext.state, false);
            primaryContext = new MineContext(pos, mc.world.getBlockState(pos), true);
            sendStartPackets(primaryContext.pos);
        }
    }

    private void doFinishBreak(MineContext ctx, boolean silent) {
        if (mc.world == null || mc.player == null) return;

        ItemStack bestTool = ToolManager.findBestTool(ctx.state);
        int bestSlot = mc.player.getInventory().getSlotWithStack(bestTool);
        //? if >=1.21.5 {
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        //?} else
        /*int currentSlot = mc.player.getInventory().selectedSlot;*/
        boolean needSwap = bestSlot >= 0 && bestSlot < 9 && bestSlot != currentSlot;

        if (!ctx.canInstaBreak) {
            if (silent && needSwap) {
                ToolManager.silentSwap(bestSlot, () -> sendAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, ctx.pos));
            } else {
                sendAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, ctx.pos);
            }
        }

        // Client-side prediction when validateBreak is off
        if ((ctx.canInstaBreak || ctx.meetsThreshold) && !validateBreak.get()) {
            mc.world.syncWorldEvent(2001, ctx.pos, Block.getRawIdFromState(ctx.state));
            mc.world.setBlockState(ctx.pos, Blocks.AIR.getDefaultState(), 3);
        }

        lastBrokenPos = ctx.pos;
        ctx.active = false;

        if (ctx == primaryContext) {
            primaryContext = null;
        } else if (ctx == secondaryContext) {
            secondaryContext = null;
        }
    }

    private void equipBestTool(BlockState state) {
        if (mc.player == null) return;
        ItemStack bestTool = ToolManager.findBestTool(state);
        if (bestTool.isEmpty()) return;

        int toolSlot = mc.player.getInventory().getSlotWithStack(bestTool);
        if (toolSlot >= 0 && toolSlot < 9) {
            ToolManager.findBestPickaxeSlot(primaryContext.pos);
        } else if (toolSlot >= 9) {
            InvUtils.move().from(toolSlot).to(ToolManager.findEmptyOrRandomHotbarSlot());
        }
    }

    // === Packet helpers ===

    private static int nextSeq() {
        if (mc.world != null) {
            return ((ClientWorldAccessor) mc.world).griefkit$getPendingUpdateManager()
                    .incrementSequence().getSequence();
        }
        return 0;
    }

    public void sendAction(PlayerActionC2SPacket.Action action, BlockPos pos) {
        if (mc.player == null || mc.world == null) return;
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(action, pos, Direction.UP, nextSeq()));
    }

    public boolean isOutOfRange(BlockPos pos) {
        if (mc.player == null) return true;
        return mc.player.getEyePos().distanceTo(pos.toCenterPos()) > 5.5;
    }

    // === Rendering ===

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        Color line = globalRendering.get() ? new Color(0, 255, 255, 200) : lineColor.get();
        Color side = globalRendering.get() ? new Color(0, 255, 255, 40) : sideColor.get();
        ShapeMode shape = ShapeMode.Both;

        for (BlockPos pos : mineQueue) {
            event.renderer.box(pos, Color.WHITE, Color.WHITE, ShapeMode.Lines, 0);
        }

        if (secondaryContext != null) {
            renderMineContext(event, secondaryContext, side, line, shape);
        }
        if (primaryContext != null) {
            renderMineContext(event, primaryContext, side, line, shape);
        }

        if (lastBrokenPos != null && autoRebreak.get() && !mc.world.getBlockState(lastBrokenPos).isAir()) {
            event.renderer.box(lastBrokenPos, side, line, shape, 0);
        }
    }

    private void renderMineContext(Render3DEvent event, MineContext ctx, Color side, Color line, ShapeMode shape) {
        double shrink = (1.0 - ctx.getProgress()) / 2.0;
        Box box = new Box(
            ctx.pos.getX() + shrink, ctx.pos.getY() + shrink, ctx.pos.getZ() + shrink,
            ctx.pos.getX() + 1.0 - shrink, ctx.pos.getY() + 1.0 - shrink, ctx.pos.getZ() + 1.0 - shrink
        );
        event.renderer.box(box, side, line, shape, 0);
    }

    // === MineContext ===

    public static class MineContext {
        public final BlockPos pos;
        public final BlockState state;
        public final long startTimeMs;
        public final float hardness;
        public boolean active = true;
        public final boolean isPrimary;
        public final boolean canInstaBreak;
        public final boolean meetsThreshold;

        public MineContext(BlockPos pos, BlockState state, boolean isPrimary) {
            this.pos = pos.toImmutable();
            this.state = state;
            this.isPrimary = isPrimary;
            this.startTimeMs = System.currentTimeMillis();
            this.hardness = state.getHardness(mc.world, pos);
            this.canInstaBreak = BlockUtils.canInstaBreak(pos);
            this.meetsThreshold = computeBreakSpeed() / INSTANCE.breakThreshold.get().floatValue() >= 1.0;
        }

        public double getProgress() {
            if (mc.player == null || mc.world == null || hardness < 0.0f) return 0.0;

            float speed = computeBreakSpeed();
            if (speed <= 0.0f) return Integer.MAX_VALUE;

            float ticks = Math.max((System.currentTimeMillis() - startTimeMs) / 50.0f + 1.0f, 1.0f);
            float progress = speed * ticks;
            float threshold = isPrimary ? INSTANCE.breakThreshold.get().floatValue() : 1.0f;
            return Math.min(progress / threshold, 1.0);
        }

        private float computeBreakSpeed() {
            if (mc.player == null || mc.world == null) return 0.0f;

            float baseDelta = state.getHardness(mc.world, pos);
            ItemStack bestTool = ToolManager.findBestTool(state);

            int breakDivisor = (!state.isToolRequired() || bestTool.isSuitableFor(state)) ? 30 : 100;

            float breakSpeed = mc.player.getBlockBreakingSpeed(state);

            if (!bestTool.isEmpty()) {
                float toolSpeed = bestTool.getMiningSpeedMultiplier(state);
                if (toolSpeed > 1.0f) {
                    breakSpeed = toolSpeed;
                }
            }

            // Haste bonus
            if (StatusEffectUtil.hasHaste(mc.player)) {
                breakSpeed *= 1.0f + (StatusEffectUtil.getHasteAmplifier(mc.player) + 1) * 0.2f;
            }

            // Mining Fatigue penalty
            if (mc.player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
                int amplifier = mc.player.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier();
                breakSpeed *= switch (amplifier) {
                    case 0 -> 0.3f;
                    case 1 -> 0.09f;
                    case 2 -> 0.0027f;
                    default -> 8.1E-4f;
                };
            }

            // Submerged in water penalty
            if (mc.player.isSubmergedIn(FluidTags.WATER)) {
                breakSpeed *= (float) mc.player.getAttributeValue(EntityAttributes.SUBMERGED_MINING_SPEED);
            }

            // Not on ground penalty
            if (!mc.player.isOnGround()) {
                breakSpeed /= 5.0f;
            }

            return breakSpeed / baseDelta / (float) breakDivisor;
        }
    }
}
