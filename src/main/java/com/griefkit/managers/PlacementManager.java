/*
 * PlacementManager
 *
 * Purpose:
 * - Takes PlacementStep requests (pos + block + "support face") and schedules
 *   controlled placement attempts over time.
 * - Uses a token-bucket rate limiter (MAX_PER_WINDOW per WINDOW_MS) so you don't
 *   spam packets / hit server limits.
 * - Tracks "pending confirmations" keyed by BlockPos and uses BlockUpdate packets
 *   (plus a timeout fallback) to decide whether a placement succeeded.
 *
 * High-level flow each tick:
 *  1) Refill tokens (rate limiter).
 *  2) Resolve pending confirmations (success, timeout -> retry, or prune).
 *  3) While tokens allow, pop queue entries and try to place them.
 *  4) Restore original hotbar slot once idle.
 *
 * Notes:
 * - This class subscribes to MeteorClient.EVENT_BUS; make sure your addon registers
 *   the Orbit lambda factory for "com.griefkit" before constructing this manager.
 */
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
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;

public class PlacementManager {
    /*
     * Rate limiting + pacing constants.
     *
     * MAX_PER_WINDOW and WINDOW_MS represent your intended placement cap.
     * You're implementing this as a token bucket with:
     *   capacity = MAX_PER_WINDOW
     *   refillRatePerMs = MAX_PER_WINDOW / WINDOW_MS
     *
     * MAX_ATTEMPTS_PER_TICK is a safety stop so you don't loop forever if the
     * queue is full but every attempt is blocked by conditions.
     */
    private static final int MAX_PER_WINDOW = 9;
    private static final long WINDOW_MS = 325L;
    private static final int MAX_ATTEMPTS_PER_TICK = 64;

    // How many times we'll retry a placement after failing to confirm.
    private static final int MAX_RETRIES = 2;

    // How long we wait for the world to confirm a placement via BlockUpdate packet.
    private static final long CONFIRM_TIMEOUT_MS = 200L;

    /*
     * Sequence number used in PlayerInteractBlockC2SPacket
     * (newer MC versions include an interaction sequence).
     */
    private int interactSeq = 0;

    /*
     * Slot restore logic:
     * When we start placing, we capture the player's current hotbar slot.
     * When we're fully idle (queue empty + pending empty), we return them to it.
     */
    private int restoreHotbarSlot = -1;

    private final InventoryManager inventory;

    /*
     * Queue of requested placements.
     * Each entry includes:
     *  - step: what to place and where
     *  - retriesLeft: how many confirm-timeout retries remain if we send but don't confirm
     */
    private final Deque<Queued> queue = new ArrayDeque<>();

    /*
     * Pending placements awaiting confirmation from the server/client.
     * Keyed by target BlockPos, because block updates come in by position.
     *
     * Each Pending stores:
     *  - step: original step
     *  - retriesLeft: retries remaining after timeout
     *  - sentAtMs: when we sent the interact packet
     */
    private final Map<BlockPos, Pending> pending = new HashMap<>();

    /*
     * Token bucket:
     * - tokens = current "budget" of placements we can send.
     * - refillRatePerMs = MAX_PER_WINDOW / WINDOW_MS
     * - lastRefillMs tracks last refill time.
     */
    private final double refillRatePerMs = 0.027692307692307693; // 9 / 325
    private double tokens = 9.0;
    private long lastRefillMs = 0L;

    /**
     * Useful for HUD / debugging: snapshot a portion of the queue.
     */
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

    /**
     * Constructor subscribes to Meteor event bus.
     * Make sure you registered the Orbit lambda factory (in GriefKit.onInitialize)
     * before instantiating PlacementManager.
     */
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

    /*
     * Debug/prune logging.
     * "Prune" means: we discard a step and record why, so you can diagnose
     * mismapping, out-of-reach, wrong block in hand, etc.
     */
    private void prune(PlacementStep step, String reason) {
        if (step == null) {
            System.out.println("[PlacementManager] PRUNED <null step> reason=" + reason);
            return;
        }

        String pos = step.pos == null ? "null" : step.pos.toShortString();
        String block = step.block == null ? "null" : step.block.toString();
        String face = step.supportFace == null ? "null" : step.supportFace.toString();

        // NOTE: supportPos is logged as step.pos in your decompile; likely you meant
        // to log a separate "supportPos" if PlacementStep had one. Right now it repeats pos.
        String supportPos = step.pos == null ? "null" : step.pos.toShortString();

        System.out.println(
            "[PlacementManager] PRUNED pos=" + pos +
                " block=" + block +
                " supportPos=" + supportPos +
                " face=" + face +
                " reason=" + reason
        );
    }

