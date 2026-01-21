package com.griefkit.modules;

import com.griefkit.GriefKit;
import com.griefkit.placement.PlacementStep;
import java.util.ArrayList;
import java.util.List;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;

public class Wither extends Module {
    private static int successfulPlacements = 0;

    private final SettingGroup sgGeneral;
    private final Setting<Boolean> silentMode;
    private final Setting<Keybind> witherPlace;
    private final Setting<Keybind> resetBind;

    private final Setting<Boolean> cursorPlacement;
    private final Setting<Double> cursorMaxDistance;
    private final Setting<Integer> cursorSearchRadius;
    private final Setting<Boolean> elytraMode;

    private final SettingGroup sgRender;
    private final Setting<Boolean> render;
    private final Setting<ShapeMode> shapeMode;
    private final Setting<SettingColor> sideColor;
    private final Setting<SettingColor> lineColor;
    private final Setting<SettingColor> placedSideColor;
    private final Setting<SettingColor> placedLineColor;

    private final List<PlacementStep> steps;
    private boolean prepared;
    private boolean queuedOnce;

    private boolean lastWitherKeyDown;
    private boolean lastResetKeyDown;

    public static void incrementSuccessfulPlacements() { ++successfulPlacements; }
    public static int getSuccessfulPlacements() { return successfulPlacements; }
    public static void resetSuccessfulPlacements() { successfulPlacements = 0; }

    public Wither() {
        super(GriefKit.CATEGORY, "Wither", "Builds a wither (cursor placement + preview)");

        // General settings
        this.sgGeneral = this.settings.getDefaultGroup();

        this.silentMode = this.sgGeneral.add(new BoolSetting.Builder()
            .name("silent-notifications")
            .description("Remove notifications")
            .defaultValue(false)
            .build());

        this.witherPlace = this.sgGeneral.add(new KeybindSetting.Builder()
            .name("wither-place")
            .description("Places a wither")
            .defaultValue(Keybind.none())
            .build());

        this.resetBind = this.sgGeneral.add(new KeybindSetting.Builder()
            .name("reset-counter")
            .description("Resets the wither placement counter")
            .defaultValue(Keybind.none())
            .build());

        this.cursorPlacement = this.sgGeneral.add(new BoolSetting.Builder()
            .name("cursor-placement")
            .description("Anchor withers around your crosshair instead of only in front of you")
            .defaultValue(true)
            .build());

        this.cursorMaxDistance = this.sgGeneral.add(new DoubleSetting.Builder()
            .name("cursor-max-distance")
            .description("Maximum distance from you for cursor-based wither placement")
            .defaultValue(12.0)
            .min(1.0).max(64.0)
            .build());

        this.cursorSearchRadius = this.sgGeneral.add(new IntSetting.Builder()
            .name("cursor-search-radius")
            .description("Horizontal search radius around the cursor hit to find a valid wither position")
            .defaultValue(2)
            .min(0).max(6)
            .build());

        this.elytraMode = this.sgGeneral.add(new BoolSetting.Builder()
            .name("elytra-mode")
            .description("Disables cursor placement and anchors the wither 2 blocks behind you (useful while ebouncing)")
            .defaultValue(false)
            .build());

        // Render settings
        this.sgRender = this.settings.createGroup("Render");

        this.render = this.sgRender.add(new BoolSetting.Builder()
            .name("render")
            .description("Render the planned wither structure")
            .defaultValue(true)
            .build());

        this.shapeMode = this.sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the boxes are rendered")
            .defaultValue(ShapeMode.Both)
            .build());

        this.sideColor = this.sgRender.add(new ColorSetting.Builder()
            .name("side-color")
            .description("Color of the box sides for blocks that are not yet placed")
            .defaultValue(new SettingColor(255, 50, 50, 25))
            .build());

        this.lineColor = this.sgRender.add(new ColorSetting.Builder()
            .name("line-color")
            .description("Outline color for blocks that are not yet placed")
            .defaultValue(new SettingColor(255, 50, 50, 255))
            .build());

        this.placedSideColor = this.sgRender.add(new ColorSetting.Builder()
            .name("placed-side-color")
            .description("Side color for blocks that are already placed")
            .defaultValue(new SettingColor(50, 255, 50, 25))
            .build());

        this.placedLineColor = this.sgRender.add(new ColorSetting.Builder()
            .name("placed-line-color")
            .description("Outline color for blocks that are already placed")
            .defaultValue(new SettingColor(50, 255, 50, 255))
            .build());

