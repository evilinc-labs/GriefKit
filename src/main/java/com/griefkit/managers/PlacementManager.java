package com.griefkit.managers;

import com.griefkit.placement.PlacementStep;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class PlacementManager {
    private static final int MAX_PER_WINDOW = 9;
    private static final long WINDOW_MS = 325L;

    // safety guard: don't spin forever on invalid queued steps in a single tick.
    private static final int MAX_ATTEMPTS_PER_TICK = 64;

    // placement confirmation / retries
    private static final int MAX_RETRIES = 2;                 // "retry x more times"
    private static final long CONFIRM_TIMEOUT_MS = 200;

    private int interactSeq = 0;// wait for BlockUpdate before deciding it failed

    // restore selected slot after placements finish
    private int restoreHotbarSlot = -1;

    public enum Fail {
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
        OFFHAND_SWAP_LOCKED
    }

    public record AirplaceAttempt(
        long timeMs,
        BlockPos targetPos,
        Block targetBlock,
        BlockPos supportPos,
        net.minecraft.util.math.Direction supportFace,
        net.minecraft.util.Hand hand
    ) {}

    // queue entries carry retry budget forward.
    private record Queued(PlacementStep step, int retriesLeft) {}

    public List<PlacementStep> getQueueSnapshot(int limit) {
        List<PlacementStep> out = new ArrayList<>(Math.min(limit, queue.size()));
        int i = 0;
        for (Queued q : queue) {
            if (i++ >= limit) break;
            out.add(q.step);
        }
        return out;
    }

    private int nextInteractSeq() {
        return ++interactSeq;
    }

    public record Result(boolean value, Fail fail, boolean sent) {
        public static Result okSent() { return new Result(true, null, true); }
        public static Result okNoSend() { return new Result(true, null, false); }
        public static Result fail(Fail fail) { return new Result(false, fail, false); }
    }

    private record Pending(PlacementStep step, int retriesLeft, long sentAtMs) {}

    private final InventoryManager inventory;

    private final Deque<Queued> queue = new ArrayDeque<>();
    private final Map<BlockPos, Pending> pending = new HashMap<>();

    private final double refillRatePerMs = (double) MAX_PER_WINDOW / (double) WINDOW_MS;
    private double tokens = MAX_PER_WINDOW;
    private long lastRefillMs = 0L;

    public PlacementManager(InventoryManager inventory) {
        this.inventory = inventory;
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public boolean isIdle() {
        return queue.isEmpty() && pending.isEmpty();
    }

    public int queuedCount() {
        return queue.size();
    }

    private void prune(PlacementStep step, String reason) {
        if (step == null) {
            System.out.println("[PlacementManager] PRUNED <null step> reason=" + reason);
            return;
        }

        String pos = (step.pos == null) ? "null" : step.pos.toShortString();
        String block = (step.block == null) ? "null" : step.block.toString();

        String face = (step.supportFace == null) ? "null" : step.supportFace.toString();
        String supportPos = (step.pos == null) ? "null" : step.pos.toShortString();

        System.out.println("[PlacementManager] PRUNED pos=" + pos
            + " block=" + block
            + " supportPos=" + supportPos
            + " face=" + face
            + " reason=" + reason
        );
    }

    private void logOutOfReach(MinecraftClient mc, PlacementStep step, Vec3d hitPos) {
        if (mc.player == null || step == null) return;

        Vec3d eye = mc.player.getEyePos();

        double reach = 5.154;
        double margin = 0.1;
        double maxSq = (reach + margin) * (reach + margin);
        double distSq = eye.squaredDistanceTo(hitPos);

        BlockPos supportPos = (step.pos == null) ? null : step.pos;

        System.out.println(
            "[PlacementManager] OUT_OF_REACH\n"
                + "  eyePos=" + eye + "\n"
                + "  hitPos=" + hitPos + "\n"
                + "  targetPos=" + (step.pos == null ? "null" : step.pos.toShortString()) + "\n"
                + "  supportPos=" + (supportPos == null ? "null" : supportPos.toShortString())
                + " face=" + step.supportFace + "\n"
                + "  distSq=" + distSq + " maxSq=" + maxSq
        );
    }

    private void prune(PlacementStep step, Fail fail) {
        prune(step, "FAIL_" + (fail == null ? "null" : fail.name()));
    }

    public void enqueue(PlacementStep step) {
        // malformed steps get dropped permanently
        if (step == null || step.pos == null || step.block == null) {
            prune(step, "MALFORMED_ENQUEUE");
            return;
        }
        queue.addLast(new Queued(step, MAX_RETRIES));
    }

    public void clear() {
        queue.clear();
        pending.clear();
        restoreHotbarSlot = -1;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof BlockUpdateS2CPacket packet)) return;

        Pending p = pending.get(packet.getPos());
        if (p == null) return;

        // success case: server says the expected block is now there.
        if (packet.getState().getBlock() == p.step.block) {
            pending.remove(packet.getPos());
        }
        // else: ignore here (we'll handle failure via timeout + world state check)
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();

        if (lastRefillMs == 0L) lastRefillMs = now;
        long elapsed = now - lastRefillMs;
        if (elapsed > 0) {
            tokens = Math.min(MAX_PER_WINDOW, tokens + elapsed * refillRatePerMs);
            lastRefillMs = now;
        }

        if (!pending.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Pending>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Pending> e = it.next();
                BlockPos pos = e.getKey();
                Pending p = e.getValue();
                if (p == null) {
                    it.remove();
                    continue;
                }

                if (now - p.sentAtMs < CONFIRM_TIMEOUT_MS) continue;

                if (mc.world.getBlockState(pos).getBlock() == p.step.block) {
                    it.remove();
                    continue;
                }

                it.remove();

                if (p.retriesLeft > 0) {
                    pendingRetryAppend(p.step, p.retriesLeft - 1);
                } else {
                    prune(p.step, "CONFIRM_TIMEOUT_RETRIES_EXHAUSTED");
                }
            }
        }

        if (queue.isEmpty()) {
            // restore slot only when truly finished (no queue, no pending)
            if (pending.isEmpty() && restoreHotbarSlot != -1) {
                int cur = mc.player.getInventory().getSelectedSlot();
                if (cur != restoreHotbarSlot) {
                    inventory.ensureSelectedSlot(mc.player, restoreHotbarSlot);
                }
                restoreHotbarSlot = -1;
            }
            return;
        }

        if (tokens < 1.0) return;

        int attempts = 0;

        while (tokens >= 1.0 && !queue.isEmpty() && attempts < MAX_ATTEMPTS_PER_TICK) {
            attempts++;

            Queued q = queue.peekFirst();
            if (q == null || q.step == null) {
                queue.pollFirst();
                prune(null, "NULL_QUEUE_ENTRY");
                continue;
            }

            PlacementStep step = q.step;

            // already placed? consume step and move on.
            if (mc.world.getBlockState(step.pos).getBlock() == step.block) {
                queue.pollFirst();
                prune(step, "ALREADY_PLACED");
                continue;
            }

            // not replaceable? consume step and move on.
            if (!mc.world.getBlockState(step.pos).isReplaceable()) {
                queue.pollFirst();
                prune(step, "NOT_REPLACEABLE_WORLDSTATE");
                continue;
            }

            // find the required block in hotbar for THIS step (preserve order).
            int neededSlot = inventory.findHotbarSlot(
                mc.player.getInventory(),
                item -> item instanceof BlockItem bi && bi.getBlock() == step.block
            );

            // missing item: drop only THIS step (keep order).
            if (neededSlot == -1) {
                queue.pollFirst();
                prune(step, Fail.MISSING_BLOCK_IN_HOTBAR);
                continue;
            }

            int currentSlot = mc.player.getInventory().getSelectedSlot();

            // record restore slot once (first time we are about to mess with selection)
            if (restoreHotbarSlot == -1) {
                restoreHotbarSlot = currentSlot;
            }

            // ensure correct slot. no tick-wait: if it sticks immediately, place now.
            if (currentSlot != neededSlot) {
                inventory.ensureSelectedSlot(mc.player, neededSlot);

                // if it still isn't selected, bail this tick (rare). don't reorder.
                if (mc.player.getInventory().getSelectedSlot() != neededSlot) return;
            }

            // nnow actually consume and attempt.
            queue.pollFirst();

            Result res = airplaceOffhandSwapRawPacket(mc, step);

            if (res.sent()) {
                tokens -= 1.0;

                // track pending confirmation for retries (carry retry budget forward).
                BlockPos key = step.pos.toImmutable();
                pending.putIfAbsent(key, new Pending(step, q.retriesLeft, now));

                continue;
            }

            if (!res.value()) {
                // drop out-of-reach steps (prevents infinite requeue loops when far away)
                if (res.fail() == Fail.OUT_OF_REACH) {
                    prune(step, Fail.OUT_OF_REACH);
                    continue;
                }

                // requeue only genuinely transient failures (front of queue; preserve "do soon" semantics)
                if (res.fail() == Fail.CHUNK_NOT_LOADED
                    || res.fail() == Fail.OFFHAND_SWAP_LOCKED
                    || res.fail() == Fail.NO_INTERACTION_MANAGER
                    || res.fail() == Fail.NO_PLAYER
                    || res.fail() == Fail.NO_WORLD) {
                    queue.addFirst(q);
                    break;
                }

                // everything else: treat as permanent skip (discard).
                prune(step, res.fail());
            }
        }

        // restore slot only when truly finished (no queue, no pending)
        if (queue.isEmpty() && pending.isEmpty() && restoreHotbarSlot != -1) {
            int cur = mc.player.getInventory().getSelectedSlot();
            if (cur != restoreHotbarSlot) {
                inventory.ensureSelectedSlot(mc.player, restoreHotbarSlot);
            }
            restoreHotbarSlot = -1;
        }
    }

    private void pendingRetryAppend(PlacementStep step, int retriesLeft) {
        // append to END of queue, keep step order relative to whatever is queued now.
        if (step == null || step.pos == null || step.block == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null && mc.world.getBlockState(step.pos).getBlock() == step.block) return;

        queue.addLast(new Queued(step, retriesLeft));
    }

    public Result airplaceOffhandSwapRawPacket(MinecraftClient mc, PlacementStep step) {
        if (mc.player == null) return Result.fail(Fail.NO_PLAYER);
        if (mc.world == null) return Result.fail(Fail.NO_WORLD);
        if (mc.interactionManager == null) return Result.fail(Fail.NO_INTERACTION_MANAGER);

        if (step == null || step.pos == null) return Result.fail(Fail.NULL_SUPPORT);
        if (step.supportFace == null) return Result.fail(Fail.NULL_SUPPORT);

        net.minecraft.util.math.Direction supportFace = step.supportFace;
        BlockPos supportPos = step.pos; // Grim-style: click the target block itself

        if (!World.isValid(step.pos) || !World.isValid(supportPos)) {
            return Result.fail(Fail.INVALID_HEIGHT);
        }

        if (!mc.world.isChunkLoaded(step.pos) || !mc.world.isChunkLoaded(supportPos)) {
            return Result.fail(Fail.CHUNK_NOT_LOADED);
        }

        if (!mc.world.getBlockState(step.pos).isReplaceable()) {
            return Result.fail(Fail.NOT_REPLACEABLE);
        }

        if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem bi)) {
            return Result.fail(Fail.MAINHAND_NOT_BLOCKITEM);
        }

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

        // reach check
        double reach = 5.154;
        double margin = 0.1;
        double maxSq = (reach + margin) * (reach + margin);
        if (mc.player.getEyePos().squaredDistanceTo(hitPos) > maxSq) {
            logOutOfReach(mc, step, hitPos);
            return Result.fail(Fail.OUT_OF_REACH);
        }

        if (!inventory.beginOffhandSwap(mc.player)) {
            return Result.fail(Fail.OFFHAND_SWAP_LOCKED);
        }

        try {
            // raw placement packet (no interactBlock / no swingHand)
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.OFF_HAND, bhr, nextInteractSeq()
            ));
            System.out.println("log");
            return Result.okSent();
        } finally {
            inventory.endOffhandSwap(mc.player);
        }
    }
}
