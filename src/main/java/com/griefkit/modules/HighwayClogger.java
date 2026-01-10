// leonetics 2025 evil inc

package com.griefkit.modules;

import com.griefkit.GriefKit;
import com.griefkit.placement.PlacementStep;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Auto Highway Clogger
 * - Places blocks behind the player randomly within a "highway box" (width/height/depth).
 * - Uses movement vector for behind/right so diagonals behave naturally (no x/z mishaps).
 * - Continuously schedules new placements when the player moves.
 */
public class HighwayClogger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Block> block = sgGeneral.add(new BlockSetting.Builder()
        .name("block")
        .description("Block to place for clogging.")
        .defaultValue(Blocks.OBSIDIAN)
        .build()
    );

    private final Setting<Integer> backMin = sgGeneral.add(new IntSetting.Builder()
        .name("back-min")
        .description("Minimum blocks behind player to place.")
        .defaultValue(2)
        .min(1)
        .max(16)
        .build()
    );

    private final Setting<Integer> backMax = sgGeneral.add(new IntSetting.Builder()
        .name("back-max")
        .description("Maximum blocks behind player to place.")
        .defaultValue(6)
        .min(1)
        .max(32)
        .build()
    );

    private final Setting<Integer> halfWidth = sgGeneral.add(new IntSetting.Builder()
        .name("half-width")
        .description("Horizontal half-width of the clog box (left/right). 2 => width 5.")
        .defaultValue(2)
        .min(0)
        .max(8)
        .build()
    );

    private final Setting<Integer> height = sgGeneral.add(new IntSetting.Builder()
        .name("height")
        .description("Vertical range (0..height-1) above the base Y.")
        .defaultValue(3)
        .min(1)
        .max(6)
        .build()
    );

    private final Setting<Integer> placementsPerMove = sgGeneral.add(new IntSetting.Builder()
        .name("placements-per-move")
        .description("How many placements to schedule each time you move enough.")
        .defaultValue(6)
        .min(1)
        .max(20)
        .build()
    );

    private final Setting<Double> moveThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("move-threshold")
        .description("Distance (blocks) you must move before scheduling more placements.")
        .defaultValue(2.5)
        .min(0.05)
        .max(2.0)
        .build()
    );

    private final Setting<Double> chance = sgGeneral.add(new DoubleSetting.Builder()
        .name("chance")
        .description("Chance per scheduled attempt to actually queue (adds randomness).")
        .defaultValue(1)
        .min(0.0)
        .max(1.0)
        .build()
    );

    private final Setting<Boolean> requireReplaceableNow = sgGeneral.add(new BoolSetting.Builder()
        .name("require-replaceable-now")
        .description("Only queue blocks if the world is currently replaceable at the target.")
        .defaultValue(true)
        .build()
    );

    private final Random rng = new Random(); // deterministic-ish

    private Vec3d lastPos = null;

    public HighwayClogger() {
        super(GriefKit.CATEGORY, "HighwayClogger", "Randomly clogs behind you.");
    }

    @Override
    public void onActivate() {
        lastPos = null;
        if (mc.player == null || mc.world == null) {
            warning("Player/world not loaded");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        lastPos = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        ClientPlayerEntity p = mc.player;
        Vec3d now = p.getPos();

        if (lastPos == null) {
            lastPos = now;
            // schedule an initial burst so it works immediately on toggle
            scheduleBatch(p, placementsPerMove.get());
            return;
        }

        double moved = now.distanceTo(lastPos);
        if (moved >= moveThreshold.get()) {
            lastPos = now;
            scheduleBatch(p, placementsPerMove.get());
        }
    }

    private void scheduleBatch(ClientPlayerEntity p, int count) {
        if (mc.world == null) return;

        Vec3d vel = p.getVelocity();
        Vec3d moveDir = new Vec3d(vel.x, 0.0, vel.z);

        Vec3d forward;
        if (moveDir.lengthSquared() > 1.0e-6) {
            forward = moveDir.normalize();
        } else {
            float yaw = p.getYaw();
            float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
            forward = new Vec3d(-MathHelper.sin(yawRad), 0.0, MathHelper.cos(yawRad)).normalize();
        }

        Vec3d behind = forward.negate();
        Vec3d right = new Vec3d(-behind.z, 0.0, behind.x);

        BlockPos base = p.getBlockPos();

        int minB = Math.min(backMin.get(), backMax.get());
        int maxB = Math.max(backMin.get(), backMax.get());

        int w = halfWidth.get();
        int h = height.get();

        Block placeBlock = block.get();

        for (int i = 0; i < count; i++) {
            if (rng.nextDouble() > chance.get()) continue;

            int back = randInt(minB, maxB);
            int lateral = (w == 0) ? 0 : randInt(-w, w);
            int up = (h <= 1) ? 0 : randInt(0, h - 1);

            Vec3d offset = behind.multiply(back).add(right.multiply(lateral)).add(0.0, up, 0.0);
            BlockPos target = BlockPos.ofFloored(new Vec3d(base.getX() + 0.5, base.getY(), base.getZ() + 0.5).add(offset));

            if (target.getX() == base.getX() && target.getZ() == base.getZ() && target.getY() == base.getY()) continue;

            if (requireReplaceableNow.get()) {
                if (!mc.world.getBlockState(target).isReplaceable()) continue;
            }

            GriefKit.PLACEMENT.enqueue(new PlacementStep(target, placeBlock, Direction.UP));
        }
    }

    private int randInt(int a, int b) {
        if (a == b) return a;
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return lo + rng.nextInt(hi - lo + 1);
    }
}
