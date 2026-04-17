package com.griefkit.modules;

import com.griefkit.GriefKit;
import com.griefkit.helpers.HotbarSupply;
import com.griefkit.helpers.gooner.BaritoneInterface;
import com.griefkit.helpers.gooner.BaritoneState;
import com.griefkit.helpers.gooner.ClogPatternGenerator;
import com.griefkit.helpers.gooner.ClogPatternGenerator.ClogMode;
import com.griefkit.helpers.gooner.ClogPatternGenerator.PlacementEntry;
import com.griefkit.helpers.gooner.GoonerPlacement;
import com.griefkit.helpers.gooner.HighwayDetector;
import com.griefkit.helpers.gooner.HighwayDetector.HighwayInfo;
import com.griefkit.helpers.gooner.HighwayDetector.NearbyHighway;
import com.griefkit.helpers.gooner.StatsHandler;
import com.griefkit.managers.ToolManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.player.AutoGap;
import meteordevelopment.meteorclient.systems.modules.render.FreeLook;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * HighwayGoonerV2 — Smart highway clogger.
 * Orchestrator: detects highway, generates clog patterns, places them via
 * GoonerPlacement air-place, walks via Baritone, triggers GoonerWither every N
 * blocks, optionally turns at junctions/corners.
 *
 * Stripped from anarchy port: restock, echest farming, speedmine, anti-nuker,
 * license gates, anarchy telemetry.
 */