    /**
     * When we fail OUT_OF_REACH, dump geometry so you can see which part is wrong:
     * - eye position
     * - computed hit position
     * - target/support position
     * - face used for the BlockHitResult
     */
    private void logOutOfReach(MinecraftClient mc, PlacementStep step, Vec3d hitPos) {
        if (mc.player == null || step == null) return;

        Vec3d eye = mc.player.getEyePos();

        // "reach" is a chosen constant; vanilla survival uses ~4.5, creative bigger.
        // Your code uses 5.154 which likely matches Meteor's computed reach or a tuned value.
        double reach = 5.154;
        double margin = 0.1;

        double maxSq = (reach + margin) * (reach + margin);
        double distSq = eye.squaredDistanceTo(hitPos);

        BlockPos supportPos = step.pos == null ? null : step.pos;

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

    /**
     * Enqueue a placement step with MAX_RETRIES retries.
     * (Your decompiled code hardcodes 2 rather than using MAX_RETRIES.)
     */
    public void enqueue(PlacementStep step) {
        if (step == null || step.pos == null || step.block == null) {
            this.prune(step, "MALFORMED_ENQUEUE");
            return;
        }
        this.queue.addLast(new Queued(step, MAX_RETRIES));
    }

    /**
     * Hard reset state.
     */
    public void clear() {
        this.queue.clear();
        this.pending.clear();
        this.restoreHotbarSlot = -1;
    }

    /**
     * Confirmation path:
     * Listen for BlockUpdate packets; if we have a Pending at that pos and the
     * block now matches, we consider it confirmed and remove it.
     */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> pkt = event.packet;
        if (!(pkt instanceof BlockUpdateS2CPacket)) return;

        BlockUpdateS2CPacket packet = (BlockUpdateS2CPacket) pkt;

        Pending p = this.pending.get(packet.getPos());
        if (p == null) return;

        // If server says the block at that position is now the block we wanted, confirm success.
        if (packet.getState().getBlock() == p.step.block) {
            this.pending.remove(packet.getPos());
        }
    }

    /**
     * Main work loop. Runs every client tick (pre).
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();

        // Token bucket refill.
        if (this.lastRefillMs == 0L) this.lastRefillMs = now;

        long elapsed = now - this.lastRefillMs;
        if (elapsed > 0L) {
            this.tokens = Math.min(
                (double) MAX_PER_WINDOW,
                this.tokens + elapsed * this.refillRatePerMs
            );
            this.lastRefillMs = now;
        }

        /*
         * Pending confirmation maintenance:
         * - If confirmed by world state: remove
         * - If timed out: either retry (requeue), or prune if retries exhausted
         */
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

                // Still waiting, not timed out yet
                if (now - p.sentAtMs < CONFIRM_TIMEOUT_MS) continue;

                // If the world state already matches, treat as confirmed success and remove.
                if (mc.world.getBlockState(pos).getBlock() == p.step.block) {
                    it.remove();
                    continue;
                }

                // Timeout without confirmation -> remove pending and either retry or prune.
                it.remove();