        // Runtime
        this.steps = new ArrayList<>();
        this.prepared = false;
        this.queuedOnce = false;
        this.lastWitherKeyDown = false;
        this.lastResetKeyDown = false;
    }

    @Override
    public void onActivate() {
        this.steps.clear();
        this.prepared = false;
        this.queuedOnce = false;

        if (this.mc.player == null || this.mc.world == null) {
            this.warning("Player/world not loaded");
            this.toggle();
        }
    }

    @Override
    public void onDeactivate() {
        this.steps.clear();
        this.prepared = false;
        this.queuedOnce = false;
        GriefKit.PLACEMENT.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!this.prepared || this.mc.player == null || this.mc.world == null) return;

        if (this.allStepsPlaced()) {
            if (!this.silentMode.get()) this.info("Wither done");
            Wither.incrementSuccessfulPlacements();
            this.prepared = false;
            this.queuedOnce = false;
            return;
        }

        if (!this.queuedOnce) {
            this.queuedOnce = true;

            for (PlacementStep step : this.steps) {
                Block currentBlock = this.mc.world.getBlockState(step.pos).getBlock();
                if (currentBlock == step.block) continue;
                GriefKit.PLACEMENT.enqueue(step);
            }
            return;
        }

        // If the placer has no work left but the structure isn't complete:
        // just reset state silently (no "incomplete" warning spam).
        if (GriefKit.PLACEMENT.isIdle() && !this.allStepsPlaced()) {
            this.prepared = false;
            this.queuedOnce = false;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (this.mc.world == null) return;

        boolean witherKeyDown = this.witherPlace.get().isPressed();
        boolean resetKeyDown = this.resetBind.get().isPressed();

        // Rising edge: trigger a new wither build
        if (witherKeyDown && !this.lastWitherKeyDown && !this.prepared) {
            if (!this.hasRequiredMaterialsInHotbar()) {
                if (!this.silentMode.get()) this.warning("Not enough soul sand / wither skulls in hotbar");
                this.prepared = false;
                this.queuedOnce = false;
            } else {
                this.steps.clear();
                this.preparePattern();

                if (this.steps.isEmpty()) {
                    if (!this.silentMode.get()) this.warning("No valid build position found");
                    this.prepared = false;
                    this.queuedOnce = false;
                } else {
                    this.prepared = true;
                    this.queuedOnce = false;
                    if (!this.silentMode.get()) this.info("Queued wither");
                }
            }
        }

        // Rising edge: reset counter
        if (resetKeyDown && !this.lastResetKeyDown) {
            if (!this.silentMode.get()) this.info("Reset wither placement counter");
            Wither.resetSuccessfulPlacements();
        }

        this.lastWitherKeyDown = witherKeyDown;
        this.lastResetKeyDown = resetKeyDown;

        if (!this.render.get()) return;

        // Preview movement if not currently placing
        if (!this.prepared) {
            this.steps.clear();
            this.preparePattern();
        }

        if (this.steps.isEmpty()) return;

        for (PlacementStep step : this.steps) {
            boolean alreadyPlaced = this.prepared && this.mc.world.getBlockState(step.pos).getBlock() == step.block;

            SettingColor side = alreadyPlaced ? this.placedSideColor.get() : this.sideColor.get();
            SettingColor line = alreadyPlaced ? this.placedLineColor.get() : this.lineColor.get();

            event.renderer.box(step.pos, (Color) side, (Color) line, this.shapeMode.get(), 0);
        }
    }

    private boolean allStepsPlaced() {
        if (this.mc.world == null) return false;

        for (PlacementStep step : this.steps) {
            if (this.mc.world.getBlockState(step.pos).getBlock() == step.block) continue;
            return false;
        }
        return true;
    }

    private boolean hasRequiredMaterialsInHotbar() {
        if (this.mc.player == null) return false;

        PlayerInventory inv = this.mc.player.getInventory();
        Item soulSand = Blocks.SOUL_SAND.asItem();
        Item skull = Blocks.WITHER_SKELETON_SKULL.asItem();

        boolean hasSoulSandStack = GriefKit.INVENTORY.hasHotbarStackAtLeast(inv, item -> item == soulSand, 4);
        boolean hasSkullStack = GriefKit.INVENTORY.hasHotbarStackAtLeast(inv, item -> item == skull, 3);
        return hasSoulSandStack && hasSkullStack;
    }

    private boolean isObstructed(BlockPos pos) {
        return !this.mc.world.getBlockState(pos).isAir() && !this.mc.world.getBlockState(pos).isReplaceable();
    }

    private boolean isEntityIntersecting(BlockPos pos) {
        if (this.mc.world == null || this.mc.player == null) return false;

        Box box = new Box(pos);

        if (!this.mc.player.isSpectator() && this.mc.player.isAlive() && this.mc.player.getBoundingBox().intersects(box)) {
            return true;
        }

        return !this.mc.world.getOtherEntities(null, box, entity -> !entity.isSpectator() && entity.isAlive()).isEmpty();
    }

    private boolean anyBlockedOrEntity(List<BlockPos> positions) {
        for (BlockPos p : positions) {
            if (!this.isObstructed(p) && !this.isEntityIntersecting(p)) continue;
            return true;
        }
        return false;
    }

    private boolean armsHaveAirBelow(BlockPos stem, Direction facing) {
        int bodyY = stem.getY() + 1;
        BlockPos centerBody = new BlockPos(stem.getX(), bodyY, stem.getZ());

        Direction left = facing.rotateYCounterclockwise();
        Direction right = facing.rotateYClockwise();

        BlockPos leftArm = centerBody.offset(left);
        BlockPos rightArm = centerBody.offset(right);

        return this.mc.world.getBlockState(leftArm.down()).isAir()
            && this.mc.world.getBlockState(rightArm.down()).isAir();
    }

    private List<BlockPos> getPatternPositions(BlockPos origin, Direction upDir, Direction rightDir) {
        BlockPos stem = origin;
        BlockPos centerBody = stem.offset(upDir);

        BlockPos leftArm = centerBody.offset(rightDir.getOpposite());
        BlockPos rightArm = centerBody.offset(rightDir);

        BlockPos headCenter = centerBody.offset(upDir);
        BlockPos headLeft = leftArm.offset(upDir);
        BlockPos headRight = rightArm.offset(upDir);

        return List.of(stem, centerBody, leftArm, rightArm, headLeft, headCenter, headRight);
    }

    private List<BlockPos> getVerticalPattern(BlockPos stem, Direction facing) {
        Direction upDir = Direction.UP;
        Direction rightDir = facing.rotateYClockwise();
        return this.getPatternPositions(stem, upDir, rightDir);
    }

    private List<BlockPos> getHorizontalPattern(BlockPos stem, Direction facing) {
        Direction upDir = facing;
        Direction rightDir = facing.rotateYClockwise();
        return this.getPatternPositions(stem, upDir, rightDir);
    }

    private boolean validateVerticalPattern(BlockPos stem, Direction facing) {
        List<BlockPos> pattern = this.getVerticalPattern(stem, facing);
        if (this.anyBlockedOrEntity(pattern)) return false;
        return this.armsHaveAirBelow(stem, facing);
    }

    private boolean validateHorizontalPattern(BlockPos stem, Direction facing) {
        List<BlockPos> pattern = this.getHorizontalPattern(stem, facing);
        return !this.anyBlockedOrEntity(pattern);
    }

    private BlockPos findBestVerticalStemPos(BlockHitResult hit, Direction facing) {
        if (this.mc.world == null || this.mc.player == null) return null;

        BlockPos base = switch (hit.getSide()) {
            case UP -> hit.getBlockPos().up();
            case DOWN -> hit.getBlockPos().up();
            default -> hit.getBlockPos().offset(hit.getSide());
        };

        int radius = this.cursorSearchRadius.get();
        Vec3d hitVec = hit.getPos();

        double maxDist = this.cursorMaxDistance.get();
        double maxDistSq = maxDist * maxDist;

        Vec3d eyePos = this.mc.player.getEyePos();

        BlockPos bestStem = null;
        double bestCursorDistSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                BlockPos candidateStem = base.add(dx, 0, dz);

                if (!this.validateVerticalPattern(candidateStem, facing)) continue;

                List<BlockPos> pattern = this.getVerticalPattern(candidateStem, facing);
                BlockPos centerBody = pattern.get(1);
                Vec3d centerVec = Vec3d.ofCenter(centerBody);

                if (centerVec.squaredDistanceTo(eyePos) > maxDistSq) continue;

                double cursorDistSq = centerVec.squaredDistanceTo(hitVec);
                if (cursorDistSq >= bestCursorDistSq) continue;

                bestCursorDistSq = cursorDistSq;
                bestStem = candidateStem;
            }
        }

        return bestStem;
    }

    private BlockPos findBestHorizontalStemPos(BlockHitResult hit, Direction facing) {
        if (this.mc.world == null || this.mc.player == null) return null;

        BlockPos base = switch (hit.getSide()) {
            case UP -> hit.getBlockPos().up();
            case DOWN -> hit.getBlockPos().up();
            default -> hit.getBlockPos().offset(hit.getSide());
        };

        int radius = this.cursorSearchRadius.get();
        Vec3d hitVec = hit.getPos();

        double maxDist = this.cursorMaxDistance.get();
        double maxDistSq = maxDist * maxDist;

        Vec3d eyePos = this.mc.player.getEyePos();

        BlockPos bestStem = null;
        double bestCursorDistSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                BlockPos candidateStem = base.add(dx, 0, dz);

                if (!this.validateHorizontalPattern(candidateStem, facing)) continue;

                Vec3d centerVec = Vec3d.ofCenter(candidateStem);

                if (centerVec.squaredDistanceTo(eyePos) > maxDistSq) continue;

                double cursorDistSq = centerVec.squaredDistanceTo(hitVec);
                if (cursorDistSq >= bestCursorDistSq) continue;

                bestCursorDistSq = cursorDistSq;
                bestStem = candidateStem;
            }
        }

        return bestStem;
    }

    private void buildSteps(BlockPos stem, Direction facing, WitherOrientation orientation) {
        this.steps.clear();

        List<BlockPos> pattern =
            orientation == WitherOrientation.VERTICAL
                ? this.getVerticalPattern(stem, facing)
                : this.getHorizontalPattern(stem, facing);

        BlockPos stemPos    = pattern.get(0);
        BlockPos centerBody = pattern.get(1);
        BlockPos leftArm    = pattern.get(2);
        BlockPos rightArm   = pattern.get(3);
        BlockPos headLeft   = pattern.get(4);
        BlockPos headCenter = pattern.get(5);
        BlockPos headRight  = pattern.get(6);

        this.steps.add(new PlacementStep(stemPos,    Blocks.SOUL_SAND));
        this.steps.add(new PlacementStep(centerBody, Blocks.SOUL_SAND));
        this.steps.add(new PlacementStep(leftArm,    Blocks.SOUL_SAND));
        this.steps.add(new PlacementStep(rightArm,   Blocks.SOUL_SAND));

        if (orientation == WitherOrientation.VERTICAL) {
            this.steps.add(new PlacementStep(headLeft,   Blocks.WITHER_SKELETON_SKULL, leftArm));
            this.steps.add(new PlacementStep(headCenter, Blocks.WITHER_SKELETON_SKULL, centerBody));
            this.steps.add(new PlacementStep(headRight,  Blocks.WITHER_SKELETON_SKULL, rightArm));
        } else {
            this.steps.add(new PlacementStep(headLeft,   Blocks.WITHER_SKELETON_SKULL, leftArm));
            this.steps.add(new PlacementStep(headCenter, Blocks.WITHER_SKELETON_SKULL, centerBody));
            this.steps.add(new PlacementStep(headRight,  Blocks.WITHER_SKELETON_SKULL, rightArm));
        }
    }

    private void preparePattern() {
        ClientPlayerEntity player = this.mc.player;
        if (player == null || this.mc.world == null) return;

        Direction facing = player.getHorizontalFacing();
        BlockPos anchor = null;
        WitherOrientation orientation = WitherOrientation.VERTICAL;

        if (this.elytraMode.get()) {
            BlockPos base = player.getBlockPos().offset(facing.getOpposite(), 2);

            if (this.validateVerticalPattern(base, facing)) {
                anchor = base;
                orientation = WitherOrientation.VERTICAL;
            } else if (this.validateHorizontalPattern(base, facing)) {
                anchor = base;
                orientation = WitherOrientation.HORIZONTAL;
            } else {
                this.steps.clear();
                return;
            }

            this.buildSteps(anchor, facing, orientation);
            return;
        }

        if (this.cursorPlacement.get() && this.mc.crosshairTarget instanceof BlockHitResult bhr) {
            anchor = this.findBestVerticalStemPos(bhr, facing);
            orientation = WitherOrientation.VERTICAL;

            if (anchor == null) {
                anchor = this.findBestHorizontalStemPos(bhr, facing);
                orientation = WitherOrientation.HORIZONTAL;
            }
        }

        if (anchor == null) {
            BlockPos base = player.getBlockPos().offset(facing, 2);

            if (this.validateVerticalPattern(base, facing)) {
                anchor = base;
                orientation = WitherOrientation.VERTICAL;
            } else if (this.validateHorizontalPattern(base, facing)) {
                anchor = base;
                orientation = WitherOrientation.HORIZONTAL;
            } else {
                this.steps.clear();
                return;
            }
        }

        this.buildSteps(anchor, facing, orientation);
    }

    private static enum WitherOrientation { VERTICAL, HORIZONTAL }
}