public class HighwayGoonerV2 extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    public static HighwayGoonerV2 INSTANCE;

    private static final long BARITONE_RECHECK_MS = 2000;

    // Hole-patch ignore blocks
    private static final List<Block> HOLE_PATCH_IGNORE = List.of(
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
            Blocks.WITHER_SKELETON_SKULL, Blocks.WITHER_SKELETON_WALL_SKULL
    );

    private static final Set<Block> OPAQUE_JUNK = Set.of(
            Blocks.COBBLESTONE, Blocks.NETHERRACK, Blocks.STONE, Blocks.DIRT,
            Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE, Blocks.BASALT,
            Blocks.BLACKSTONE, Blocks.END_STONE, Blocks.ANDESITE,
            Blocks.DIORITE, Blocks.GRANITE, Blocks.GRAVEL, Blocks.SAND,
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.TUFF
    );

    // === Settings: Detection ===
    private final SettingGroup sgDetection = settings.createGroup("Detection");

    private final Setting<Boolean> smartDetection = sgDetection.add(new BoolSetting.Builder()
        .name("smart-detection")
        .description("Auto-detect highway axis, width, and rails on enable.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> rescanKey = sgDetection.add(new KeybindSetting.Builder()
        .name("rescan-key")
        .description("Press to re-detect the highway at your current position.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Double> detectionConfidence = sgDetection.add(new DoubleSetting.Builder()
        .name("min-confidence")
        .description("Minimum detection confidence threshold.")
        .defaultValue(0.3)
        .range(0.1, 1.0)
        .sliderRange(0.1, 1.0)
        .build()
    );

    private final Setting<Boolean> detectRingRoads = sgDetection.add(new BoolSetting.Builder()
        .name("detect-ring-roads")
        .description("Classify ring roads (axis-aligned squares at known distances from 0,0).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> detectDiamondRoads = sgDetection.add(new BoolSetting.Builder()
        .name("detect-diamond-roads")
        .description("Classify diamond roads (45° rotated squares at known |x|+|z| distances).")
        .defaultValue(true)
        .build()
    );

    // === Settings: Clog Pattern ===
    private final SettingGroup sgPattern = settings.createGroup("Clog Pattern");

    private final Setting<ClogMode> clogMode = sgPattern.add(new EnumSetting.Builder<ClogMode>()
        .name("clog-mode")
        .description("Clog density. BITCH = every 8 steps (sparse), STANDARD = every 4, CLOG = every step (full).")
        .defaultValue(ClogMode.STANDARD)
        .build()
    );

    private final Setting<List<Block>> clogBlocks = sgPattern.add(new BlockListSetting.Builder()
        .name("clog-blocks")
        .description("Blocks used for clog placements. Any block in this list is valid.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN)
        .build()
    );

    // === Settings: Block Clearing ===
    private final SettingGroup sgClear = settings.createGroup("Block Clearing");

    private final Setting<Boolean> autoClearBlocks = sgClear.add(new BoolSetting.Builder()
        .name("auto-clear-blocks")
        .description("Break whitelisted blocks within reach before placing patterns. Clears obstructions like ender chests and signs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> clearBlocks = sgClear.add(new BlockListSetting.Builder()
        .name("clear-blocks")
        .description("Blocks to break when they block the pattern.")
        .defaultValue(Blocks.ENDER_CHEST)
        .build()
    );

    // === Settings: Walking ===
    private final SettingGroup sgWalk = settings.createGroup("Walking");

    private final Setting<Boolean> autoWalk = sgWalk.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Use Baritone to walk to the next pattern position. Disable to walk manually.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> walkTowardOrigin = sgWalk.add(new BoolSetting.Builder()
        .name("walk-toward-origin")
        .description("⚠ WALKS TOWARD 0,0. Default is away from spawn.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> autoWalkDelay = sgWalk.add(new IntSetting.Builder()
        .name("auto-walk-delay-ms")
        .description("Extra delay after pattern completes before Baritone is invoked.")
        .defaultValue(0)
        .range(0, 5000)
        .sliderRange(0, 5000)
        .build()
    );

    private final Setting<Boolean> patchHolesBeforeWalk = sgWalk.add(new BoolSetting.Builder()
        .name("patch-holes-before-walk")
        .description("Before walking to the next step, patch any hole in the floor so you don't fall.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> suppressBaritoneChat = sgWalk.add(new BoolSetting.Builder()
        .name("suppress-baritone-chat")
        .description("Silence Baritone's chat spam during Gooner runs.")
        .defaultValue(true)
        .build()
    );

    // === Settings: Meteor Integration (yield + freelook) ===
    private final SettingGroup sgYield = settings.createGroup("Meteor Integration");

    private final Setting<Boolean> enableFreeLook = sgYield.add(new BoolSetting.Builder()
        .name("auto-freelook")
        .description("Enable Meteor's FreeLook on activate, restore to prior state on disable.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> yieldToAutoEat = sgYield.add(new BoolSetting.Builder()
        .name("yield-to-autoeat")
        .description("Pause the gooner tick while Meteor's AutoEat is eating. Keeps hotbar untouched during food swaps.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> yieldToAutoGap = sgYield.add(new BoolSetting.Builder()
        .name("yield-to-autogap")
        .description("Pause the gooner tick while Meteor's AutoGap is eating a gapple.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> yieldToKillAura = sgYield.add(new BoolSetting.Builder()
        .name("yield-to-killaura")
        .description("Pause the gooner tick while Meteor's KillAura is attacking. Lets combat take priority.")
        .defaultValue(true)
        .build()
    );

    // === Settings: Wither ===
    private final SettingGroup sgWither = settings.createGroup("Wither");

    private final Setting<Boolean> enableAutoWither = sgWither.add(new BoolSetting.Builder()
        .name("auto-wither")
        .description("Toggle GoonerWither every N blocks to place a wither along the highway.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> witherEveryBlocks = sgWither.add(new IntSetting.Builder()
        .name("wither-every-blocks")
        .description("Distance between wither placements in blocks.")
        .defaultValue(50)
        .range(10, 300)
        .sliderRange(10, 300)
        .build()
    );

    // === Settings: Placement ===
    private final SettingGroup sgPlace = settings.createGroup("Placement");

    private final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay-ms")
        .description("Delay between block placements (ms).")
        .defaultValue(30)
        .range(0, 500)
        .sliderRange(0, 500)
        .build()
    );

    private final Setting<Integer> patternDelay = sgPlace.add(new IntSetting.Builder()
        .name("pattern-delay-ticks")
        .description("Delay between clog steps (ticks).")
        .defaultValue(2)
        .range(1, 40)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> placeHotbarSlot = sgPlace.add(new IntSetting.Builder()
        .name("place-hotbar-slot")
        .description("Preferred hotbar slot (1-9) for clog block placement.")
        .defaultValue(1)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> refillThreshold = sgPlace.add(new IntSetting.Builder()
        .name("refill-threshold")
        .description("Min clog block count before pulling from inventory.")
        .defaultValue(32)
        .range(1, 64)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Boolean> legitRotation = sgPlace.add(new BoolSetting.Builder()
        .name("legit-rotation")
        .description("Rotate player head to face placement target (grim-safer).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapOpaqueBlocks = sgPlace.add(new BoolSetting.Builder()
        .name("swap-opaque-blocks")
        .description("On enable, move opaque junk (cobble, netherrack, etc.) out of hotbar and pull clog blocks in.")
        .defaultValue(false)
        .build()
    );

    // === Settings: Junction Turning ===
    private final SettingGroup sgJunction = settings.createGroup("Junction & Turning");

    private final Setting<Boolean> autoTurn = sgJunction.add(new BoolSetting.Builder()
        .name("auto-turn")
        .description("Automatically turn at junctions and corners between highways.")
        .defaultValue(false)
        .build()
    );

    private final Setting<TurnPolicy> turnPolicy = sgJunction.add(new EnumSetting.Builder<TurnPolicy>()
        .name("turn-policy")
        .description("At junctions: CONTINUE_STRAIGHT, TURN_LEFT/RIGHT, PREFER_CARDINAL/RING/DIAMOND, ZIGZAG.")
        .defaultValue(TurnPolicy.CONTINUE_STRAIGHT)
        .build()
    );

    private final Setting<Integer> junctionApproachDist = sgJunction.add(new IntSetting.Builder()
        .name("junction-detect-dist")
        .description("How close (in blocks) to the junction before starting the turn sequence.")
        .defaultValue(10)
        .range(5, 30)
        .sliderRange(5, 30)
        .build()
    );

    // === Settings: Safety ===
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    private final Setting<Boolean> autoDisableOnPlayer = sgSafety.add(new BoolSetting.Builder()
        .name("disable-on-player")
        .description("Auto-disable when a non-friend player comes within render distance. Friends are ignored.")
        .defaultValue(true)
        .build()
    );

    // === Settings: Render ===
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> renderOverlay = sgRender.add(new BoolSetting.Builder()
        .name("render-overlay")
        .description("Master toggle for all 3D overlays below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderHighwayStructure = sgRender.add(new BoolSetting.Builder()
        .name("render-highway")
        .description("Render the detected highway floor and rails.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderPattern = sgRender.add(new BoolSetting.Builder()
        .name("render-pattern")
        .description("Render the pending clog step pattern.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderNearbyHighways = sgRender.add(new BoolSetting.Builder()
        .name("render-nearby-highways")
        .description("Render nearby secondary highways detected on enable.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> highwayColor = sgRender.add(new ColorSetting.Builder()
        .name("highway-color")
        .defaultValue(new SettingColor(0, 255, 0, 32))
        .build()
    );

    private final Setting<SettingColor> railColor = sgRender.add(new ColorSetting.Builder()
        .name("rail-color")
        .defaultValue(new SettingColor(0, 136, 255, 32))
        .build()
    );

    private final Setting<SettingColor> patternPendingColor = sgRender.add(new ColorSetting.Builder()
        .name("pattern-pending-color")
        .defaultValue(new SettingColor(255, 0, 0, 50))
        .build()
    );

    private final Setting<SettingColor> patternPlacedColor = sgRender.add(new ColorSetting.Builder()
        .name("pattern-placed-color")
        .defaultValue(new SettingColor(0, 255, 0, 50))
        .build()
    );

    private final Setting<SettingColor> nearbyColor = sgRender.add(new ColorSetting.Builder()
        .name("nearby-highway-color")
        .defaultValue(new SettingColor(255, 136, 0, 50))
        .build()
    );

    // === Enums / Records ===

    public enum TurnPolicy {
        CONTINUE_STRAIGHT,
        TURN_LEFT,
        TURN_RIGHT,
        PREFER_CARDINAL,
        PREFER_RING,
        PREFER_DIAMOND,
        ZIGZAG
    }

    private enum State {
        BUILDING, PATTERN_COMPLETE_DELAY, WALKING, WITHERING, WAITING,
        APPROACHING_JUNCTION, TURNING, REALIGNING
    }

    private record JunctionPoint(
            BlockPos pos,
            boolean isCorner,
            HighwayDetector.HighwayCategory crossingCategory,
            double crossingDist
    ) {}

    // === Runtime state ===

    private HighwayInfo detectedHighway = null;
    private List<NearbyHighway> nearbyHighways = new ArrayList<>();
    private boolean isWalkingTowardOrigin = false;
    private Direction initialFacing;

    private List<PlacementEntry> currentStepPlan = new ArrayList<>();
    private int stepPlanIndex = 0;
    private int stepsBuilt = 0;
    private final List<BlockPos> placedBlocks = new ArrayList<>();

    private State state = State.BUILDING;
    private long lastPlaceTime = 0;
    private long patternCompleteTime = 0;
    private int walkTicks = 0;
    private int goonerTick = 0;
    private int totalBlocksPlaced = 0;

    // Wither state
    private int blocksSinceWither = 0;
    private int witherWalkStepsRemaining = 0;
    private boolean witherQueued = false;
    private boolean witherFailed = false;
    private int witherRetryCountdown = 0;
    private int witherAttempts = 0;

    // Hotbar pinning
    private int pinnedHotbarSlotRuntime = -1;

    // Block clearing
    private BlockPos clearingBlockTarget = null;

    // Junction tracking
    private final List<JunctionPoint> junctionPoints = new ArrayList<>();
    private int nextJunctionIndex = 0;
    private HighwayInfo preJunctionHighway = null;
    private BlockPos turnTarget = null;
    private int turnAttempts = 0;
    private int realignAttempts = 0;
    private boolean zigzagLeft = true;

    // Baritone
    private boolean baritoneAvailable = false;
    private boolean baritoneWarned = false;
    private long lastBaritoneCheckMs = 0L;

    // FreeLook state preservation
    private boolean freeLookToggledByUs = false;

    public HighwayGoonerV2() {
        super(GriefKit.CATEGORY, "highway-gooner-v2",
            "Smart highway clogger — detects highway, places clog patterns via air-place, walks via Baritone, triggers GoonerWither every N blocks.");
        INSTANCE = this;
    }

    // === Highway Detection ===

    public HighwayInfo getDetectedHighway() { return detectedHighway; }

    private void rescanHighway() {
        if (mc.player == null || mc.world == null) {
            ChatUtils.warning("HighwayGoonerV2: cannot scan — player/world not loaded.");
            return;
        }

        detectedHighway = HighwayDetector.detect(mc, detectionConfidence.get().floatValue(),
                detectRingRoads.get(), detectDiamondRoads.get());
        if (detectedHighway != null) {
            nearbyHighways = HighwayDetector.scanNearbyHighways(mc, detectedHighway, 100);
            ChatUtils.info("HighwayGoonerV2: detected %s", detectedHighway);
            if (!nearbyHighways.isEmpty()) {
                ChatUtils.info("HighwayGoonerV2: found %d nearby highway(s).", nearbyHighways.size());
            }
            computeJunctionPoints();
        } else {
            nearbyHighways.clear();
            ChatUtils.warning("HighwayGoonerV2: no highway detected (confidence below %.0f%%). Reposition and try again.",
                    detectionConfidence.get() * 100);
        }
    }

    // === Lifecycle ===

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            warning("player/world not loaded");
            toggle();
            return;
        }

        StatsHandler.reset();
        walkTicks = 0;
        state = State.BUILDING;
        placedBlocks.clear();
        stepsBuilt = 0;
        stepPlanIndex = 0;
        currentStepPlan.clear();
        lastPlaceTime = 0;
        patternCompleteTime = 0;
        goonerTick = 0;
        totalBlocksPlaced = 0;
        blocksSinceWither = 0;
        witherQueued = false;
        witherFailed = false;
        witherRetryCountdown = 0;
        witherAttempts = 0;
        witherWalkStepsRemaining = 0;
        clearingBlockTarget = null;

        if (smartDetection.get()) {
            detectedHighway = HighwayDetector.detect(mc, detectionConfidence.get().floatValue(),
                    detectRingRoads.get(), detectDiamondRoads.get());
            if (detectedHighway != null) {
                nearbyHighways = HighwayDetector.scanNearbyHighways(mc, detectedHighway, 100);
                initialFacing = detectedHighway.facingDirection;
                ChatUtils.info("HighwayGoonerV2: detected %s", detectedHighway);
                if (!nearbyHighways.isEmpty()) {
                    ChatUtils.info("HighwayGoonerV2: %d nearby highway(s).", nearbyHighways.size());
                }
            } else {
                nearbyHighways.clear();
                warning("no highway detected. Stand on a cardinal/diagonal highway and try again.");
                toggle();
                return;
            }

            // Validate: diagonal highways must be intercardinal (|x|≈|z|) unless it's a diamond
            if (detectedHighway.diagonal && detectedHighway.category != HighwayDetector.HighwayCategory.DIAMOND) {
                int absX = Math.abs(mc.player.getBlockPos().getX());
                int absZ = Math.abs(mc.player.getBlockPos().getZ());
                int diff = Math.abs(absX - absZ);
                if (diff > 200) {
                    warning("this diagonal is a side road, not a main intercardinal highway. Module only works on main highways.");
                    toggle();
                    return;
                }
            }
        } else {
            warning("smart detection is required. Enable it and try again.");
            toggle();
            return;
        }

        computeJunctionPoints();

        if (walkTowardOrigin.get()) {
            isWalkingTowardOrigin = true;
            ChatUtils.warning("§c§l⚠ WARNING: WALKING TOWARD 0,0! ⚠");
            ChatUtils.warning("§cYou are clogging TOWARD spawn. Disable 'walk-toward-origin' to reverse.");
        } else {
            isWalkingTowardOrigin = false;
        }

        if (detectedHighway != null) {
            generateNextStepPlan();
        }

        detectPinnedHotbarSlot();
        baritoneAvailable = BaritoneInterface.isBaritoneAvailable();
        lastBaritoneCheckMs = System.currentTimeMillis();
        baritoneWarned = false;

        if (baritoneAvailable) {
            ChatUtils.info("§aBaritone detected§r — auto-walk ready.");
        } else {
            if (autoWalk.get()) {
                ChatUtils.warning("§cBaritone NOT detected§r — auto-walk will do nothing. Install Baritone for 1.21.11, or disable auto-walk and walk manually.");
            } else {
                ChatUtils.warning("§cBaritone NOT detected§r — gooner will only place (no walking). You are already in manual mode.");
            }
            baritoneWarned = true;
        }

        try {
            BaritoneInterface.setSetting("allowSprint", true);
        } catch (Throwable ignored) {}

        if (swapOpaqueBlocks.get()) {
            swapOpaqueBlocksFromHotbar();
        }

        if (suppressBaritoneChat.get()) {
            try {
                BaritoneInterface.setSetting("chatControl", false);
                BaritoneInterface.setSetting("chatDebug", false);
            } catch (Throwable ignored) {}
        }

        // FreeLook auto-toggle (preserves prior state)
        freeLookToggledByUs = false;
        if (enableFreeLook.get()) {
            FreeLook freeLook = Modules.get().get(FreeLook.class);
            if (freeLook != null && !freeLook.isActive()) {
                freeLook.toggle();
                freeLookToggledByUs = true;
            }
        }

        ChatUtils.info("HighwayGoonerV2 enabled");
    }

    @Override
    public void onDeactivate() {
        placedBlocks.clear();
        currentStepPlan.clear();
        junctionPoints.clear();
        nextJunctionIndex = 0;
        preJunctionHighway = null;
        turnTarget = null;
        clearingBlockTarget = null;

        // Make sure GoonerWither isn't left running
        GoonerWither wither = Modules.get().get(GoonerWither.class);
        if (wither != null && wither.isActive()) wither.toggle();

        // Restore FreeLook if we toggled it on
        if (freeLookToggledByUs) {
            FreeLook freeLook = Modules.get().get(FreeLook.class);
            if (freeLook != null && freeLook.isActive()) freeLook.toggle();
            freeLookToggledByUs = false;
        }

        BaritoneState.groundWalking = false;
        if (ensureBaritoneAvailable(false)) {
            try { BaritoneInterface.cancelAll(); } catch (Throwable ignored) {}
        }
        BaritoneState.pathing = false;
        BaritoneState.needsPathingCheck = false;

        ChatUtils.info("HighwayGoonerV2 disabled");
    }

    // === Step Plan Generation ===

    private void generateNextStepPlan() {
        currentStepPlan.clear();
        stepPlanIndex = 0;

        if (detectedHighway == null || mc.player == null) return;

        ClogMode mode = clogMode.get();
        if (stepsBuilt % mode.stepSpacing != 0) {
            return;
        }

        // stepDx/stepDz point AWAY from 0,0.
        // Default: walk away from 0,0 (+step), build toward 0,0 (-step).
        BlockPos playerPos = mc.player.getBlockPos();
        int sdx = detectedHighway.axis.stepDx;
        int sdz = detectedHighway.axis.stepDz;
        int buildSign = isWalkingTowardOrigin ? 1 : -1;
        BlockPos stepCenter = playerPos.add(sdx * buildSign * 2, 0, sdz * buildSign * 2);

        currentStepPlan = ClogPatternGenerator.generateNextStep(detectedHighway, stepCenter);
    }

    // === Tick (main state machine) ===

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        // Rescan keybind
        if (rescanKey.get().isPressed()) {
            rescanHighway();
        }

        StatsHandler.onTick();

        // Safety: auto-disable when a non-friend player enters render distance
        if (autoDisableOnPlayer.get() && hasNearbyNonFriendPlayer()) {
            warning("non-friend player nearby — disabling for safety.");
            toggle();
            return;
        }

        // Yield to Meteor modules that need exclusive control
        if (isMeteorBusy()) return;

        goonerTick++;

        switch (state) {
            case BUILDING -> build();
            case PATTERN_COMPLETE_DELAY -> handlePatternCompleteDelay();
            case WALKING -> walk();
            case WITHERING -> witherState();
            case WAITING -> waitState();
            case APPROACHING_JUNCTION -> tickApproachingJunction();
            case TURNING -> tickTurning();
            case REALIGNING -> tickRealigning();
        }
    }

    // === Render ===

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderOverlay.get() || mc.player == null || mc.world == null) return;

        if (renderHighwayStructure.get() && detectedHighway != null) {
            renderHighwayOutline(event);
        }

        if (renderPattern.get()) {
            for (int i = 0; i < currentStepPlan.size(); i++) {
                PlacementEntry entry = currentStepPlan.get(i);
                boolean alreadyPlaced = !mc.world.getBlockState(entry.pos).isReplaceable();
                SettingColor c = (alreadyPlaced || i < stepPlanIndex)
                        ? patternPlacedColor.get() : patternPendingColor.get();
                SettingColor l = new SettingColor(c.r, c.g, c.b, 255);
                event.renderer.box(entry.pos, c, l, ShapeMode.Both, 0);
            }
        }

        if (renderNearbyHighways.get()) {
            SettingColor c = nearbyColor.get();
            SettingColor l = new SettingColor(c.r, c.g, c.b, 255);
            for (NearbyHighway nearby : nearbyHighways) {
                if (nearby.samplePos == null) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    BlockPos pos = nearby.samplePos.add(
                            nearby.axis.stepDx * dx, 0, nearby.axis.stepDz * dx);
                    event.renderer.box(pos, c, l, ShapeMode.Both, 0);
                }
            }
        }
    }

    private void renderHighwayOutline(Render3DEvent event) {
        if (detectedHighway == null || mc.player == null) return;

        HighwayInfo info = detectedHighway;
        BlockPos playerPos = mc.player.getBlockPos();
        int perpDx = info.axis.perpDx();
        int perpDz = info.axis.perpDz();
        int stepDx = info.axis.stepDx;
        int stepDz = info.axis.stepDz;
        int halfWidth = info.width / 2;

        SettingColor floor = highwayColor.get();
        SettingColor floorLine = new SettingColor(floor.r, floor.g, floor.b, 255);
        SettingColor rail = railColor.get();
        SettingColor railLine = new SettingColor(rail.r, rail.g, rail.b, 255);

        for (int step = -5; step <= 5; step++) {
            int sx = playerPos.getX() + stepDx * step;
            int sz = playerPos.getZ() + stepDz * step;

            for (int perp = -halfWidth; perp <= halfWidth; perp++) {
                BlockPos pos = new BlockPos(
                        sx + perpDx * perp, info.floorY, sz + perpDz * perp);
                event.renderer.box(pos, floor, floorLine, ShapeMode.Both, 0);
            }

            for (int railOffset : new int[]{-(halfWidth + 1), halfWidth + 1}) {
                BlockPos railBase = new BlockPos(
                        sx + perpDx * railOffset, info.floorY, sz + perpDz * railOffset);
                event.renderer.box(railBase, rail, railLine, ShapeMode.Both, 0);
                event.renderer.box(railBase.up(), rail, railLine, ShapeMode.Both, 0);
            }
        }
    }

    // === Build Logic ===

    private void build() {
        if (System.currentTimeMillis() - lastPlaceTime < placeDelay.get()) return;

        // Clear whitelisted blocks within reach before placing
        if (autoClearBlocks.get() && clearBlocksInReach()) return;

        // AutoWither — place a wither instead of a pattern when threshold met or retrying
        if (witherFailed) {
            if (witherRetryCountdown > 0) {
                witherRetryCountdown--;
            }
        }
        if ((witherFailed && witherRetryCountdown == 0) || canAttemptWitherNow()) {
            GoonerWither witherModule = Modules.get().get(GoonerWither.class);
            int ssThreshold = witherModule != null ? witherModule.getSoulSandThreshold() : 16;
            int skThreshold = witherModule != null ? witherModule.getSkullThreshold() : 6;
            int soulSand = ToolManager.countItem(Blocks.SOUL_SAND.asItem());
            int skulls = ToolManager.countItem(Items.WITHER_SKELETON_SKULL);

            if (soulSand < ssThreshold || skulls < skThreshold) {
                // No restock system in GriefKit — skip the cycle.
                warning("autoWither: low materials (sand=%d skulls=%d), skipping.", soulSand, skulls);
                blocksSinceWither = 0;
                witherQueued = false;
                witherFailed = false;
                witherAttempts = 0;
            } else {
                faceWitherBuildDirection();
                if (witherModule != null && !witherModule.isActive()) {
                    witherModule.toggle();
                }
                witherQueued = false;
                state = State.WITHERING;
                walkTicks = 2;
                return;
            }
        }

        // If plan is empty (sparse step — blocks already placed), walk past it.
        if (currentStepPlan.isEmpty()) {
            patternCompleteTime = System.currentTimeMillis();
            state = State.PATTERN_COMPLETE_DELAY;
            return;
        }

        // Place blocks from current step plan
        for (; stepPlanIndex < currentStepPlan.size(); stepPlanIndex++) {
            PlacementEntry entry = currentStepPlan.get(stepPlanIndex);

            BlockState existingState = mc.world.getBlockState(entry.pos);
            if (!existingState.isReplaceable()) {
                if (!placedBlocks.contains(entry.pos)) placedBlocks.add(entry.pos);
                continue;
            }

            if (placeBlock(entry.pos)) {
                if (!placedBlocks.contains(entry.pos)) placedBlocks.add(entry.pos);
                lastPlaceTime = System.currentTimeMillis();
                stepPlanIndex++;
                return; // one block per tick
            } else {
                // Skip failed blocks — keep going
                if (!placedBlocks.contains(entry.pos)) placedBlocks.add(entry.pos);
                continue;
            }
        }

        // Step complete
        patternCompleteTime = System.currentTimeMillis();
        state = State.PATTERN_COMPLETE_DELAY;
    }

    private void handlePatternCompleteDelay() {
        if (System.currentTimeMillis() - patternCompleteTime < autoWalkDelay.get()) return;

        // Patch the destination hole so we don't fall
        if (patchHolesBeforeWalk.get()) {
            patchDestinationHole();
        }

        boolean canWalk = autoWalk.get() && ensureBaritoneAvailable(true);
        if (canWalk) {
            startBaritoneWalk();
            state = State.WALKING;
        } else {
            state = State.WAITING;
        }
        walkTicks = patternDelay.get();
    }

    private void startBaritoneWalk() {
        if (!ensureBaritoneAvailable(false) || mc.player == null || detectedHighway == null) return;

        try {
            int sdx = detectedHighway.axis.stepDx;
            int sdz = detectedHighway.axis.stepDz;
            int walkSign = isWalkingTowardOrigin ? -1 : 1;
            BlockPos target = mc.player.getBlockPos().add(sdx * walkSign, 0, sdz * walkSign);

            if (BaritoneInterface.isActive()) return;
            BaritoneState.groundWalking = true;
            BaritoneInterface.setGoal(target);
        } catch (Throwable t) {
            BaritoneState.groundWalking = false;
            if (!baritoneWarned) {
                baritoneWarned = true;
                warning("Baritone walk failed: %s", t.getMessage());
            }
            state = State.WAITING;
            walkTicks = patternDelay.get();
        }
    }

    private void walk() {
        if (walkTicks-- > 0) return;

        if (!ensureBaritoneAvailable(false)) {
            state = State.WAITING;
            walkTicks = patternDelay.get();
            return;
        }

        Vec3d velocity = mc.player.getVelocity();
        if (velocity.horizontalLength() > 0.1 || BaritoneInterface.isActive()) {
            walkTicks = 5;
            return;
        }

        BaritoneState.groundWalking = false;
        blocksSinceWither++;

        if (checkJunctionProximity()) return;

        if (witherWalkStepsRemaining > 0) {
            witherWalkStepsRemaining--;
            stepsBuilt++;
            startBaritoneWalk();
            walkTicks = patternDelay.get();
            return;
        }

        state = State.WAITING;
        walkTicks = patternDelay.get();
    }

    private void witherState() {
        if (walkTicks-- > 0) return;

        GoonerWither wither = Modules.get().get(GoonerWither.class);
        if (wither == null) {
            state = State.WAITING;
            walkTicks = patternDelay.get();
            return;
        }

        if (!wither.isActive()) {
            if (wither.lastPlacementSucceeded) {
                blocksSinceWither = 0;
                witherQueued = false;
                witherFailed = false;
                witherAttempts = 0;
                state = State.WALKING;
                walkTicks = 0;
                witherWalkStepsRemaining = 10;
            } else {
                witherAttempts++;
                if (witherAttempts >= 3) {
                    blocksSinceWither = 0;
                    witherQueued = false;
                    witherFailed = false;
                    witherAttempts = 0;
                    warning("wither failed 3 times — skipping this cycle.");
                    state = State.WAITING;
                    walkTicks = patternDelay.get();
                } else {
                    witherFailed = true;
                    witherRetryCountdown = 1;
                    state = State.WAITING;
                    walkTicks = patternDelay.get();
                }
            }
        } else {
            walkTicks = 2;
        }
    }

    private void waitState() {
        if (walkTicks-- > 0) return;

        stepsBuilt++;
        placedBlocks.clear();
        generateNextStepPlan();
        ensureAnyClogBlock(false);

        state = State.BUILDING;
    }

    // === Junction / Corner System ===

    private void computeJunctionPoints() {
        junctionPoints.clear();
        nextJunctionIndex = 0;
        if (detectedHighway == null || mc.player == null) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int px = playerPos.getX();
        int pz = playerPos.getZ();

        HighwayDetector.HighwayCategory cat = detectedHighway.category;
        double dist = detectedHighway.ringOrDiamondDist;

        if (cat == HighwayDetector.HighwayCategory.RING && dist > 0) {
            int D = (int) dist;
            HighwayDetector.RingSide side = detectedHighway.ringSide;
            if (side != null) {
                switch (side) {
                    case NORTH, SOUTH -> {
                        int z = (side == HighwayDetector.RingSide.SOUTH) ? D : -D;
                        junctionPoints.add(new JunctionPoint(new BlockPos(D, detectedHighway.floorY, z), true, cat, dist));
                        junctionPoints.add(new JunctionPoint(new BlockPos(-D, detectedHighway.floorY, z), true, cat, dist));
                    }
                    case EAST, WEST -> {
                        int x = (side == HighwayDetector.RingSide.EAST) ? D : -D;
                        junctionPoints.add(new JunctionPoint(new BlockPos(x, detectedHighway.floorY, D), true, cat, dist));
                        junctionPoints.add(new JunctionPoint(new BlockPos(x, detectedHighway.floorY, -D), true, cat, dist));
                    }
                }
            }
            // Junctions with cardinal highways
            if (side == HighwayDetector.RingSide.NORTH || side == HighwayDetector.RingSide.SOUTH) {
                int z = side == HighwayDetector.RingSide.SOUTH ? D : -D;
                junctionPoints.add(new JunctionPoint(new BlockPos(0, detectedHighway.floorY, z), false,
                        HighwayDetector.HighwayCategory.CARDINAL, 0));
            } else if (side != null) {
                int x = side == HighwayDetector.RingSide.EAST ? D : -D;
                junctionPoints.add(new JunctionPoint(new BlockPos(x, detectedHighway.floorY, 0), false,
                        HighwayDetector.HighwayCategory.CARDINAL, 0));
            }
        } else if (cat == HighwayDetector.HighwayCategory.DIAMOND && dist > 0) {
            int D = (int) dist;
            HighwayDetector.DiamondSegment seg = detectedHighway.diamondSegment;
            if (seg != null) {
                switch (seg) {
                    case NE -> { junctionPoints.add(new JunctionPoint(new BlockPos(D, detectedHighway.floorY, 0), true, cat, dist));
                                 junctionPoints.add(new JunctionPoint(new BlockPos(0, detectedHighway.floorY, D), true, cat, dist)); }
                    case NW -> { junctionPoints.add(new JunctionPoint(new BlockPos(0, detectedHighway.floorY, D), true, cat, dist));
                                 junctionPoints.add(new JunctionPoint(new BlockPos(-D, detectedHighway.floorY, 0), true, cat, dist)); }
                    case SW -> { junctionPoints.add(new JunctionPoint(new BlockPos(-D, detectedHighway.floorY, 0), true, cat, dist));
                                 junctionPoints.add(new JunctionPoint(new BlockPos(0, detectedHighway.floorY, -D), true, cat, dist)); }
                    case SE -> { junctionPoints.add(new JunctionPoint(new BlockPos(0, detectedHighway.floorY, -D), true, cat, dist));
                                 junctionPoints.add(new JunctionPoint(new BlockPos(D, detectedHighway.floorY, 0), true, cat, dist)); }
                }
            }
        } else if (cat == HighwayDetector.HighwayCategory.CARDINAL || cat == HighwayDetector.HighwayCategory.DIAGONAL) {
            if (detectRingRoads.get()) {
                for (double ringD : HighwayDetector.RING_DISTANCES) {
                    BlockPos jp = computeAxisRingJunction(detectedHighway.axis, ringD);
                    if (jp != null) {
                        junctionPoints.add(new JunctionPoint(jp, false, HighwayDetector.HighwayCategory.RING, ringD));
                    }
                }
            }
            if (detectDiamondRoads.get()) {
                for (double diaD : HighwayDetector.DIAMOND_DISTANCES) {
                    BlockPos jp = computeAxisDiamondJunction(detectedHighway.axis, diaD);
                    if (jp != null) {
                        junctionPoints.add(new JunctionPoint(jp, false, HighwayDetector.HighwayCategory.DIAMOND, diaD));
                    }
                }
            }
        }

        // Sort by walk-direction projection, closest first
        int sdx = detectedHighway.axis.stepDx;
        int sdz = detectedHighway.axis.stepDz;
        int walkSign = isWalkingTowardOrigin ? -1 : 1;
        junctionPoints.sort((a, b) -> {
            int projA = (a.pos.getX() - px) * sdx * walkSign + (a.pos.getZ() - pz) * sdz * walkSign;
            int projB = (b.pos.getX() - px) * sdx * walkSign + (b.pos.getZ() - pz) * sdz * walkSign;
            return Integer.compare(projA, projB);
        });

        while (nextJunctionIndex < junctionPoints.size()) {
            JunctionPoint jp = junctionPoints.get(nextJunctionIndex);
            int proj = (jp.pos.getX() - px) * sdx * walkSign + (jp.pos.getZ() - pz) * sdz * walkSign;
            if (proj > 0) break;
            nextJunctionIndex++;
        }
    }

    private BlockPos computeAxisRingJunction(HighwayDetector.HighwayAxis axis, double D) {
        int floorY = detectedHighway != null ? detectedHighway.floorY : 120;
        int iD = (int) D;
        return switch (axis) {
            case PLUS_X -> new BlockPos(iD, floorY, 0);
            case MINUS_X -> new BlockPos(-iD, floorY, 0);
            case PLUS_Z -> new BlockPos(0, floorY, iD);
            case MINUS_Z -> new BlockPos(0, floorY, -iD);
            case DIAG_PX_PZ -> new BlockPos(iD, floorY, iD);
            case DIAG_PX_MZ -> new BlockPos(iD, floorY, -iD);
            case DIAG_MX_PZ -> new BlockPos(-iD, floorY, iD);
            case DIAG_MX_MZ -> new BlockPos(-iD, floorY, -iD);
        };
    }

    private BlockPos computeAxisDiamondJunction(HighwayDetector.HighwayAxis axis, double D) {
        int floorY = detectedHighway != null ? detectedHighway.floorY : 120;
        int iD = (int) D;
        return switch (axis) {
            case PLUS_X -> new BlockPos(iD, floorY, 0);
            case MINUS_X -> new BlockPos(-iD, floorY, 0);
            case PLUS_Z -> new BlockPos(0, floorY, iD);
            case MINUS_Z -> new BlockPos(0, floorY, -iD);
            case DIAG_PX_PZ -> new BlockPos(iD / 2, floorY, iD / 2);
            case DIAG_PX_MZ -> new BlockPos(iD / 2, floorY, -iD / 2);
            case DIAG_MX_PZ -> new BlockPos(-iD / 2, floorY, iD / 2);
            case DIAG_MX_MZ -> new BlockPos(-iD / 2, floorY, -iD / 2);
        };
    }

    private boolean checkJunctionProximity() {
        if (!autoTurn.get() || junctionPoints.isEmpty() || nextJunctionIndex >= junctionPoints.size()) return false;
        if (mc.player == null) return false;

        JunctionPoint next = junctionPoints.get(nextJunctionIndex);
        BlockPos playerPos = mc.player.getBlockPos();
        int dist = Math.abs(playerPos.getX() - next.pos.getX()) + Math.abs(playerPos.getZ() - next.pos.getZ());

        if (dist <= junctionApproachDist.get()) {
            preJunctionHighway = detectedHighway;
            turnTarget = null;
            turnAttempts = 0;
            realignAttempts = 0;

            if (next.isCorner) {
                ChatUtils.info("HighwayGoonerV2: approaching corner at %s", next.pos.toShortString());
            } else {
                TurnPolicy policy = turnPolicy.get();
                if (policy == TurnPolicy.CONTINUE_STRAIGHT) {
                    nextJunctionIndex++;
                    return false;
                }
                ChatUtils.info("HighwayGoonerV2: approaching junction at %s (%s dist=%d)",
                        next.pos.toShortString(), next.crossingCategory, (int) next.crossingDist);
            }

            state = State.APPROACHING_JUNCTION;
            return true;
        }
        return false;
    }

    private void tickApproachingJunction() {
        if (junctionPoints.isEmpty() || nextJunctionIndex >= junctionPoints.size()) {
            state = State.WALKING;
            return;
        }

        JunctionPoint target = junctionPoints.get(nextJunctionIndex);
        BlockPos playerPos = mc.player.getBlockPos();
        int dist = Math.abs(playerPos.getX() - target.pos.getX()) + Math.abs(playerPos.getZ() - target.pos.getZ());

        if (dist <= 2) {
            BaritoneState.groundWalking = false;
            try { BaritoneInterface.cancelAll(); } catch (Throwable ignored) {}

            nextJunctionIndex++;
            state = State.TURNING;
            walkTicks = 5;

            if (target.isCorner) {
                turnTarget = computeCornerTurnTarget(target);
            } else {
                turnTarget = computeJunctionTurnTarget(target);
            }
        } else {
            if (!BaritoneState.groundWalking && !BaritoneInterface.isActive()) {
                BaritoneState.groundWalking = true;
                BaritoneInterface.setGoal(target.pos);
            }
        }
    }

    private BlockPos computeCornerTurnTarget(JunctionPoint corner) {
        if (detectedHighway == null) return null;
        HighwayDetector.HighwayAxis currentAxis = detectedHighway.axis;

        int sdx = currentAxis.stepDx;
        int sdz = currentAxis.stepDz;
        int walkSign = isWalkingTowardOrigin ? -1 : 1;
        int wx = sdx * walkSign;
        int wz = sdz * walkSign;

        BlockPos cp = corner.pos;
        int cx = cp.getX();
        int cz = cp.getZ();

        int rightDx = wz;
        int rightDz = -wx;
        BlockPos rightTarget = new BlockPos(cx + rightDx * 5, cp.getY(), cz + rightDz * 5);

        int leftDx = -wz;
        int leftDz = wx;
        BlockPos leftTarget = new BlockPos(cx + leftDx * 5, cp.getY(), cz + leftDz * 5);

        boolean rightHasObby = hasObsidianFloor(rightTarget, detectedHighway.floorY);
        boolean leftHasObby = hasObsidianFloor(leftTarget, detectedHighway.floorY);

        if (rightHasObby && !leftHasObby) return rightTarget;
        if (leftHasObby && !rightHasObby) return leftTarget;
        return rightTarget;
    }

    private BlockPos computeJunctionTurnTarget(JunctionPoint junction) {
        if (detectedHighway == null) return null;
        BlockPos jp = junction.pos;
        int jx = jp.getX();
        int jz = jp.getZ();

        int perpDx = detectedHighway.axis.perpDx();
        int perpDz = detectedHighway.axis.perpDz();

        int targetDx, targetDz;
        switch (turnPolicy.get()) {
            case TURN_LEFT -> { targetDx = -perpDx; targetDz = -perpDz; }
            case TURN_RIGHT -> { targetDx = perpDx; targetDz = perpDz; }
            case ZIGZAG -> {
                if (zigzagLeft) { targetDx = -perpDx; targetDz = -perpDz; }
                else { targetDx = perpDx; targetDz = perpDz; }
                zigzagLeft = !zigzagLeft;
            }
            default -> { targetDx = perpDx; targetDz = perpDz; }
        }

        return new BlockPos(jx + targetDx * 5, jp.getY(), jz + targetDz * 5);
    }

    private boolean hasObsidianFloor(BlockPos pos, int floorY) {
        if (mc.world == null) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos check = new BlockPos(pos.getX() + dx, floorY, pos.getZ() + dz);
                Block block = mc.world.getBlockState(check).getBlock();
                if (block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN) return true;
            }
        }
        return false;
    }

    private void tickTurning() {
        if (walkTicks-- > 0) return;

        if (turnTarget == null) {
            ChatUtils.warning("HighwayGoonerV2: turn failed — no valid target. Continuing straight.");
            detectedHighway = preJunctionHighway;
            state = State.WALKING;
            startBaritoneWalk();
            return;
        }

        if (!BaritoneState.groundWalking && !BaritoneInterface.isActive()) {
            BaritoneState.groundWalking = true;
            BaritoneInterface.setGoal(turnTarget);
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int dist = Math.abs(playerPos.getX() - turnTarget.getX()) + Math.abs(playerPos.getZ() - turnTarget.getZ());

        if (dist <= 2 || (!BaritoneInterface.isActive() && mc.player.getVelocity().horizontalLength() < 0.05)) {
            BaritoneState.groundWalking = false;
            try { BaritoneInterface.cancelAll(); } catch (Throwable ignored) {}
            state = State.REALIGNING;
            walkTicks = 5;
        }

        turnAttempts++;
        if (turnAttempts > 200) {
            BaritoneState.groundWalking = false;
            try { BaritoneInterface.cancelAll(); } catch (Throwable ignored) {}
            ChatUtils.warning("HighwayGoonerV2: turn timeout — resuming original highway.");
            detectedHighway = preJunctionHighway;
            state = State.WALKING;
            startBaritoneWalk();
        }
    }

    private void tickRealigning() {
        if (walkTicks-- > 0) return;

        List<HighwayInfo> detected = HighwayDetector.detectAll(mc, 0.2f,
                detectRingRoads.get(), detectDiamondRoads.get());

        HighwayInfo newHighway = null;
        for (HighwayInfo info : detected) {
            if (preJunctionHighway != null && info.axis == preJunctionHighway.axis
                    && info.category == preJunctionHighway.category) continue;
            newHighway = info;
            break;
        }

        if (newHighway != null) {
            detectedHighway = newHighway;
            nearbyHighways = HighwayDetector.scanNearbyHighways(mc, detectedHighway, 100);
            stepsBuilt = 0;
            placedBlocks.clear();
            computeJunctionPoints();
            generateNextStepPlan();
            state = State.BUILDING;
            ChatUtils.info("HighwayGoonerV2: turned onto %s", detectedHighway);
        } else {
            realignAttempts++;
            if (realignAttempts >= 3) {
                ChatUtils.warning("HighwayGoonerV2: realign failed after 3 attempts — resuming original highway.");
                detectedHighway = preJunctionHighway;
                computeJunctionPoints();
                state = State.WALKING;
                startBaritoneWalk();
            } else {
                if (turnTarget != null && mc.player != null) {
                    int dx = Integer.signum(turnTarget.getX() - mc.player.getBlockPos().getX());
                    int dz = Integer.signum(turnTarget.getZ() - mc.player.getBlockPos().getZ());
                    BlockPos nudge = mc.player.getBlockPos().add(dx * 2, 0, dz * 2);
                    BaritoneState.groundWalking = true;
                    BaritoneInterface.setGoal(nudge);
                }
                walkTicks = 20;
            }
        }
    }

    // === Multi-block hotbar management ===

    private Predicate<ItemStack> clogBlockMatcher() {
        List<Block> blocks = clogBlocks.get();
        return stack -> {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
            return blocks.contains(bi.getBlock());
        };
    }

    private boolean ensureAnyClogBlock(boolean select) {
        if (mc.player == null) return false;

        Predicate<ItemStack> matcher = clogBlockMatcher();
        int pinned = pinnedHotbarIndex();

        ItemStack pinnedStack = mc.player.getInventory().getStack(pinned);
        if (!pinnedStack.isEmpty() && matcher.test(pinnedStack)) {
            return true;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                pinnedHotbarSlotRuntime = i;
                return true;
            }
        }

        // Not in hotbar — try pulling from inventory via HotbarSupply
        int slot = HotbarSupply.ensureHotbarStack(matcher, refillThreshold.get(), false);
        if (slot >= 0) {
            pinnedHotbarSlotRuntime = slot;
            return true;
        }

        return false;
    }

    // === Block clearing ===

    private boolean clearBlocksInReach() {
        if (mc.player == null || mc.world == null) return false;

        List<Block> breakList = clearBlocks.get();
        if (breakList.isEmpty()) return false;

        // Continue breaking current target if it still exists
        if (clearingBlockTarget != null) {
            BlockState bs = mc.world.getBlockState(clearingBlockTarget);
            if (!bs.isAir() && breakList.contains(bs.getBlock())) {
                mc.interactionManager.updateBlockBreakingProgress(clearingBlockTarget, Direction.UP);
                return true;
            }
            clearingBlockTarget = null;
        }

        // Scan pattern positions for whitelisted blocks
        for (PlacementEntry entry : currentStepPlan) {
            BlockState bs = mc.world.getBlockState(entry.pos);
            if (bs.isAir()) continue;
            if (breakList.contains(bs.getBlock())) {
                clearingBlockTarget = entry.pos;
                mc.interactionManager.attackBlock(entry.pos, Direction.UP);
                return true;
            }
        }

        // Scan player's immediate vicinity
        BlockPos playerPos = mc.player.getBlockPos();
        int floorY = detectedHighway != null ? detectedHighway.floorY : playerPos.getY() - 1;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos check = new BlockPos(playerPos.getX() + dx, floorY + dy, playerPos.getZ() + dz);
                    BlockState bs = mc.world.getBlockState(check);
                    if (bs.isAir()) continue;
                    if (breakList.contains(bs.getBlock())) {
                        clearingBlockTarget = check;
                        mc.interactionManager.attackBlock(check, Direction.UP);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // === Block placement ===

    private boolean placeBlock(BlockPos pos) {
        if (!ensureAnyClogBlock(true)) {
            return false;
        }

        boolean success = GoonerPlacement.wallGoonerPlace(pos, legitRotation.get(), pinnedHotbarIndex());
        if (success) {
            lastPlaceTime = System.currentTimeMillis();
            totalBlocksPlaced++;
            ItemStack held = mc.player.getInventory().getStack(pinnedHotbarIndex());
            if (held.getItem() instanceof BlockItem bi) {
                StatsHandler.recordPlacement(bi.getBlock());
            }
        }
        return success;
    }

    // === Hotbar pinning ===

    private int configuredHotbarIndex() {
        return Math.max(0, Math.min(8, placeHotbarSlot.get() - 1));
    }

    private int pinnedHotbarIndex() {
        if (pinnedHotbarSlotRuntime >= 0 && pinnedHotbarSlotRuntime <= 8) return pinnedHotbarSlotRuntime;
        return configuredHotbarIndex();
    }

    private void detectPinnedHotbarSlot() {
        pinnedHotbarSlotRuntime = -1;
        if (mc.player == null) return;

        Predicate<ItemStack> matcher = clogBlockMatcher();

        // Priority 1: clog block already in hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                pinnedHotbarSlotRuntime = i;
                return;
            }
        }

        // Priority 2: configured slot (if not protected)
        int configured = configuredHotbarIndex();
        if (!isProtectedSlot(configured)) {
            pinnedHotbarSlotRuntime = configured;
            return;
        }

        // Priority 3: find a safe slot
        pinnedHotbarSlotRuntime = findSafeHotbarSlot();
    }

    private boolean isProtectedSlot(int slot) {
        if (mc.player == null) return false;
        ItemStack stack = mc.player.getInventory().getStack(slot);
        if (stack.isEmpty()) return false;

        if (stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) return true;
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) return true;
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.AXES)) return true;
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.SHOVELS)) return true;

        Item item = stack.getItem();
        if (item == Items.TOTEM_OF_UNDYING) return true;
        if (item == Items.SHIELD) return true;
        if (item == Items.END_CRYSTAL) return true;
        if (item == Items.FIREWORK_ROCKET) return true;
        if (item == Items.GOLDEN_APPLE) return true;
        if (item == Items.ENCHANTED_GOLDEN_APPLE) return true;
        if (item == Items.CHORUS_FRUIT) return true;
        if (item == Items.ENDER_PEARL) return true;
        if (item == Items.BOW) return true;
        if (item == Items.CROSSBOW) return true;

        if (stack.get(net.minecraft.component.DataComponentTypes.FOOD) != null) return true;

        if (item == Items.EXPERIENCE_BOTTLE) return true;
        if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) return true;

        if (item instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) return true;

        Predicate<ItemStack> clogMatcher = clogBlockMatcher();
        if (clogMatcher.test(stack)) return false;

        if (item instanceof BlockItem) return false;

        return true;
    }

    private int findSafeHotbarSlot() {
        if (mc.player == null) return configuredHotbarIndex();

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        for (int i = 8; i >= 0; i--) {
            if (!isProtectedSlot(i)) return i;
        }

        return configuredHotbarIndex();
    }

    // === Opaque block swap ===

    private void swapOpaqueBlocksFromHotbar() {
        if (mc.player == null) return;
        Predicate<ItemStack> clogMatcher = clogBlockMatcher();

        for (int hotSlot = 0; hotSlot < 9; hotSlot++) {
            ItemStack hotStack = mc.player.getInventory().getStack(hotSlot);
            if (hotStack.isEmpty()) continue;
            if (!(hotStack.getItem() instanceof BlockItem bi)) continue;
            if (!OPAQUE_JUNK.contains(bi.getBlock())) continue;

            int clogInvSlot = -1;
            int emptyInvSlot = -1;
            for (int i = 9; i < mc.player.getInventory().size(); i++) {
                ItemStack invStack = mc.player.getInventory().getStack(i);
                if (invStack.isEmpty() && emptyInvSlot == -1) {
                    emptyInvSlot = i;
                } else if (!invStack.isEmpty() && clogMatcher.test(invStack) && clogInvSlot == -1) {
                    clogInvSlot = i;
                }
                if (clogInvSlot != -1) break;
            }

            int swapSlot = clogInvSlot != -1 ? clogInvSlot : emptyInvSlot;
            if (swapSlot == -1) continue;

            try {
                int screenSlot = swapSlot < 9 ? swapSlot + 36 : swapSlot;
                ToolManager.moveToHotbar(screenSlot, hotSlot);
            } catch (Throwable ignored) {}
        }
    }

    // === Hole patching ===

    private void patchDestinationHole() {
        if (!patchHolesBeforeWalk.get()) return;
        if (mc.player == null || mc.world == null || detectedHighway == null) return;

        // Walk destination is 1 step in walk direction
        int sdx = detectedHighway.axis.stepDx;
        int sdz = detectedHighway.axis.stepDz;
        int walkSign = isWalkingTowardOrigin ? -1 : 1;
        BlockPos dest = mc.player.getBlockPos().add(sdx * walkSign, 0, sdz * walkSign);
        BlockPos below = dest.down();

        Block belowBlock = mc.world.getBlockState(below).getBlock();
        if (HOLE_PATCH_IGNORE.contains(belowBlock)) return;
        if (!mc.world.getBlockState(below).isReplaceable()) return;

        if (!ensureAnyClogBlock(true)) return;

        GoonerPlacement.wallGoonerPlace(below, legitRotation.get(), pinnedHotbarIndex());
    }

    // === Wither helpers ===

    private void faceWitherBuildDirection() {
        if (mc.player == null || detectedHighway == null) return;

        int sdx = detectedHighway.axis.stepDx;
        int sdz = detectedHighway.axis.stepDz;
        int buildSign = isWalkingTowardOrigin ? 1 : -1;
        int bx = sdx * buildSign;
        int bz = sdz * buildSign;

        float yaw = (float) Math.toDegrees(Math.atan2(-bx, bz));
        mc.player.setYaw(yaw);
    }

    private boolean canAttemptWitherNow() {
        if (!enableAutoWither.get()) return false;
        boolean timerReady = blocksSinceWither >= witherEveryBlocks.get();
        if (!timerReady && !witherQueued) return false;

        if (hasClogBlockBuffer64()) {
            return true;
        }

        if (timerReady && !witherQueued) {
            witherQueued = true;
        }
        return false;
    }

    private boolean hasClogBlockBuffer64() {
        if (mc.player == null) return false;
        for (Block block : clogBlocks.get()) {
            if (ToolManager.countItem(block.asItem()) < 64) return false;
        }
        return true;
    }

    // === Meteor integration (yield) ===

    private boolean isMeteorBusy() {
        if (yieldToAutoEat.get()) {
            AutoEat autoEat = Modules.get().get(AutoEat.class);
            if (autoEat != null && autoEat.isActive() && autoEat.eating) return true;
        }
        if (yieldToAutoGap.get()) {
            AutoGap autoGap = Modules.get().get(AutoGap.class);
            if (autoGap != null && autoGap.isActive() && autoGap.isEating()) return true;
        }
        if (yieldToKillAura.get()) {
            KillAura killAura = Modules.get().get(KillAura.class);
            if (killAura != null && killAura.isActive() && killAura.attacking) return true;
        }
        return false;
    }

    // === Safety ===

    private boolean hasNearbyNonFriendPlayer() {
        if (mc.world == null || mc.player == null) return false;
        Friends friends = Friends.get();
        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other == mc.player) continue;
            if (!other.isAlive() || other.isSpectator()) continue;
            if (friends != null && friends.isFriend(other)) continue;
            return true;
        }
        return false;
    }

    // === Baritone availability ===

    private boolean ensureBaritoneAvailable(boolean logOnChange) {
        long now = System.currentTimeMillis();
        if (now - lastBaritoneCheckMs < BARITONE_RECHECK_MS) return baritoneAvailable;

        boolean prev = baritoneAvailable;
        baritoneAvailable = BaritoneInterface.isBaritoneAvailable();
        lastBaritoneCheckMs = now;

        if (logOnChange && baritoneAvailable != prev) {
            if (baritoneAvailable) {
                baritoneWarned = false;
                ChatUtils.info("HighwayGoonerV2: Baritone detected — auto-walk enabled.");
            } else {
                if (!baritoneWarned) {
                    baritoneWarned = true;
                    warning("Baritone not detected — auto-walk will do nothing.");
                }
            }
        }
        return baritoneAvailable;
    }

    public int getTotalBlocksPlaced() { return totalBlocksPlaced; }
}
