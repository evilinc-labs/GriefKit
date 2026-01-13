package com.griefkit.modules;

import com.griefkit.GriefKit;
import com.griefkit.placement.PlacementStep;

import java.util.ArrayList;
import java.util.List;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
 * Cross module
 *
 * What it does:
 * - On key press, computes a 6-block “cross” pattern:
 *   - A 4-long stem
 *   - Plus 2 arms on the 2nd block of the stem (s1)
 * - Supports vertical and horizontal orientation, and can anchor near the crosshair.
 * - Enqueues missing steps into GriefKit.PLACEMENT (PlacementManager) immediately.
 * - Renders a preview of the plan every frame when render is enabled.
 *
 * Pattern definition (6 blocks total):
 *   s0 - s1 - s2 - s3    (stem, 4 long)
 *          |
 *        l   r           (arms at s1, perpendicular to facing)
 *
 * Notes:
 * - “vertical” means stem goes UP from base position (y+)
 * - “horizontal” means stem goes FORWARD along facing direction
 */
public class Cross extends Module {
    // ----------------------
    // Settings: General
    // ----------------------
    private final SettingGroup sgGeneral;

    /** Block type used for the cross (default obsidian). */
    private final Setting<Block> block;

    /** Toggle notifications. */
    private final Setting<Boolean> silentMode;

    /** Keybind for placing the cross. */
    private final Setting<Keybind> crossPlace;

    /**
     * Cursor placement:
     * - if enabled, attempt to anchor around crosshair hit, scanning in dx/dz around that point
     * - if disabled, place 2 blocks in front of you
     */
    private final Setting<Boolean> cursorPlacement;

    /** Max allowed distance (from player eye) for cursor-anchored crosses. */
    private final Setting<Double> cursorMaxDistance;

    /** Horizontal scan radius around cursor hit for anchor search. */
    private final Setting<Integer> cursorSearchRadius;

    /**
     * Elytra mode:
     * - disables cursor placement
     * - anchors the cross 2 blocks behind you
     */
    private final Setting<Boolean> elytraMode;

    // ----------------------
    // Settings: Render
    // ----------------------
    private final SettingGroup sgRender;
    private final Setting<Boolean> render;
    private final Setting<ShapeMode> shapeMode;

    // Colors for not-yet-placed vs already-placed blocks
    private final Setting<SettingColor> sideColor;
    private final Setting<SettingColor> lineColor;
    private final Setting<SettingColor> placedSideColor;
    private final Setting<SettingColor> placedLineColor;

    // ----------------------
    // Runtime state
    // ----------------------
    /** Planned blocks (6 positions). Recomputed for preview and for placement. */
    private final List<PlacementStep> steps;

    /** Edge detection for the keybind (press once). */
    private boolean lastPlaceKeyDown;

    public Cross() {
        super(GriefKit.CATEGORY, "Cross", "Builds a 4-long 2-arm cross.");

        // General group
        this.sgGeneral = this.settings.getDefaultGroup();

        this.block = this.sgGeneral.add(new BlockSetting.Builder()
            .name("block")
            .description("Block used to build the cross.")
            .defaultValue(Blocks.OBSIDIAN)
            .build());

        this.silentMode = this.sgGeneral.add(new BoolSetting.Builder()
            .name("silent-notifications")
            .description("Remove notifications.")
            .defaultValue(false)
            .build());

        this.crossPlace = this.sgGeneral.add(new KeybindSetting.Builder()
            .name("cross-place")
            .description("Places a cross instantly.")
            .defaultValue(Keybind.none())
            .build());

        this.cursorPlacement = this.sgGeneral.add(new BoolSetting.Builder()
            .name("cursor-placement")
            .description("Anchor crosses around your crosshair instead of only in front of you.")
            .defaultValue(true)
            .build());

        this.cursorMaxDistance = this.sgGeneral.add(new DoubleSetting.Builder()
            .name("cursor-max-distance")
            .description("Maximum distance from you for cursor-based cross placement.")
            .defaultValue(6.0)
            .min(1.0).max(8.0)
            .build());

        this.cursorSearchRadius = this.sgGeneral.add(new IntSetting.Builder()
            .name("cursor-search-radius")
            .description("Horizontal search radius around the cursor hit to find a valid cross anchor.")
            .defaultValue(2)
            .min(0).max(8)
            .build());

        this.elytraMode = this.sgGeneral.add(new BoolSetting.Builder()
            .name("elytra-mode")
            .description("Disables cursor placement and anchors the cross 2 blocks behind you (useful while ebouncing)")
            .defaultValue(false)
            .build());

        // Render group
        this.sgRender = this.settings.createGroup("Render");

        this.render = this.sgRender.add(new BoolSetting.Builder()
            .name("render")
            .description("Render the planned cross structure.")
            .defaultValue(true)
            .build());

        this.shapeMode = this.sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the boxes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build());

