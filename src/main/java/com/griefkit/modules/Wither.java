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

/**
 * Wither module
 *
 * High-level behavior:
 * - On key press (witherPlace), choose an anchor position and orientation (vertical/horizontal),
 *   then build a list of PlacementSteps representing the wither structure.
 * - Once "prepared", enqueue all steps into GriefKit.PLACEMENT (your PlacementManager).
 * - On tick: monitor completion; when all blocks are present, count success and reset state.
 * - Rendering: draws boxes where blocks will go; uses different colors for already-placed blocks.
 *
 * Important: This module does NOT itself place blocks directly; it produces a plan and feeds it
 * to PlacementManager, which handles rate limiting, offhand swap, and raw packets.
 */
public class Wither extends Module {
    /** Global session counter, used by HUD elements. */
    private static int successfulPlacements = 0;

    // ----------------------
    // Settings: General
    // ----------------------
    private final SettingGroup sgGeneral;
    private final Setting<Boolean> silentMode;
    private final Setting<Keybind> witherPlace;
    private final Setting<Keybind> resetBind;

    /**
     * cursorPlacement:
     * - When true, attempt to anchor near the block you are aiming at (crosshair hit),
     *   scanning around that point to find a valid build location.
     * - When false, anchor in front of you (2 blocks) instead.
     */
    private final Setting<Boolean> cursorPlacement;

    /** Max allowed distance (from player eye) for cursor-anchored wither building. */
    private final Setting<Double> cursorMaxDistance;

    /** Horizontal scan radius around the cursor hit to search for an anchor. */
    private final Setting<Integer> cursorSearchRadius;

    /**
     * elytraMode:
     * - Disables cursor placement.
     * - Anchors the wither 2 blocks behind you (useful while moving/ebouncing).
     */
    private final Setting<Boolean> elytraMode;

    // ----------------------
    // Settings: Render
    // ----------------------
    private final SettingGroup sgRender;
    private final Setting<Boolean> render;
    private final Setting<ShapeMode> shapeMode;
    private final Setting<SettingColor> sideColor;
    private final Setting<SettingColor> lineColor;
    private final Setting<SettingColor> placedSideColor;
    private final Setting<SettingColor> placedLineColor;

    // ----------------------
    // Runtime state
    // ----------------------

    /**
     * Planned structure steps (7 total):
     * - 4x soul sand (stem + body + left/right arms)
     * - 3x skulls (left/center/right heads), with support face depending on orientation
     *
     * Each step contains:
     * - pos: where to place
     * - block: which block
     * - supportFace: which face to click/anchor for placement (important for skulls)
     */
    private final List<PlacementStep> steps;

    /**
     * prepared:
     * - true means we have a current plan (steps list is meaningful) and we should try to place it.
     * - false means we’re either idle or preview-only (render preview can still generate steps).
     */
    private boolean prepared;

    /**
     * queuedOnce:
     * - used so we only enqueue the plan into PlacementManager a single time per activation.
     * - after enqueue, tick() just monitors completion.
     */
    private boolean queuedOnce;

    /** Edge detection for keybinds (press once). */
    private boolean lastWitherKeyDown;
    private boolean lastResetKeyDown;

    // ----------------------
    // Global counter helpers
    // ----------------------
    public static void incrementSuccessfulPlacements() { ++successfulPlacements; }
    public static int getSuccessfulPlacements() { return successfulPlacements; }
    public static void resetSuccessfulPlacements() { successfulPlacements = 0; }

    public Wither() {
        super(GriefKit.CATEGORY, "Wither", "Builds a wither (cursor placement + preview)");

        // General settings group
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

        // Render settings group
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

        // Unplaced colors (red-ish)
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

        // Placed colors (green-ish)
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

        // Runtime state init
        this.steps = new ArrayList<>();
        this.prepared = false;
        this.queuedOnce = false;
        this.lastWitherKeyDown = false;
        this.lastResetKeyDown = false;
    }

    /**
     * When the module is toggled on:
     * - reset state
     * - if world/player not ready, auto-toggle off to avoid NPE spam.
     */
    public void onActivate() {
        this.steps.clear();
        this.prepared = false;
        this.queuedOnce = false;

        if (this.mc.player == null || this.mc.world == null) {
            this.warning("Player/world not loaded");
            this.toggle();
        }
    }

