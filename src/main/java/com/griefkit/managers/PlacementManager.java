package com.griefkit.managers;

import com.griefkit.placement.PlacementStep;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class PlacementManager {
    private static final int MAX_PER_WINDOW = 9;
    private static final long WINDOW_MS = 325L;
    private static final int MAX_ATTEMPTS_PER_TICK = 64;

    private static final int MAX_RETRIES = 2;
    private static final long CONFIRM_TIMEOUT_MS = 200L;

    private int interactSeq = 0;

    private final InventoryManager inventory;

    private final Deque<Queued> queue = new ArrayDeque<>();
    private final Map<BlockPos, Pending> pending = new HashMap<>();

    // 9 / 325, computed from the constants (only one cast needed)
    private final double refillRatePerMs = MAX_PER_WINDOW / (double) WINDOW_MS;
    private double tokens = 9.0;
    private long lastRefillMs = 0L;

    // Tracks whether we've started a hotbar swap session with InventoryManager.
    private boolean hotbarSwapSessionActive = false;

    public List<PlacementStep> getQueueSnapshot(int limit) {
        ArrayList<PlacementStep> out = new ArrayList<>(Math.min(limit, this.queue.size()));
        int i = 0;
        for (Queued q : this.queue) {
            if (i++ >= limit) break;
            out.add(q.step);
        }
        return out;
    }

    private int nextInteractSeq() {
        return ++this.interactSeq;
    }

    public PlacementManager(InventoryManager inventory) {
        this.inventory = inventory;
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public boolean isIdle() {
        return this.queue.isEmpty() && this.pending.isEmpty();
    }

    public int queuedCount() {
        return this.queue.size();
    }

    /**
     * Resolve support position:
     * - Prefer explicit step.supportPos (new behavior)
     * - Else treat target pos as support (legacy).
     */
    private BlockPos resolveSupportPos(PlacementStep step) {
        if (step == null) return null;

        if (step.supportPos != null) return step.supportPos;

        // Legacy behavior: supportPos was effectively treated as step.pos
        return step.pos;
    }

    private void prune(PlacementStep step, String reason) {
        if (step == null) {
            System.out.println("[PlacementManager] PRUNED <null step> reason=" + reason);
            return;
        }

        String pos = step.pos == null ? "null" : step.pos.toShortString();
        String block = step.block == null ? "null" : step.block.toString();
        String face = step.supportFace == null ? "null" : step.supportFace.toString();

        BlockPos sp = this.resolveSupportPos(step);
        String supportPos = sp == null ? "null" : sp.toShortString();

        System.out.println(
            "[PlacementManager] PRUNED pos=" + pos +
                " block=" + block +
                " supportPos=" + supportPos +
                " face=" + face +
                " reason=" + reason
        );
    }

    private void logOutOfReach(MinecraftClient mc, PlacementStep step, Vec3d hitPos) {
        if (mc.player == null || step == null) return;

        Vec3d eye = mc.player.getEyePos();

        double reach = 5.154;
        double margin = 0.1;
        double maxSq = (reach + margin) * (reach + margin);
        double distSq = eye.squaredDistanceTo(hitPos);

        BlockPos supportPos = this.resolveSupportPos(step);

        System.out.println(
            "[PlacementManager] OUT_OF_REACH\n" +
                "  eyePos=" + eye + "\n" +
                "  hitPos=" + hitPos + "\n" +
                "  targetPos=" + (step.pos == null ? "null" : step.pos.toShortString()) + "\n" +
                "  supportPos=" + (supportPos == null ? "null" : supportPos.toShortString()) +
                " face=" + step.supportFace + "\n" +
                "  distSq=" + distSq + " maxSq=" + maxSq
        );
    }

    private void prune(PlacementStep step, Fail fail) {
        this.prune(step, "FAIL_" + (fail == null ? "null" : fail.name()));
    }

    public void enqueue(PlacementStep step) {
        if (step == null || step.pos == null || step.block == null) {
            this.prune(step, "MALFORMED_ENQUEUE");
            return;
        }
        this.queue.addLast(new Queued(step, MAX_RETRIES));
    }

    public void clear() {
        this.queue.clear();
        this.pending.clear();
        this.hotbarSwapSessionActive = false;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> pkt = event.packet;
        if (!(pkt instanceof BlockUpdateS2CPacket)) return;

        BlockUpdateS2CPacket packet = (BlockUpdateS2CPacket) pkt;

        Pending p = this.pending.get(packet.getPos());
        if (p == null) return;

        Block updated = packet.getState().getBlock();

        // Normal confirmation: block now matches the desired one
        if (updated == p.step.block) {
            this.pending.remove(packet.getPos());
            return;
        }

        // SPECIAL CASE: wither skulls can be consumed instantly by wither spawn,
        // resulting in an update to AIR instead of SKULL. Treat that as success.
        if (p.step.block == Blocks.WITHER_SKELETON_SKULL && updated == Blocks.AIR) {
            this.pending.remove(packet.getPos());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();

        if (this.lastRefillMs == 0L) this.lastRefillMs = now;

        long elapsed = now - this.lastRefillMs;
        if (elapsed > 0L) {
            this.tokens = Math.min(
                MAX_PER_WINDOW,
                this.tokens + elapsed * this.refillRatePerMs
            );
            this.lastRefillMs = now;
        }

        // Maintain pending confirmations (timeout / retry / prune)
        if (!this.pending.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Pending>> it = this.pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Pending> e = it.next();
                BlockPos pos = e.getKey();
                Pending p = e.getValue();

                if (p == null) {
                    it.remove();
                    continue;
                }

                if (now - p.sentAtMs < CONFIRM_TIMEOUT_MS) continue;

                Block current = mc.world.getBlockState(pos).getBlock();

                // Normal success: block matches
                if (current == p.step.block) {
                    it.remove();
                    continue;
                }

                // SPECIAL CASE: wither skulls disappear when the wither spawns;
                if (p.step.block == Blocks.WITHER_SKELETON_SKULL && current == Blocks.AIR) {
                    it.remove();
                    continue;
                }

                // Otherwise, timeout -> retry or prune
                it.remove();

                if (p.retriesLeft > 0) {
                    this.pendingRetryAppend(p.step, p.retriesLeft - 1);
                } else {
                    this.prune(p.step, "CONFIRM_TIMEOUT_RETRIES_EXHAUSTED");
                }
            }
        }

        // If queue is empty, potentially end hotbar swap session when pending also empty.
        if (this.queue.isEmpty()) {
            if (this.pending.isEmpty() && this.hotbarSwapSessionActive) {
                this.inventory.endHotbarSwapSession(mc.player);
                this.hotbarSwapSessionActive = false;
            }
            return;
        }

        // Ensure we have a hotbar swap session active while we manipulate slots.
        if (!this.hotbarSwapSessionActive) {
            if (this.inventory.beginHotbarSwapSession(mc.player)) {
                this.hotbarSwapSessionActive = true;
            }
        }

        if (this.tokens < 1.0) return;

        int attempts = 0;

        while (this.tokens >= 1.0 && !this.queue.isEmpty() && attempts < MAX_ATTEMPTS_PER_TICK) {
            ++attempts;

            Queued q = this.queue.peekFirst();
            if (q == null || q.step == null) {
                this.queue.pollFirst();
                this.prune(null, "NULL_QUEUE_ENTRY");
                continue;
            }

            PlacementStep step = q.step;

            if (mc.world.getBlockState(step.pos).getBlock() == step.block) {
                this.queue.pollFirst();
                this.prune(step, "ALREADY_PLACED");
                continue;
            }

            if (!mc.world.getBlockState(step.pos).isReplaceable()) {
                this.queue.pollFirst();
                this.prune(step, "NOT_REPLACEABLE_WORLDSTATE");
                continue;
            }

            int neededSlot = this.inventory.findHotbarSlot(mc.player.getInventory(), item -> {
                if (!(item instanceof BlockItem)) return false;
                return ((BlockItem) item).getBlock() == step.block;
            });

            if (neededSlot == -1) {
                this.queue.pollFirst();
                this.prune(step, Fail.MISSING_BLOCK_IN_HOTBAR);
                continue;
            }

            // We’re in a hotbar swap session now; just ensure we’re holding the right slot.
            this.inventory.ensureSelectedSlot(mc.player, neededSlot);
            // If selection didn't "stick" (weird client/server lag), bail out this tick.
            if (mc.player.getInventory().getSelectedSlot() != neededSlot) return;

            this.queue.pollFirst();

            Result res = this.airplaceOffhandSwapRawPacket(mc, step);

            if (res.sent()) {
                this.tokens -= 1.0;
                BlockPos key = step.pos.toImmutable();
                this.pending.putIfAbsent(key, new Pending(step, q.retriesLeft, now));
                continue;
            }

            if (res.value()) continue;

            if (res.fail() == Fail.OUT_OF_REACH) {
                this.prune(step, Fail.OUT_OF_REACH);
                continue;
            }

            if (res.fail() == Fail.CHUNK_NOT_LOADED
                || res.fail() == Fail.OFFHAND_SWAP_LOCKED
                || res.fail() == Fail.NO_INTERACTION_MANAGER
                || res.fail() == Fail.NO_PLAYER
                || res.fail() == Fail.NO_WORLD) {
                this.queue.addFirst(q);
                break;
            }

            this.prune(step, res.fail());
        }

        // If we just became fully idle, end the hotbar swap session.
        if (this.queue.isEmpty() && this.pending.isEmpty() && this.hotbarSwapSessionActive) {
            this.inventory.endHotbarSwapSession(mc.player);
            this.hotbarSwapSessionActive = false;
        }
    }

    private void pendingRetryAppend(PlacementStep step, int retriesLeft) {
        if (step == null || step.pos == null || step.block == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.world != null && mc.world.getBlockState(step.pos).getBlock() == step.block) return;

        this.queue.addLast(new Queued(step, retriesLeft));
    }

    public Result airplaceOffhandSwapRawPacket(MinecraftClient mc, PlacementStep step) {
        if (mc.player == null) return Result.fail(Fail.NO_PLAYER);
        if (mc.world == null) return Result.fail(Fail.NO_WORLD);
        if (mc.interactionManager == null) return Result.fail(Fail.NO_INTERACTION_MANAGER);

        if (step == null || step.pos == null) return Result.fail(Fail.NULL_SUPPORT);
        if (step.supportFace == null) return Result.fail(Fail.NULL_SUPPORT);

        // Skulls -> their soul sand; legacy blocks -> their own target pos.
        BlockPos supportPos = this.resolveSupportPos(step);
        if (supportPos == null) return Result.fail(Fail.NULL_SUPPORT);

        Direction supportFace = step.supportFace;

        if (!World.isValid(step.pos) || !World.isValid(supportPos)) {
            return Result.fail(Fail.INVALID_HEIGHT);
        }

        if (!mc.world.isChunkLoaded(step.pos) || !mc.world.isChunkLoaded(supportPos)) {
            return Result.fail(Fail.CHUNK_NOT_LOADED);
        }

        if (!mc.world.getBlockState(step.pos).isReplaceable()) {
            return Result.fail(Fail.NOT_REPLACEABLE);
        }

        Item held = mc.player.getMainHandStack().getItem();
        if (!(held instanceof BlockItem)) {
            return Result.fail(Fail.MAINHAND_NOT_BLOCKITEM);
        }

        BlockItem bi = (BlockItem) held;
        if (bi.getBlock() != step.block) {
            return Result.fail(Fail.MAINHAND_WRONG_BLOCK);
        }

        Block heldBlock = bi.getBlock();

        if (!mc.world.canPlace(heldBlock.getDefaultState(), step.pos, ShapeContext.absent())) {
            return Result.fail(Fail.ENTITY_BLOCKING);
        }

        Vec3d hitPos = Vec3d.ofCenter(supportPos)
            .add(Vec3d.of(supportFace.getVector()).multiply(0.5));

        BlockHitResult bhr = new BlockHitResult(hitPos, supportFace, supportPos, false);

        double reach = 5.154;
        double margin = 0.1;
        double maxSq = (reach + margin) * (reach + margin);

        if (mc.player.getEyePos().squaredDistanceTo(hitPos) > maxSq) {
            this.logOutOfReach(mc, step, hitPos);
            return Result.fail(Fail.OUT_OF_REACH);
        }

        if (!this.inventory.beginOffhandSwap(mc.player)) {
            return Result.fail(Fail.OFFHAND_SWAP_LOCKED);
        }

        try {
            mc.player.networkHandler.sendPacket(
                new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, bhr, this.nextInteractSeq())
            );
            System.out.println("log");
            return Result.okSent();
        } finally {
            this.inventory.endOffhandSwap(mc.player);
        }
    }

    private record Queued(PlacementStep step, int retriesLeft) {}

    public static enum Fail {
        NO_PLAYER,
        NO_WORLD,
        NO_INTERACTION_MANAGER,
        CHUNK_NOT_LOADED,
        INVALID_HEIGHT,
        NULL_SUPPORT,
        ENTITY_BLOCKING,
        OUT_OF_REACH,
        NOT_REPLACEABLE,
        MISSING_BLOCK_IN_HOTBAR,
        MAINHAND_NOT_BLOCKITEM,
        MAINHAND_WRONG_BLOCK,
        OFFHAND_SWAP_LOCKED;
    }

    private record Pending(PlacementStep step, int retriesLeft, long sentAtMs) {}

    public record Result(boolean value, Fail fail, boolean sent) {
        public static Result okSent() { return new Result(true, null, true); }
        public static Result okNoSend() { return new Result(true, null, false); }
        public static Result fail(Fail fail) { return new Result(false, fail, false); }
    }
}