        this.sideColor = this.sgRender.add(new ColorSetting.Builder()
            .name("side-color")
            .description("Color of the box sides for blocks that are not yet placed.")
            .defaultValue(new SettingColor(255, 50, 50, 25))
            .build());

        this.lineColor = this.sgRender.add(new ColorSetting.Builder()
            .name("line-color")
            .description("Outline color for blocks that are not yet placed.")
            .defaultValue(new SettingColor(255, 50, 50, 255))
            .build());

        this.placedSideColor = this.sgRender.add(new ColorSetting.Builder()
            .name("placed-side-color")
            .description("Side color for blocks that are already placed.")
            .defaultValue(new SettingColor(50, 255, 50, 25))
            .build());

        this.placedLineColor = this.sgRender.add(new ColorSetting.Builder()
            .name("placed-line-color")
            .description("Outline color for blocks that are already placed.")
            .defaultValue(new SettingColor(50, 255, 50, 255))
            .build());

        this.steps = new ArrayList<>();
        this.lastPlaceKeyDown = false;
    }

    /**
     * Reset state. If world/player isn't ready, disable the module to prevent NPE spam.
     */
    public void onActivate() {
        this.steps.clear();
        if (this.mc.player == null || this.mc.world == null) {
            this.warning("Player/world not loaded");
            this.toggle();
        }
    }

    public void onDeactivate() {
        this.steps.clear();
    }

    /**
     * Render handler does both:
     * - keybind polling and triggering enqueue
     * - preview rendering of the planned cross
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (this.mc.world == null) return;

        boolean placeKeyDown = this.crossPlace.get().isPressed();

        // Rising edge: attempt to place cross
        if (placeKeyDown && !this.lastPlaceKeyDown) {
            if (!this.hasRequiredMaterialsInHotbar()) {
                if (!this.silentMode.get()) {
                    this.warning("Not enough blocks in hotbar to build a cross (need 6)");
                }
            } else {
                this.steps.clear();
                this.preparePattern(); // fills steps

                if (this.steps.isEmpty()) {
                    if (!this.silentMode.get()) this.warning("No valid build position found");
                } else {
                    // Enqueue only missing blocks (skip blocks already present)
                    int queued = 0;
                    for (PlacementStep step : this.steps) {
                        Block current = this.mc.world.getBlockState(step.pos).getBlock();
                        if (current == step.block) continue;

                        GriefKit.PLACEMENT.enqueue(step);
                        ++queued;
                    }

                    if (!this.silentMode.get()) this.info("Queued " + queued + " blocks");
                }
            }
        }

        this.lastPlaceKeyDown = placeKeyDown;

        // Preview rendering
        if (!this.render.get()) return;

        // Always recompute plan for preview so it follows cursor/player position
        this.steps.clear();
        this.preparePattern();
        if (this.steps.isEmpty()) return;

        for (PlacementStep step : this.steps) {
            boolean alreadyPlaced = this.mc.world.getBlockState(step.pos).getBlock() == step.block;

            SettingColor side = alreadyPlaced ? this.placedSideColor.get() : this.sideColor.get();
            SettingColor line = alreadyPlaced ? this.placedLineColor.get() : this.lineColor.get();

            event.renderer.box(step.pos, (Color) side, (Color) line, this.shapeMode.get(), 0);
        }
    }

    /**
     * Needs 6 blocks of the selected type in hotbar, since the cross pattern is 6 blocks.
     */
    private boolean hasRequiredMaterialsInHotbar() {
        if (this.mc.player == null) return false;

        PlayerInventory inv = this.mc.player.getInventory();
        Item want = this.block.get().asItem();

        return GriefKit.INVENTORY.hasHotbarStackAtLeast(inv, item -> item == want, 6);
    }

    /**
     * "Obstructed" means the block space is not usable:
     * - If not air AND not replaceable, don't plan into it.
     */
    private boolean isObstructed(BlockPos pos) {
        return !this.mc.world.getBlockState(pos).isAir() && !this.mc.world.getBlockState(pos).isReplaceable();
    }

    /**
     * Prevent planning cross blocks inside entities:
     * - self collision
     * - other living, non-spectator entities
     */
    private boolean isEntityIntersecting(BlockPos pos) {
        if (this.mc.world == null || this.mc.player == null) return false;

        Box box = new Box(pos);

        if (!this.mc.player.isSpectator() && this.mc.player.isAlive() && this.mc.player.getBoundingBox().intersects(box)) {
            return true;
        }

        return !this.mc.world.getOtherEntities(null, box, entity -> !entity.isSpectator() && entity.isAlive()).isEmpty();
    }

    /** True if any planned position is blocked or has an entity intersecting it. */
    private boolean anyBlockedOrEntity(List<BlockPos> positions) {
        for (BlockPos p : positions) {
            if (!this.isObstructed(p) && !this.isEntityIntersecting(p)) continue;
            return true;
        }
        return false;
    }

    // ----------------------
    // Pattern generation
    // ----------------------

    /**
     * Vertical cross:
     * - stem grows upward: s0,s1,s2,s3
     * - arms are at s1 (second block), perpendicular to facing
     *
     * stemBottom is s0.
     */
    private List<BlockPos> getVerticalCross(BlockPos stemBottom, Direction facing) {
        BlockPos s0 = stemBottom;
        BlockPos s1 = s0.up();
        BlockPos s2 = s1.up();
        BlockPos s3 = s2.up();

        Direction left = facing.rotateYCounterclockwise();
        Direction right = facing.rotateYClockwise();

        BlockPos l = s1.offset(left);
        BlockPos r = s1.offset(right);

        return List.of(s0, s1, s2, s3, l, r);
    }

    /**
     * Horizontal cross:
     * - stem grows forward: s0,s1,s2,s3 along facing direction
     * - arms are at s1, perpendicular to facing
     *
     * stemStart is s0.
     */
    private List<BlockPos> getHorizontalCross(BlockPos stemStart, Direction facing) {
        BlockPos s0 = stemStart;
        BlockPos s1 = s0.offset(facing);
        BlockPos s2 = s1.offset(facing);
        BlockPos s3 = s2.offset(facing);

        Direction left = facing.rotateYCounterclockwise();
        Direction right = facing.rotateYClockwise();

        BlockPos l = s1.offset(left);
        BlockPos r = s1.offset(right);

        return List.of(s0, s1, s2, s3, l, r);
    }

    /** Pattern valid if all 6 positions are clear. */
    private boolean validateVerticalCross(BlockPos stemBottom, Direction facing) {
        return !this.anyBlockedOrEntity(this.getVerticalCross(stemBottom, facing));
    }

    /** Pattern valid if all 6 positions are clear. */
    private boolean validateHorizontalCross(BlockPos stemStart, Direction facing) {
        return !this.anyBlockedOrEntity(this.getHorizontalCross(stemStart, facing));
    }

    /**
     * Converts a BlockHitResult into a reasonable “anchor base”:
     * - If you hit top or bottom, prefer placing ABOVE the hit block.
     * - Otherwise place adjacent to the hit face.
     *
     * This matches your Wither logic and makes cursor anchoring predictable.
     */
    private BlockPos computeBaseFromHit(BlockHitResult hit) {
        return switch (hit.getSide()) {
            case UP -> hit.getBlockPos().up();
            case DOWN -> hit.getBlockPos().up();
            default -> hit.getBlockPos().offset(hit.getSide());
        };
    }

    /**
     * Finds the best vertical anchor near cursor:
     * - scan dx/dz around base
     * - candidate must validateVerticalCross
     * - the cross “center” for distance checks is s1 (candidate.up())
     * - candidate must be within cursorMaxDistance from player eye (via center position)
     * - choose the candidate whose center is closest to the cursor hit point
     */
    private BlockPos findBestVerticalAnchor(BlockHitResult hit, Direction facing) {
        if (this.mc.world == null || this.mc.player == null) return null;

        BlockPos base = this.computeBaseFromHit(hit);
        int radius = this.cursorSearchRadius.get();

        Vec3d hitVec = hit.getPos();

        double maxDist = this.cursorMaxDistance.get();
        double maxDistSq = maxDist * maxDist;

        Vec3d eyePos = this.mc.player.getEyePos();

        BlockPos best = null;
        double bestCursorDistSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                BlockPos candidate = base.add(dx, 0, dz);

                if (!this.validateVerticalCross(candidate, facing)) continue;

                // center chosen as s1 (second block of the stem)
                Vec3d center = Vec3d.ofCenter(candidate.up());

                // must be close enough to the player (avoid placing too far)
                if (center.squaredDistanceTo(eyePos) > maxDistSq) continue;

                // choose the candidate closest to where the cursor hit
                double cursorDistSq = center.squaredDistanceTo(hitVec);
                if (cursorDistSq >= bestCursorDistSq) continue;

                bestCursorDistSq = cursorDistSq;
                best = candidate;
            }
        }

        return best;
    }

    /**
     * Finds the best horizontal anchor near cursor:
     * - same scan strategy
     * - uses s1 = candidate.offset(facing) as the “center” reference
     */
    private BlockPos findBestHorizontalAnchor(BlockHitResult hit, Direction facing) {
        if (this.mc.world == null || this.mc.player == null) return null;

        BlockPos base = this.computeBaseFromHit(hit);
        int radius = this.cursorSearchRadius.get();

        Vec3d hitVec = hit.getPos();

        double maxDist = this.cursorMaxDistance.get();
        double maxDistSq = maxDist * maxDist;

        Vec3d eyePos = this.mc.player.getEyePos();

        BlockPos best = null;
        double bestCursorDistSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                BlockPos candidate = base.add(dx, 0, dz);

                if (!this.validateHorizontalCross(candidate, facing)) continue;

                // center chosen as s1 (the second block forward)
                Vec3d center = Vec3d.ofCenter(candidate.offset(facing));

                if (center.squaredDistanceTo(eyePos) > maxDistSq) continue;

                double cursorDistSq = center.squaredDistanceTo(hitVec);
                if (cursorDistSq >= bestCursorDistSq) continue;

                bestCursorDistSq = cursorDistSq;
                best = candidate;
            }
        }

        return best;
    }

    /**
     * Builds PlacementSteps for vertical cross.
     *
     * NOTE: you pass Direction.UP for every step’s supportFace.
     * That implies PlacementManager will "click from above/up-face" style placement.
     * If PlacementManager interprets supportFace as "the face of the support block to click",
     * UP is a stable choice, but it's not necessarily “correct” for all situations.
     */
    private void buildStepsVertical(BlockPos stemBottom, Direction facing) {
        this.steps.clear();
        Block b = this.block.get();

        for (BlockPos p : this.getVerticalCross(stemBottom, facing)) {
            this.steps.add(new PlacementStep(p, b, Direction.UP));
        }
    }

    /**
     * Builds PlacementSteps for horizontal cross.
     *
     * Same note as above: still uses Direction.UP for supportFace.
     */
    private void buildStepsHorizontal(BlockPos stemStart, Direction facing) {
        this.steps.clear();
        Block b = this.block.get();

        for (BlockPos p : this.getHorizontalCross(stemStart, facing)) {
            this.steps.add(new PlacementStep(p, b, Direction.UP));
        }
    }

    /**
     * Computes the plan (fills steps) using the same priority as Wither:
     * 1) Elytra mode: anchor 2 blocks behind; prefer vertical then horizontal
     * 2) Cursor placement: scan around crosshair hit; prefer vertical then horizontal
     * 3) Default: anchor 2 blocks in front; prefer vertical then horizontal
     */
    private void preparePattern() {
        ClientPlayerEntity player = this.mc.player;
        if (player == null || this.mc.world == null) return;

        Direction facing = player.getHorizontalFacing();

        BlockPos anchor = null;
        CrossOrientation orientation = CrossOrientation.VERTICAL;

        // 1) Elytra mode anchor (behind you)
        if (this.elytraMode.get()) {
            BlockPos base = player.getBlockPos().offset(facing.getOpposite(), 2);

            if (this.validateVerticalCross(base, facing)) {
                anchor = base;
                orientation = CrossOrientation.VERTICAL;
            } else if (this.validateHorizontalCross(base, facing)) {
                anchor = base;
                orientation = CrossOrientation.HORIZONTAL;
            } else {
                this.steps.clear();
                return;
            }

            if (orientation == CrossOrientation.VERTICAL) this.buildStepsVertical(anchor, facing);
            else this.buildStepsHorizontal(anchor, facing);

            return;
        }

        // 2) Cursor placement (crosshair)
        if (this.cursorPlacement.get() && this.mc.crosshairTarget instanceof BlockHitResult) {
            BlockHitResult bhr = (BlockHitResult) this.mc.crosshairTarget;

            anchor = this.findBestVerticalAnchor(bhr, facing);
            orientation = CrossOrientation.VERTICAL;

            if (anchor == null) {
                anchor = this.findBestHorizontalAnchor(bhr, facing);
                orientation = CrossOrientation.HORIZONTAL;
            }
        }

        // 3) Default placement (in front)
        if (anchor == null) {
            BlockPos base = player.getBlockPos().offset(facing, 2);

            if (this.validateVerticalCross(base, facing)) {
                anchor = base;
                orientation = CrossOrientation.VERTICAL;
            } else if (this.validateHorizontalCross(base, facing)) {
                anchor = base;
                orientation = CrossOrientation.HORIZONTAL;
            } else {
                this.steps.clear();
                return;
            }
        }

        if (orientation == CrossOrientation.VERTICAL) this.buildStepsVertical(anchor, facing);
        else this.buildStepsHorizontal(anchor, facing);
    }

    private static enum CrossOrientation { VERTICAL, HORIZONTAL }
}