    /**
     * When toggled off:
     * - clear state
     * - clear PlacementManager queue (so it doesn’t keep placing after disable)
     */
    public void onDeactivate() {
        this.steps.clear();
        this.prepared = false;
        this.queuedOnce = false;
        GriefKit.PLACEMENT.clear();
    }

    /**
     * Tick loop only matters once prepared.
     *
     * Logic:
     * - If plan is fully placed: announce success, increment counter, reset state.
     * - If not queued yet: enqueue missing steps into PlacementManager exactly once.
     * - If PlacementManager becomes idle but we’re still not complete: treat as failure.
     */
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

            // Enqueue only missing blocks (if something is already there, skip)
            for (PlacementStep step : this.steps) {
                Block currentBlock = this.mc.world.getBlockState(step.pos).getBlock();
                if (currentBlock == step.block) continue;
                GriefKit.PLACEMENT.enqueue(step);
            }
            return;
        }

        // If the placer has no work left but the structure isn't complete, something failed:
        // - out of reach
        // - blocks ran out
        // - placement constraints changed
        // - confirm timeouts exhausted, etc.
        if (GriefKit.PLACEMENT.isIdle() && !this.allStepsPlaced()) {
            this.prepared = false;
            this.queuedOnce = false;
            if (!this.silentMode.get()) this.warning("Wither placement incomplete (no requeue).");
        }
    }

    /**
     * Render3DEvent handler doubles as:
     * - keybind polling (Meteor typical pattern)
     * - preview rendering of the planned structure
     *
     * On a rising edge of witherPlace:
     * - verify materials in hotbar (>=4 soul sand, >=3 skulls)
     * - compute plan (preparePattern)
     * - if plan exists: set prepared=true so tick() will enqueue it
     *
     * When render is enabled:
     * - if not prepared, still compute plan for preview, but don't enqueue
     * - draw a box for each planned block position
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (this.mc.world == null) return;

        boolean witherKeyDown = this.witherPlace.get().isPressed();
        boolean resetKeyDown = this.resetBind.get().isPressed();

        // Rising edge: start a new build attempt (only if not already prepared)
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
                    if (!this.silentMode.get()) this.info("Withering...");
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

        // Rendering toggle
        if (!this.render.get()) return;

        // Preview mode: if we're not actively placing, recompute steps so boxes follow aim/position
        if (!this.prepared) {
            this.steps.clear();
            this.preparePattern();
        }

        if (this.steps.isEmpty()) return;

        // Draw each planned block
        for (int i = 0; i < this.steps.size(); ++i) {
            PlacementStep step = this.steps.get(i);

            // In active placement mode, show already-placed blocks in the "placed" color
            boolean alreadyPlaced = this.prepared && this.mc.world.getBlockState(step.pos).getBlock() == step.block;

            SettingColor side = alreadyPlaced ? this.placedSideColor.get() : this.sideColor.get();
            SettingColor line = alreadyPlaced ? this.placedLineColor.get() : this.lineColor.get();

            event.renderer.box(step.pos, (Color) side, (Color) line, this.shapeMode.get(), 0);
        }
    }

    /**
     * Checks whether the world already matches every planned step position.
     * Used for "done" detection and for skipping steps when enqueueing.
     */
    private boolean allStepsPlaced() {
        if (this.mc.world == null) return false;

        for (PlacementStep step : this.steps) {
            if (this.mc.world.getBlockState(step.pos).getBlock() == step.block) continue;
            return false;
        }
        return true;
    }

    /**
     * Hotbar material check:
     * - Requires at least 4 soul sand and 3 wither skeleton skulls in hotbar.
     *
     * Assumption: PlacementManager places from hotbar only, so we enforce here to avoid partial plans.
     */
    private boolean hasRequiredMaterialsInHotbar() {
        if (this.mc.player == null) return false;

        PlayerInventory inv = this.mc.player.getInventory();
        Item soulSand = Blocks.SOUL_SAND.asItem();
        Item skull = Blocks.WITHER_SKELETON_SKULL.asItem();

        boolean hasSoulSandStack = GriefKit.INVENTORY.hasHotbarStackAtLeast(inv, item -> item == soulSand, 4);
        boolean hasSkullStack = GriefKit.INVENTORY.hasHotbarStackAtLeast(inv, item -> item == skull, 3);
        return hasSoulSandStack && hasSkullStack;
    }

    /**
     * "Obstructed" means the target space can't be used for placement:
     * - if it's not air AND not replaceable, it's blocked.
     */
    private boolean isObstructed(BlockPos pos) {
        return !this.mc.world.getBlockState(pos).isAir() && !this.mc.world.getBlockState(pos).isReplaceable();
    }

    /**
     * Checks if any entity would intersect the block box at this position.
     * This prevents planning a wither inside you or other entities.
     */
    private boolean isEntityIntersecting(BlockPos pos) {
        if (this.mc.world == null || this.mc.player == null) return false;

        Box box = new Box(pos);

        // Player check (avoid self-intersection)
        if (!this.mc.player.isSpectator() && this.mc.player.isAlive() && this.mc.player.getBoundingBox().intersects(box)) {
            return true;
        }

        // Other entities check
        return !this.mc.world.getOtherEntities(null, box, entity -> !entity.isSpectator() && entity.isAlive()).isEmpty();
    }

    /** True if any position is obstructed OR has an entity in it. */
    private boolean anyBlockedOrEntity(List<BlockPos> positions) {
        for (BlockPos p : positions) {
            if (!this.isObstructed(p) && !this.isEntityIntersecting(p)) continue;
            return true;
        }
        return false;
    }

    /**
     * Extra constraint for vertical orientation only:
     * Require that the blocks directly beneath the arm positions are air.
     *
     * Why you might want this:
     * - ensures the arms are not "supported" from below (prevents weird placements into terrain)
     * - helps avoid building a wither where arms collide with a ledge/step
     * - tends to keep the wither in open air rather than embedded in walls/floors
     */
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

    /**
     * Produces the 7 positions that make up the wither structure.
     *
     * The pattern is described in relative terms:
     * - stem = origin
     * - centerBody = stem + upDir
     * - arms = centerBody +/- rightDir
     * - heads = those three top positions (each arm + up, plus body + up)
     *
     * upDir/rightDir let you describe both:
     * - vertical (upDir = UP)
     * - horizontal (upDir = facing direction)
     */
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

    /** Classic upright wither: up is UP, arms are perpendicular to facing. */
    private List<BlockPos> getVerticalPattern(BlockPos stem, Direction facing) {
        Direction upDir = Direction.UP;
        Direction rightDir = facing.rotateYClockwise();
        return this.getPatternPositions(stem, upDir, rightDir);
    }

    /** Horizontal wither: "upDir" is actually forward in the facing direction. */
    private List<BlockPos> getHorizontalPattern(BlockPos stem, Direction facing) {
        Direction upDir = facing;
        Direction rightDir = facing.rotateYClockwise();
        return this.getPatternPositions(stem, upDir, rightDir);
    }

    /** Valid if all 7 positions are clear AND the arms have air below. */
    private boolean validateVerticalPattern(BlockPos stem, Direction facing) {
        List<BlockPos> pattern = this.getVerticalPattern(stem, facing);
        if (this.anyBlockedOrEntity(pattern)) return false;
        return this.armsHaveAirBelow(stem, facing);
    }

    /** Valid if all 7 positions are clear. */
    private boolean validateHorizontalPattern(BlockPos stem, Direction facing) {
        List<BlockPos> pattern = this.getHorizontalPattern(stem, facing);
        return !this.anyBlockedOrEntity(pattern);
    }

    /**
     * Cursor anchoring for vertical pattern:
     * - Determine a "base" position near the face you hit.
     * - Scan dx/dz around it within cursorSearchRadius.
     * - For each candidate stem:
     *   - must validate the vertical pattern
     *   - the pattern's *centerBody* must be within cursorMaxDistance of your eye
     *   - choose the candidate whose centerBody is closest to the cursor hit point (hitVec)
     *
     * Why centerBody:
     * - it's a decent proxy for the structure’s center, so the chosen anchor “feels” aligned with crosshair.
     */
    private BlockPos findBestVerticalStemPos(BlockHitResult hit, Direction facing) {
        if (this.mc.world == null || this.mc.player == null) return null;

        // Determine starting base depending on which face was hit.
        // - If hit top/bottom, you place above that block.
        // - Otherwise, place adjacent to the hit face.
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

                // compute the pattern + choose the "center body" position as distance anchor
                List<BlockPos> pattern = this.getVerticalPattern(candidateStem, facing);
                BlockPos centerBody = pattern.get(1);
                Vec3d centerVec = Vec3d.ofCenter(centerBody);

                // must be within max distance from player eye
                if (centerVec.squaredDistanceTo(eyePos) > maxDistSq) continue;

                // choose the candidate whose center is closest to where the cursor hit
                double cursorDistSq = centerVec.squaredDistanceTo(hitVec);
                if (cursorDistSq >= bestCursorDistSq) continue;

                bestCursorDistSq = cursorDistSq;
                bestStem = candidateStem;
            }
        }

        return bestStem;
    }

    /**
     * Cursor anchoring for horizontal pattern:
     * - Same scan idea as vertical, but:
     *   - validateHorizontalPattern (no arms-air-below constraint)
     *   - uses candidateStem center (not centerBody) as distance reference
     *
     * This makes sense because horizontal “stem” is already the “front” anchor and acts like the center.
     */
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

    /**
     * Builds the final ordered PlacementSteps list (the plan).
     *
     * Order matters:
     * - you place all soul sand first
     * - then skulls last
     *
     * support face for skulls:
     * - vertical: skulls are placed "on top" of blocks => supportFace = UP
     * - horizontal: skulls placed against a face in the facing direction => supportFace = facing
     *
     * This matches how PlacementManager uses supportFace to compute the hit position / click face.
     */
    private void buildSteps(BlockPos stem, Direction facing, WitherOrientation orientation) {
        this.steps.clear();

        List<BlockPos> pattern =
            orientation == WitherOrientation.VERTICAL
                ? this.getVerticalPattern(stem, facing)
                : this.getHorizontalPattern(stem, facing);

        BlockPos stemPos = pattern.get(0);
        BlockPos centerBody = pattern.get(1);
        BlockPos leftArm = pattern.get(2);
        BlockPos rightArm = pattern.get(3);
        BlockPos headLeft = pattern.get(4);
        BlockPos headCenter = pattern.get(5);
        BlockPos headRight = pattern.get(6);

        // Soul sand foundation
        this.steps.add(new PlacementStep(stemPos, Blocks.SOUL_SAND));
        this.steps.add(new PlacementStep(centerBody, Blocks.SOUL_SAND));
        this.steps.add(new PlacementStep(leftArm, Blocks.SOUL_SAND));
        this.steps.add(new PlacementStep(rightArm, Blocks.SOUL_SAND));

        // Skull placement (face differs by orientation)
        if (orientation == WitherOrientation.VERTICAL) {
            this.steps.add(new PlacementStep(headLeft, Blocks.WITHER_SKELETON_SKULL, Direction.UP));
            this.steps.add(new PlacementStep(headCenter, Blocks.WITHER_SKELETON_SKULL, Direction.UP));
            this.steps.add(new PlacementStep(headRight, Blocks.WITHER_SKELETON_SKULL, Direction.UP));
        } else {
            this.steps.add(new PlacementStep(headLeft, Blocks.WITHER_SKELETON_SKULL, facing));
            this.steps.add(new PlacementStep(headCenter, Blocks.WITHER_SKELETON_SKULL, facing));
            this.steps.add(new PlacementStep(headRight, Blocks.WITHER_SKELETON_SKULL, facing));
        }
    }

    /**
     * Computes the current build plan (fills steps).
     *
     * Priority order:
     * 1) Elytra mode: anchor 2 blocks behind player; prefer vertical, fallback horizontal
     * 2) Cursor placement: if crosshair is a BlockHitResult, scan for vertical stem near cursor; fallback to horizontal scan
     * 3) Default: anchor 2 blocks in front of player; prefer vertical, fallback horizontal
     *
     * If no valid position exists, clears steps.
     */
    private void preparePattern() {
        ClientPlayerEntity player = this.mc.player;
        if (player == null || this.mc.world == null) return;

        Direction facing = player.getHorizontalFacing();
        BlockPos anchor = null;
        WitherOrientation orientation = WitherOrientation.VERTICAL;

        // 1) Elytra mode: place behind you
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

        // 2) Cursor placement: use crosshair hit + scan radius
        if (this.cursorPlacement.get() && this.mc.crosshairTarget instanceof BlockHitResult) {
            BlockHitResult bhr = (BlockHitResult) this.mc.crosshairTarget;

            anchor = this.findBestVerticalStemPos(bhr, facing);
            orientation = WitherOrientation.VERTICAL;

            if (anchor == null) {
                anchor = this.findBestHorizontalStemPos(bhr, facing);
                orientation = WitherOrientation.HORIZONTAL;
            }
        }

        // 3) Default: place in front of you (2 blocks)
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

    /** Two orientations: upright and “forward-extended”. */
    private static enum WitherOrientation { VERTICAL, HORIZONTAL }
}