                if (p.retriesLeft > 0) {
                    this.pendingRetryAppend(p.step, p.retriesLeft - 1);
                } else {
                    this.prune(p.step, "CONFIRM_TIMEOUT_RETRIES_EXHAUSTED");
                }
            }
        }

        /*
         * If nothing queued, attempt to restore original hotbar slot once
         * there are no pending confirmations either.
         */
        if (this.queue.isEmpty()) {
            if (this.pending.isEmpty() && this.restoreHotbarSlot != -1) {
                int cur = mc.player.getInventory().getSelectedSlot();
                if (cur != this.restoreHotbarSlot) {
                    this.inventory.ensureSelectedSlot(mc.player, this.restoreHotbarSlot);
                }
                this.restoreHotbarSlot = -1;
            }
            return;
        }

        // If we have no budget to send, wait until next tick for refill.
        if (this.tokens < 1.0) return;

        int attempts = 0;

        /*
         * Try to send as many placements as allowed:
         * - Must have >= 1 token
         * - Must have items in queue
         * - Limited attempts per tick for safety
         */
        while (this.tokens >= 1.0 && !this.queue.isEmpty() && attempts < MAX_ATTEMPTS_PER_TICK) {
            ++attempts;

            Queued q = this.queue.peekFirst();
            if (q == null || q.step == null) {
                this.queue.pollFirst();
                this.prune(null, "NULL_QUEUE_ENTRY");
                continue;
            }

            PlacementStep step = q.step;

            // If already placed, discard.
            if (mc.world.getBlockState(step.pos).getBlock() == step.block) {
                this.queue.pollFirst();
                this.prune(step, "ALREADY_PLACED");
                continue;
            }

            // If not replaceable, discard (someone placed there, or target is solid).
            if (!mc.world.getBlockState(step.pos).isReplaceable()) {
                this.queue.pollFirst();
                this.prune(step, "NOT_REPLACEABLE_WORLDSTATE");
                continue;
            }

            // Find the block in hotbar.
            int neededSlot = this.inventory.findHotbarSlot(mc.player.getInventory(), item -> {
                if (!(item instanceof BlockItem)) return false;
                return ((BlockItem) item).getBlock() == step.block;
            });

            if (neededSlot == -1) {
                this.queue.pollFirst();
                this.prune(step, Fail.MISSING_BLOCK_IN_HOTBAR);
                continue;
            }

            // Capture original slot the first time we have to switch slots.
            int currentSlot = mc.player.getInventory().getSelectedSlot();
            if (this.restoreHotbarSlot == -1) this.restoreHotbarSlot = currentSlot;

            // Switch to the required slot.
            if (currentSlot != neededSlot) {
                this.inventory.ensureSelectedSlot(mc.player, neededSlot);

                // If slot switch didn't take effect yet (client/server delay), stop for this tick.
                if (mc.player.getInventory().getSelectedSlot() != neededSlot) return;
            }

            // We are going to attempt this step now; remove from queue.
            this.queue.pollFirst();

            // Attempt to place using an "offhand interact packet" method (see below).
            Result res = this.airplaceOffhandSwapRawPacket(mc, step);

            if (res.sent()) {
                // Sent a packet -> consume a token and create a pending confirmation record.
                this.tokens -= 1.0;
                BlockPos key = step.pos.toImmutable();
                this.pending.putIfAbsent(key, new Pending(step, q.retriesLeft, now));
                continue;
            }

            // If ok but didn't send (rare path), keep going.
            if (res.value()) continue;

            // Hard fail: out of reach is not transient; prune.
            if (res.fail() == Fail.OUT_OF_REACH) {
                this.prune(step, Fail.OUT_OF_REACH);
                continue;
            }

            /*
             * Transient failures -> requeue at front and stop processing more this tick.
             * (Idea: don't churn the queue; wait for conditions to improve next tick.)
             */
            if (res.fail() == Fail.CHUNK_NOT_LOADED
                || res.fail() == Fail.OFFHAND_SWAP_LOCKED
                || res.fail() == Fail.NO_INTERACTION_MANAGER
                || res.fail() == Fail.NO_PLAYER
                || res.fail() == Fail.NO_WORLD) {
                this.queue.addFirst(q);
                break;
            }

            // Everything else is a permanent-ish fail; prune it.
            this.prune(step, res.fail());
        }

        // If we just became fully idle, restore slot.
        if (this.queue.isEmpty() && this.pending.isEmpty() && this.restoreHotbarSlot != -1) {
            int cur = mc.player.getInventory().getSelectedSlot();
            if (cur != this.restoreHotbarSlot) {
                this.inventory.ensureSelectedSlot(mc.player, this.restoreHotbarSlot);
            }
            this.restoreHotbarSlot = -1;
        }
    }

    /**
     * Requeue a step after a confirm-timeout, but only if it still needs placing.
     */
    private void pendingRetryAppend(PlacementStep step, int retriesLeft) {
        if (step == null || step.pos == null || step.block == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        // If it got placed meanwhile, don't requeue.
        if (mc.world != null && mc.world.getBlockState(step.pos).getBlock() == step.block) return;

        this.queue.addLast(new Queued(step, retriesLeft));
    }

    /**
     * Attempt to place by sending PlayerInteractBlockC2SPacket using OFF_HAND,
     * while InventoryManager performs some "offhand swap lock".
     *
     * What this is trying to do (in plain terms):
     * - Ensure we are holding the correct BlockItem (mainhand check).
     * - Ensure chunk is loaded, height valid, target replaceable, entity not blocking.
     * - Compute a "hit position" and BlockHitResult for the support block.
     * - Check reach distance (client-side) so we don't send a packet that can't work.
     * - Acquire an offhand swap lock, send the interact packet as OFF_HAND, release lock.
     *
     * Caveat:
     * - You are validating "main hand has the right block", but you send OFF_HAND packet.
     *   That’s *fine* if your InventoryManager temporarily swaps mainhand/offhand or the
     *   server interprets it in a specific way, but it’s worth double-checking:
     *   If you truly mean "place from mainhand", use Hand.MAIN_HAND.
     *   If you truly mean "place from offhand", validate offhand stack instead.
     */
    public Result airplaceOffhandSwapRawPacket(MinecraftClient mc, PlacementStep step) {
        if (mc.player == null) return Result.fail(Fail.NO_PLAYER);
        if (mc.world == null) return Result.fail(Fail.NO_WORLD);
        if (mc.interactionManager == null) return Result.fail(Fail.NO_INTERACTION_MANAGER);

        if (step == null || step.pos == null) return Result.fail(Fail.NULL_SUPPORT);
        if (step.supportFace == null) return Result.fail(Fail.NULL_SUPPORT);

        Direction supportFace = step.supportFace;

        // NOTE: decompile uses supportPos = step.pos; usually "supportPos" is a neighbor block.
        // In your step model you might have intended something like step.supportPos.
        BlockPos supportPos = step.pos;

        // Validate Y bounds.
        if (!World.isValid(step.pos) || !World.isValid(supportPos)) {
            return Result.fail(Fail.INVALID_HEIGHT);
        }

        // Avoid interacting with unloaded chunks.
        if (!mc.world.isChunkLoaded(step.pos) || !mc.world.isChunkLoaded(supportPos)) {
            return Result.fail(Fail.CHUNK_NOT_LOADED);
        }

        // Target must be replaceable.
        if (!mc.world.getBlockState(step.pos).isReplaceable()) {
            return Result.fail(Fail.NOT_REPLACEABLE);
        }

        // Verify held item.
        Item held = mc.player.getMainHandStack().getItem();
        if (!(held instanceof BlockItem)) {
            return Result.fail(Fail.MAINHAND_NOT_BLOCKITEM);
        }

        BlockItem bi = (BlockItem) held;
        if (bi.getBlock() != step.block) {
            return Result.fail(Fail.MAINHAND_WRONG_BLOCK);
        }

        Block heldBlock = bi.getBlock();

        // Ensure no entity collision blocks placement.
        if (!mc.world.canPlace(heldBlock.getDefaultState(), step.pos, ShapeContext.absent())) {
            return Result.fail(Fail.ENTITY_BLOCKING);
        }

        /*
         * Compute hit position on the support block face.
         * - Center of support block + half a block towards the face normal.
         */
        Vec3d hitPos = Vec3d.ofCenter((Vec3i) supportPos)
            .add(Vec3d.of((Vec3i) supportFace.getVector()).multiply(0.5));

        BlockHitResult bhr = new BlockHitResult(hitPos, supportFace, supportPos, false);

        // Client-side reach check (prevents "obviously impossible" packets).
        double reach = 5.154;
        double margin = 0.1;
        double maxSq = (reach + margin) * (reach + margin);

        if (mc.player.getEyePos().squaredDistanceTo(hitPos) > maxSq) {
            this.logOutOfReach(mc, step, hitPos);
            return Result.fail(Fail.OUT_OF_REACH);
        }

        // Acquire lock (prevents concurrent inventory/offhand manipulation).
        if (!this.inventory.beginOffhandSwap(mc.player)) {
            return Result.fail(Fail.OFFHAND_SWAP_LOCKED);
        }

        try {
            // Send interact packet using OFF_HAND.
            mc.player.networkHandler.sendPacket(
                (Packet<?>) new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, bhr, this.nextInteractSeq())
            );

            // "log" from decompile; likely you had a debug log here.
            System.out.println("log");

            return Result.okSent();
        } finally {
            this.inventory.endOffhandSwap(mc.player);
        }
    }

    /*
     * Queue entry: the work item + retry budget.
     */
    private record Queued(PlacementStep step, int retriesLeft) {}

    /*
     * Placement failure reasons.
     * You use this both for pruning and for requeue decisions.
     */
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

    /*
     * Pending placement record.
     */
    private record Pending(PlacementStep step, int retriesLeft, long sentAtMs) {}

    /*
     * Result wrapper:
     * - value: success/failure of the attempt
     * - fail: which Fail if any
     * - sent: whether we actually sent an interact packet (consumes token + creates pending)
     */
    public record Result(boolean value, Fail fail, boolean sent) {
        public static Result okSent() { return new Result(true, null, true); }
        public static Result okNoSend() { return new Result(true, null, false); }
        public static Result fail(Fail fail) { return new Result(false, fail, false); }
    }

    /*
     * Looks like a debugging/analytics record that never gets used in this class.
     * You could store these for a HUD / debug overlay if you want.
     */
    public record AirplaceAttempt(
        long timeMs,
        BlockPos targetPos,
        Block targetBlock,
        BlockPos supportPos,
        Direction supportFace,
        Hand hand
    ) {}
}
