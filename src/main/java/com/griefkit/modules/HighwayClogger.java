package com.griefkit.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.griefkit.GriefKit;
import com.griefkit.helpers.HotbarSupply;
import com.griefkit.helpers.gooner.BaritoneInterface;
import com.griefkit.helpers.gooner.BaritoneState;
import com.griefkit.helpers.gooner.GoonerPlacement;
import com.griefkit.helpers.gooner.HighwayDetector;
import com.griefkit.helpers.gooner.SetbackMonitor;
import com.griefkit.helpers.gooner.HighwayDetector.HighwayAxis;
import com.griefkit.managers.ToolManager;
import com.griefkit.helpers.gooner.HighwayDetector.HighwayInfo;
import com.griefkit.helpers.gooner.StatsHandler;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BlockSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public class HighwayClogger extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final long BARITONE_RECHECK_MS = 3000L;

    // ── Setting Groups ─────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender    = settings.createGroup("Render");
    private final SettingGroup sgPattern1 = settings.createGroup("Pattern 1");
    private final SettingGroup sgPattern2 = settings.createGroup("Pattern 2");
    private final SettingGroup sgPattern3 = settings.createGroup("Pattern 3");
    private final SettingGroup sgPattern4 = settings.createGroup("Pattern 4");
    private final SettingGroup sgPattern5 = settings.createGroup("Pattern 5");
    private final SettingGroup sgPattern6 = settings.createGroup("Pattern 6");

    // ── General ────────────────────────────────────────────────────────────────
    private final Setting<Boolean> usePattern2 = sgGeneral.add(new BoolSetting.Builder()
        .name("use-pattern-2")
        .description("Include pattern 2 in the build cycle.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> usePattern3 = sgGeneral.add(new BoolSetting.Builder()
        .name("use-pattern-3")
        .description("Include pattern 3 in the build cycle.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> usePattern4 = sgGeneral.add(new BoolSetting.Builder()
        .name("use-pattern-4")
        .description("Include pattern 4 in the build cycle.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> usePattern5 = sgGeneral.add(new BoolSetting.Builder()
        .name("use-pattern-5")
        .description("Include pattern 5 in the build cycle.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> usePattern6 = sgGeneral.add(new BoolSetting.Builder()
        .name("use-pattern-6")
        .description("Include pattern 6 in the build cycle.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay-ms")
        .description("Milliseconds between individual block placements.")
        .defaultValue(30)
        .min(0)
        .sliderMax(500)
        .build());

    private final Setting<Integer> patternDelay = sgGeneral.add(new IntSetting.Builder()
        .name("pattern-delay-ticks")
        .description("Ticks to wait between pattern cycles.")
        .defaultValue(2)
        .min(1)
        .sliderMax(100)
        .build());

    private final Setting<Boolean> savePatterns = sgGeneral.add(new BoolSetting.Builder()
        .name("save-patterns")
        .description("Save and reload wall patterns from config/GriefKit/highway_clogger_patterns.json.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Use Baritone to walk backward one step after each completed pattern.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> patchHolesBeforeWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("patch-holes-before-walk")
        .description("Place a bridge block under the next walk destination when standing over a gap.")
        .defaultValue(true)
        .visible(autoWalk::get)
        .build());

    private final Setting<List<Block>> holePatchIgnoreBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("hole-patch-ignore-blocks")
        .description("Blocks already present below the walk destination that should not be replaced when patching holes.")
        .defaultValue(List.of(
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
            Blocks.WITHER_SKELETON_SKULL, Blocks.WITHER_SKELETON_WALL_SKULL))
        .visible(patchHolesBeforeWalk::get)
        .build());

    private final Setting<Boolean> enableAutoWither = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-wither")
        .description("Toggle GoonerWither every N walk steps along the highway.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> witherEveryBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("wither-every-blocks")
        .description("Number of walk steps between wither placements.")
        .defaultValue(50)
        .min(10)
        .max(150)
        .sliderMax(150)
        .visible(enableAutoWither::get)
        .build());

    private final Setting<Integer> autoWalkDelay = sgGeneral.add(new IntSetting.Builder()
        .name("auto-walk-delay-ms")
        .description("Extra milliseconds to wait after a pattern finishes before invoking Baritone.")
        .defaultValue(0)
        .min(0)
        .sliderMax(5000)
        .visible(autoWalk::get)
        .build());

    private final Setting<Integer> refillThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("refill-threshold")
        .description("Pull a fresh stack from inventory when the hotbar placement slot drops below this count.")
        .defaultValue(32)
        .min(1)
        .max(64)
        .sliderMax(64)
        .build());

    private final Setting<Integer> placeHotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("place-hotbar-slot")
        .description("Preferred hotbar slot (1–9) for block placement.")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderMax(9)
        .build());

    private final Setting<Boolean> suppressBaritoneChat = sgGeneral.add(new BoolSetting.Builder()
        .name("suppress-baritone-chat")
        .description("Suppress Baritone chat spam during auto-walk.")
        .defaultValue(true)
        .visible(autoWalk::get)
        .build());

    private final Setting<Boolean> diagonalBuilding = sgGeneral.add(new BoolSetting.Builder()
        .name("diagonal-building-override")
        .description("Force diagonal building even when highway detection says straight. Has no effect if detection is enabled and succeeds.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> autoMineEchests = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-mine-echests")
        .description("Automatically mine ender chests found in the highway lane or in a pattern cell.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> mineScanDistance = sgGeneral.add(new IntSetting.Builder()
        .name("mine-scan-distance")
        .description("Blocks ahead to scan for ender chests in the walk lane when auto-mine-echests is enabled.")
        .defaultValue(5)
        .min(1)
        .max(12)
        .sliderMax(12)
        .visible(autoMineEchests::get)
        .build());

    // ── Detection ─────────────────────────────────────────────────────────────
    private final Setting<Double> detectionConfidence = sgDetection.add(new DoubleSetting.Builder()
        .name("min-confidence")
        .description("Minimum detection confidence (0.1–1.0). Lower = more permissive.")
        .defaultValue(0.3)
        .range(0.1, 1.0)
        .sliderRange(0.1, 1.0)
        .build());

    private final Setting<Boolean> detectRingRoads = sgDetection.add(new BoolSetting.Builder()
        .name("detect-ring-roads")
        .description("Classify ring roads (axis-aligned squares at known distances from 0,0).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> detectDiamondRoads = sgDetection.add(new BoolSetting.Builder()
        .name("detect-diamond-roads")
        .description("Classify diamond roads (45° rotated squares at known |x|+|z| distances).")
        .defaultValue(true)
        .build());

    private final Setting<Keybind> rescanKey = sgDetection.add(new KeybindSetting.Builder()
        .name("rescan-key")
        .description("Press to re-detect the highway at your current position without disabling the module.")
        .defaultValue(Keybind.none())
        .build());

    private final Setting<Boolean> legitRotation = sgGeneral.add(new BoolSetting.Builder()
        .name("legit-rotation")
        .description("Rotate client-side to face each placement target before sending the packet.")
        .defaultValue(false)
        .build());

    // ── Render ─────────────────────────────────────────────────────────────────
    private final Setting<Boolean> renderOverlay = sgRender.add(new BoolSetting.Builder()
        .name("render-overlay")
        .description("Show a 3-D box overlay of the current wall pattern.")
        .defaultValue(true)
        .build());

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("RedGreen: placed = green, pending = red.  Custom: use the colors below.")
        .defaultValue(RenderMode.RedGreen)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 255, 255, 50))
        .visible(() -> renderMode.get() == RenderMode.Custom)
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> renderMode.get() == RenderMode.Custom)
        .build());

    // ── Per-pattern block types ─────────────────────────────────────────────
    private final Setting<Block> pattern1BlockType = sgPattern1.add(new BlockSetting.Builder()
        .name("block-type")
        .description("Block placed for pattern 1.")
        .defaultValue(Blocks.OBSIDIAN)
        .build());

    private final Setting<Block> pattern2BlockType = sgPattern2.add(new BlockSetting.Builder()
        .name("block-type")
        .description("Block placed for pattern 2.")
        .defaultValue(Blocks.CRYING_OBSIDIAN)
        .build());

    private final Setting<Block> pattern3BlockType = sgPattern3.add(new BlockSetting.Builder()
        .name("block-type")
        .description("Block placed for pattern 3.")
        .defaultValue(Blocks.OBSIDIAN)
        .build());

    private final Setting<Block> pattern4BlockType = sgPattern4.add(new BlockSetting.Builder()
        .name("block-type")
        .description("Block placed for pattern 4.")
        .defaultValue(Blocks.CRYING_OBSIDIAN)
        .build());

    private final Setting<Block> pattern5BlockType = sgPattern5.add(new BlockSetting.Builder()
        .name("block-type")
        .description("Block placed for pattern 5.")
        .defaultValue(Blocks.OBSIDIAN)
        .build());

    private final Setting<Block> pattern6BlockType = sgPattern6.add(new BlockSetting.Builder()
        .name("block-type")
        .description("Block placed for pattern 6.")
        .defaultValue(Blocks.CRYING_OBSIDIAN)
        .build());

    // ── Enums ──────────────────────────────────────────────────────────────────
    private enum RenderMode { RedGreen, Custom }

    private enum State {
        BUILDING, PATTERN_COMPLETE_DELAY, WALKING, WITHERING, WAITING
    }

    // ── 5×7 grid patterns [row 0..4][col 0..6] ─────────────────────────────────
    private boolean[][] pattern1 = new boolean[5][7];
    private boolean[][] pattern2 = new boolean[5][7];
    private boolean[][] pattern3 = new boolean[5][7];
    private boolean[][] pattern4 = new boolean[5][7];
    private boolean[][] pattern5 = new boolean[5][7];
    private boolean[][] pattern6 = new boolean[5][7];

    // ── Runtime state ──────────────────────────────────────────────────────────
    private int     currentPatternIndex = 1;
    private int     buildStep           = 0;
    private State   state               = State.BUILDING;
    private long    lastPlaceTime       = 0;
    private long    patternCompleteTime = 0;
    private int     walkTicks           = 0;
    private final List<BlockPos> placedBlocks = new ArrayList<>();
    private Direction initialFacing;

    // ── Detection state ────────────────────────────────────────────────────────
    private HighwayInfo detectedHighway  = null;
    private HighwayAxis detectedAxis     = null;
    private boolean     detectedDiagonal = false;

    private int totalBlocksPlaced       = 0;
    private int blocksSinceWither       = 0;
    private int lastWitherWarnAt        = 0;

    // Delayed Baritone step — 1-tick wait after a hole-patch block is placed
    private boolean   pendingBaritone     = false;
    private int       pendingBaritoneTicks = 0;
    private Direction pendingFacing;
    private boolean   pendingDiagonal;
    private int       pendingBlocks;

    // Hotbar
    private int pinnedHotbarSlotRuntime = -1;

    // Echest mining state (auto-mine-echests)
    private BlockPos miningEchestPos = null;

    // Baritone availability (throttled)
    private boolean baritoneAvailable  = false;
    private boolean baritoneWarned     = false;
    private long    lastBaritoneCheckMs = 0L;

    // ── JSON pattern persistence ───────────────────────────────────────────────
    private static final Gson   GSON         = new GsonBuilder().setPrettyPrinting().create();
    private static final String PATTERN_FILE = "highway_clogger_patterns.json";
    private static boolean patternsLoadedOnce = false;

    private static class PatternConfig {
        public boolean[][] pattern1;
        public boolean[][] pattern2;
        public boolean[][] pattern3;
        public boolean[][] pattern4;
        public boolean[][] pattern5;
        public boolean[][] pattern6;
    }

    // ── Constructor ────────────────────────────────────────────────────────────
    public HighwayClogger() {
        super(GriefKit.CATEGORY, "highway-clogger",
            "User-configurable 5×7 wall builder. Draw up to 6 patterns, choose block types per pattern, " +
            "auto-walk with Baritone, and optionally place withers every N steps.");

        for (int i = 0; i < 5; i++) {
            Arrays.fill(pattern1[i], false);
            Arrays.fill(pattern2[i], false);
            Arrays.fill(pattern3[i], false);
            Arrays.fill(pattern4[i], false);
            Arrays.fill(pattern5[i], false);
            Arrays.fill(pattern6[i], false);
        }

        loadPatternsFromFile();
    }

    // ── GUI: per-pattern checkbox grids ───────────────────────────────────────
    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        for (int n = 1; n <= 6; n++) {
            list.add(theme.label("Pattern " + n + (isPatternEnabled(n) ? "" : " (disabled)"))).expandX();
            WTable table = theme.table();
            list.add(table);

            boolean[][] pat = getPatternByIndex(n);
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 7; col++) {
                    final int r = row, c = col;
                    var box = table.add(theme.checkbox(pat[r][c])).widget();
                    box.action = () -> pat[r][c] = box.checked;
                }
                table.row();
            }
        }

        return list;
    }

    // ── Pattern persistence ────────────────────────────────────────────────────
    private File getPatternFile() {
        File dir = new File(MinecraftClient.getInstance().runDirectory, "config/GriefKit");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return new File(dir, PATTERN_FILE);
    }

    private void loadPatternsFromFile() {
        if (patternsLoadedOnce) return;
        patternsLoadedOnce = true;
        if (!savePatterns.get()) return;

        File f = getPatternFile();
        if (!f.exists()) return;

        try (FileReader r = new FileReader(f)) {
            PatternConfig cfg = GSON.fromJson(r, PatternConfig.class);
            if (cfg == null) return;
            if (valid(cfg.pattern1)) pattern1 = cfg.pattern1;
            if (valid(cfg.pattern2)) pattern2 = cfg.pattern2;
            if (valid(cfg.pattern3)) pattern3 = cfg.pattern3;
            if (valid(cfg.pattern4)) pattern4 = cfg.pattern4;
            if (valid(cfg.pattern5)) pattern5 = cfg.pattern5;
            if (valid(cfg.pattern6)) pattern6 = cfg.pattern6;
            info("Highway-Clogger patterns loaded.");
        } catch (Exception e) {
            error("Failed to load patterns: " + e.getMessage());
        }
    }

    private void savePatternsToFile() {
        if (!savePatterns.get()) return;
        PatternConfig cfg = new PatternConfig();
        cfg.pattern1 = pattern1; cfg.pattern2 = pattern2;
        cfg.pattern3 = pattern3; cfg.pattern4 = pattern4;
        cfg.pattern5 = pattern5; cfg.pattern6 = pattern6;
        try (FileWriter w = new FileWriter(getPatternFile())) {
            GSON.toJson(cfg, w);
            info("Highway-Clogger patterns saved.");
        } catch (Exception e) {
            error("Failed to save patterns: " + e.getMessage());
        }
    }

    private static boolean valid(boolean[][] p) {
        return p != null && p.length == 5 && p[0].length == 7;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            warning("Player/world not loaded — cannot activate.");
            toggle();
            return;
        }

        StatsHandler.reset();

        buildStep           = 0;
        currentPatternIndex = ensurePatternIndex(1);
        walkTicks           = 0;
        state               = State.BUILDING;
        placedBlocks.clear();
        detectedHighway     = null;
        detectedAxis        = null;
        detectedDiagonal    = false;
        totalBlocksPlaced   = 0;
        blocksSinceWither   = 0;
        lastWitherWarnAt    = 0;
        pendingBaritone     = false;
        lastPlaceTime       = 0;
        patternCompleteTime = 0;

        runDetection(true);
        detectPinnedHotbarSlot();
        savePatternsToFile();

        baritoneAvailable   = BaritoneInterface.isBaritoneAvailable();
        lastBaritoneCheckMs = System.currentTimeMillis();
        baritoneWarned      = false;

        if (suppressBaritoneChat.get()) {
            try {
                BaritoneInterface.setSetting("chatControl", false);
                BaritoneInterface.setSetting("chatDebug", false);
            } catch (Throwable ignored) {}
        }

        if (baritoneAvailable) {
            info("Baritone detected — auto-walk ready.");
        } else if (autoWalk.get()) {
            warning("Baritone not found — auto-walk will be skipped. Install Baritone or disable auto-walk.");
            baritoneWarned = true;
        }
    }

    // ── Highway detection ──────────────────────────────────────────────────────
    private void runDetection(boolean quietOnFailure) {
        if (mc.player == null || mc.world == null) return;

        detectedHighway = HighwayDetector.detect(mc, detectionConfidence.get().floatValue(),
                detectRingRoads.get(), detectDiamondRoads.get());

        if (detectedHighway != null) {
            initialFacing    = detectedHighway.facingDirection;
            detectedAxis     = detectedHighway.axis;
            detectedDiagonal = detectedHighway.diagonal;
            ChatUtils.info("HighwayClogger: detected %s", detectedHighway);
        } else {
            // Fall back to player facing + manual override
            initialFacing    = mc.player.getHorizontalFacing();
            detectedAxis     = null;
            detectedDiagonal = false;
            if (!quietOnFailure) {
                ChatUtils.warning("HighwayClogger: no highway detected (confidence below %.0f%%). Using player facing as fallback.",
                        detectionConfidence.get() * 100);
            } else {
                warning("No highway detected — falling back to player facing. Check Detection settings or reposition.");
            }
        }
    }

    private boolean isDetectedDiagonal() {
        return detectedDiagonal || diagonalBuilding.get();
    }

    private void scanAndMineEchestsAhead() {
        if (mc.player == null || mc.world == null) return;

        BlockPos playerPos = mc.player.getBlockPos();
        int fwdDx, fwdDz, perpDx, perpDz;
        if (detectedAxis != null) {
            fwdDx  = detectedAxis.stepDx;
            fwdDz  = detectedAxis.stepDz;
            perpDx = detectedAxis.perpDx();
            perpDz = detectedAxis.perpDz();
        } else {
            Direction f = initialFacing != null ? initialFacing : mc.player.getHorizontalFacing();
            fwdDx  = f.getOffsetX();
            fwdDz  = f.getOffsetZ();
            perpDx = f.rotateYClockwise().getOffsetX();
            perpDz = f.rotateYClockwise().getOffsetZ();
        }

        int halfWidth = detectedHighway != null ? (detectedHighway.width / 2) : 2;

        for (int dist = 1; dist <= mineScanDistance.get(); dist++) {
            for (int side = -halfWidth; side <= halfWidth; side++) {
                int bx = playerPos.getX() + fwdDx * dist + perpDx * side;
                int bz = playerPos.getZ() + fwdDz * dist + perpDz * side;
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos check = new BlockPos(bx, playerPos.getY() + dy, bz);
                    if (mc.player.getEyePos().distanceTo(check.toCenterPos()) > 5.5) continue;
                    if (mc.world.getBlockState(check).getBlock() == Blocks.ENDER_CHEST) {
                        mineEchest(check);
                        return; // one block at a time
                    }
                }
            }
        }
    }

    private void mineEchest(BlockPos pos) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // Block already gone — clear state
        if (mc.world.getBlockState(pos).getBlock() != Blocks.ENDER_CHEST) {
            if (pos.equals(miningEchestPos)) miningEchestPos = null;
            return;
        }

        // Select best tool and start/continue breaking
        ToolManager.findBestPickaxeSlot(pos);
        if (!pos.equals(miningEchestPos)) {
            miningEchestPos = pos;
            mc.interactionManager.attackBlock(pos, Direction.UP);
        } else {
            mc.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
        }
    }

    @Override
    public void onDeactivate() {
        placedBlocks.clear();
        BaritoneState.groundWalking = false;
        if (ensureBaritoneAvailable(false)) {
            try { BaritoneInterface.cancelAll(); } catch (Throwable ignored) {}
        }
        BaritoneState.pathing = false;
        BaritoneState.needsPathingCheck = false;
        SetbackMonitor.get().reset();
        miningEchestPos = null;
    }

    // ── Tick (main state machine) ──────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        SetbackMonitor.get().tick(mc);

        if (rescanKey.get().isPressed()) runDetection(false);

        StatsHandler.onTick();

        if (autoMineEchests.get()) scanAndMineEchestsAhead();

        switch (state) {
            case BUILDING               -> build();
            case PATTERN_COMPLETE_DELAY -> handlePatternCompleteDelay();
            case WALKING                -> walk();
            case WITHERING              -> witherState();
            case WAITING                -> waitState();
        }
    }

    // ── 3-D render overlay ────────────────────────────────────────────────────
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderOverlay.get() || mc.player == null) return;

        Direction facing = initialFacing != null ? initialFacing : mc.player.getHorizontalFacing();
        // Cardinal fallback: build toward origin = opposite of facing direction
        BlockPos  start  = mc.player.getBlockPos().offset(facing.getOpposite(), 2);
        currentPatternIndex = ensurePatternIndex(currentPatternIndex);

        // Index 7 is the AutoWither pseudo-step — nothing to render.
        if (currentPatternIndex == 7) return;

        boolean[][] pat = getPatternByIndex(currentPatternIndex);
        BlockPos playerPos = mc.player.getBlockPos();

        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 7; j++) {
                if (!pat[4 - i][j]) continue;

                BlockPos pos = computeBlockPos(playerPos, start, facing, i, j);

                SettingColor side, line;
                if (renderMode.get() == RenderMode.RedGreen) {
                    boolean placed = placedBlocks.contains(pos);
                    side = new SettingColor(placed ? 0 : 255, placed ? 255 : 0, 0, 50);
                    line = new SettingColor(placed ? 0 : 255, placed ? 255 : 0, 0, 255);
                } else {
                    side = sideColor.get();
                    line = lineColor.get();
                }

                event.renderer.box(pos, side, line, shapeMode.get(), 0);
            }
        }
    }

    // ── Build state ───────────────────────────────────────────────────────────
    private void build() {
        // Pause during and immediately after a Grim server-issued setback (teleport-back).
        // Sending placement packets while position-corrected causes further flags.
        if (!SetbackMonitor.get().isCalm() || SetbackMonitor.get().recentSetbackCount(40) > 0) return;

        if (System.currentTimeMillis() - lastPlaceTime < placeDelay.get()) return;

        Direction facing = initialFacing != null ? initialFacing : mc.player.getHorizontalFacing();
        BlockPos  start  = mc.player.getBlockPos().offset(facing, 2);
        currentPatternIndex = ensurePatternIndex(currentPatternIndex);

        // Index 7 = AutoWither pseudo-step
        if (currentPatternIndex == 7) {
            if (canAttemptWitherNow()) {
                faceInitialFacingForWither();
                GoonerWither wither = Modules.get().get(GoonerWither.class);
                if (wither != null && !wither.isActive()) wither.toggle();
                blocksSinceWither = 0;
            }
            patternCompleteTime = System.currentTimeMillis();
            state = State.PATTERN_COMPLETE_DELAY;
            return;
        }

        boolean[][] pat     = getPatternByIndex(currentPatternIndex);
        final int   required = countTrue(pat);

        if (required == 0) {
            patternCompleteTime = System.currentTimeMillis();
            state = State.PATTERN_COMPLETE_DELAY;
            return;
        }

        BlockPos playerPos = mc.player.getBlockPos();

        for (; buildStep < 35 && placedBlocks.size() < required; buildStep++) {
            int col = buildStep % 7;
            int row = buildStep / 7;
            if (!pat[4 - row][col]) continue;

            BlockPos pos       = computeBlockPos(playerPos, start, facing, row, col);
            Block    needBlock = getBlockForCurrentPattern();

            if (!ensurePinnedHotbarBlock(needBlock, true)) {
                // If we genuinely have no blocks anywhere, stop. Otherwise just wait a tick
                // for a pending InvUtils.move() to complete before retrying placement.
                if (HotbarSupply.getTotalCount(needBlock) <= 0) {
                    error("Out of " + needBlock.getName().getString() + " — disabling.");
                    toggle();
                }
                return;
            }

            if (!mc.world.getBlockState(pos).isReplaceable()) {
                // If auto-mine is on and it's an ender chest, break it with interactionManager.
                if (autoMineEchests.get() && mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
                    mineEchest(pos);
                    return; // wait for it to break; step index stays, retry next tick
                }
                if (!placedBlocks.contains(pos)) placedBlocks.add(pos);
                if (placedBlocks.size() >= required) break;
                continue;
            }

            if (placeBlock(pos)) {
                if (!placedBlocks.contains(pos)) placedBlocks.add(pos);
                lastPlaceTime = System.currentTimeMillis();
                buildStep++;
                return; // one placement per tick
            } else {
                return; // placement failed; retry next tick
            }
        }

        if (placedBlocks.size() >= required || buildStep >= 35) {
            patternCompleteTime = System.currentTimeMillis();
            state = State.PATTERN_COMPLETE_DELAY;
        }
    }

    // ── Pattern-complete delay / walk trigger ─────────────────────────────────
    private void handlePatternCompleteDelay() {
        // If we planted a hole-patch block, wait 1 tick before calling Baritone
        if (pendingBaritone) {
            if (pendingBaritoneTicks-- > 0) return;
            pendingBaritone = false;

            if (!autoWalk.get() || !ensureBaritoneAvailable(true)) {
                state     = State.WAITING;
                walkTicks = patternDelay.get();
                return;
            }
            try {
                if (!BaritoneInterface.isActive()) {
                    doStepRelative(pendingFacing, pendingDiagonal, pendingBlocks);
                }
                state     = State.WALKING;
                walkTicks = patternDelay.get();
            } catch (Throwable t) {
                warning("Baritone walk failed: %s", t.getMessage());
                state     = State.WAITING;
                walkTicks = patternDelay.get();
            }
            return;
        }

        if (System.currentTimeMillis() - patternCompleteTime < autoWalkDelay.get()) return;

        if (autoWalk.get() && ensureBaritoneAvailable(true)) {
            startBaritoneWalk();
            state = pendingBaritone ? State.PATTERN_COMPLETE_DELAY : State.WALKING;
        } else {
            state = State.WAITING;
        }
        walkTicks = patternDelay.get();
    }

    private void startBaritoneWalk() {
        if (!ensureBaritoneAvailable(false) || mc.player == null) return;

        final Direction facing   = initialFacing != null ? initialFacing : mc.player.getHorizontalFacing();
        final boolean   diagonal = isDetectedDiagonal();
        final int       blocks   = 1;

        int patchResult = patchDestinationHole(facing, diagonal, blocks);
        if (patchResult < 0) return; // cannot patch — skip walk

        if (patchResult == 1) {
            // Patch placed — wait 1 tick before walking
            pendingBaritone      = true;
            pendingBaritoneTicks = 1;
            pendingFacing        = facing;
            pendingDiagonal      = diagonal;
            pendingBlocks        = blocks;
            return;
        }

        if (!BaritoneInterface.isActive()) {
            doStepRelative(facing, diagonal, blocks);
        }
    }

    private void doStepRelative(Direction facing, boolean diagonal, int blocks) {
        if (mc.player == null || blocks <= 0) return;

        int dx, dz;
        if (detectedAxis != null) {
            // stepDx/stepDz point AWAY from origin — walk away from origin (+step).
            dx = detectedAxis.stepDx * blocks;
            dz = detectedAxis.stepDz * blocks;
        } else {
            // Cardinal fallback: walk in facing direction (away from origin).
            dx = facing.getOffsetX() * blocks;
            dz = facing.getOffsetZ() * blocks;
            if (diagonal) {
                Direction left = facing.rotateYCounterclockwise();
                dx += left.getOffsetX() * blocks;
                dz += left.getOffsetZ() * blocks;
            }
        }

        BlockPos target = mc.player.getBlockPos().add(dx, 0, dz);
        BaritoneState.groundWalking = true;
        BaritoneInterface.setGoal(target);
    }

    // ── Walk state ─────────────────────────────────────────────────────────────
    private void walk() {
        if (walkTicks-- > 0) return;

        if (!ensureBaritoneAvailable(false)) {
            state     = State.WAITING;
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

        state     = State.WAITING;
        walkTicks = patternDelay.get();
    }

    // ── Wither state ───────────────────────────────────────────────────────────
    private void witherState() {
        if (walkTicks-- > 0) return;

        GoonerWither wither = Modules.get().get(GoonerWither.class);
        if (wither == null) {
            state     = State.WAITING;
            walkTicks = patternDelay.get();
            return;
        }

        if (!wither.isActive()) {
            state     = State.WAITING;
            walkTicks = patternDelay.get();
        } else {
            walkTicks = 2;
        }
    }

    // ── Wait state ─────────────────────────────────────────────────────────────
    private void waitState() {
        if (walkTicks-- > 0) return;

        buildStep = 0;
        placedBlocks.clear();

        currentPatternIndex = getNextPatternIndex(currentPatternIndex);

        // Pre-fetch the next pattern's block into the pinned slot while idle
        if (currentPatternIndex >= 1 && currentPatternIndex <= 6) {
            ensurePinnedHotbarBlock(getBlockForCurrentPattern(), false);
        }

        state = State.BUILDING;
    }

    // ── Block position helper ─────────────────────────────────────────────────
    private BlockPos computeBlockPos(BlockPos playerPos, BlockPos start, Direction facing, int row, int col) {
        if (detectedAxis != null) {
            // stepDx/stepDz point AWAY from origin.
            // Build TOWARD origin: base = player - step*2.
            BlockPos base = playerPos.add(-detectedAxis.stepDx * 2, 0, -detectedAxis.stepDz * 2);
            return base.add(detectedAxis.perpDx() * (col - 3), row, detectedAxis.perpDz() * (col - 3));
        }
        // Fallback: no detected axis — use cardinal facing (start already offset opposite to facing).
        return start.add(
            facing.rotateYClockwise().getOffsetX() * (col - 3),
            row,
            facing.rotateYClockwise().getOffsetZ() * (col - 3)
        );
    }

    // ── Pattern index helpers ─────────────────────────────────────────────────
    private int[] getEnabledPatternOrder() {
        List<Integer> order = new ArrayList<>(8);
        order.add(1);
        if (usePattern2.get()) order.add(2);
        if (usePattern3.get()) order.add(3);
        if (usePattern4.get()) order.add(4);
        if (usePattern5.get()) order.add(5);
        if (usePattern6.get()) order.add(6);
        if (canAttemptWitherNow()) order.add(7);
        int[] out = new int[order.size()];
        for (int i = 0; i < order.size(); i++) out[i] = order.get(i);
        return out;
    }

    private boolean isPatternEnabled(int index) {
        return switch (index) {
            case 1 -> true;
            case 2 -> usePattern2.get();
            case 3 -> usePattern3.get();
            case 4 -> usePattern4.get();
            case 5 -> usePattern5.get();
            case 6 -> usePattern6.get();
            case 7 -> canAttemptWitherNow();
            default -> false;
        };
    }

    private int ensurePatternIndex(int index) {
        int[] order = getEnabledPatternOrder();
        if (order.length == 0) return 1;
        for (int v : order) if (v == index) return index;
        return order[0];
    }

    private int getNextPatternIndex(int index) {
        int[] order = getEnabledPatternOrder();
        if (order.length == 0) return 1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == index) return order[(i + 1) % order.length];
        }
        return order[0];
    }

    private boolean[][] getPatternByIndex(int index) {
        return switch (index) {
            case 2 -> pattern2;
            case 3 -> pattern3;
            case 4 -> pattern4;
            case 5 -> pattern5;
            case 6 -> pattern6;
            default -> pattern1;
        };
    }

    private Block getBlockForCurrentPattern() {
        return switch (ensurePatternIndex(currentPatternIndex)) {
            case 2 -> pattern2BlockType.get();
            case 3 -> pattern3BlockType.get();
            case 4 -> pattern4BlockType.get();
            case 5 -> pattern5BlockType.get();
            case 6 -> pattern6BlockType.get();
            default -> pattern1BlockType.get();
        };
    }

    private static int countTrue(boolean[][] pat) {
        int c = 0;
        for (boolean[] row : pat) for (boolean v : row) if (v) c++;
        return c;
    }

    // ── Hotbar management ─────────────────────────────────────────────────────
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
        Block target = pattern1BlockType.get();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi && bi.getBlock() == target) {
                pinnedHotbarSlotRuntime = i;
                return;
            }
        }
        pinnedHotbarSlotRuntime = configuredHotbarIndex();
    }

    private boolean ensurePinnedHotbarBlock(Block block, boolean select) {
        if (mc.player == null) return false;
        int pinned = pinnedHotbarIndex();

        // Already correct — just maybe switch selection
        Predicate<ItemStack> matcher = HotbarSupply.blockIs(block);
        if (matcher.test(mc.player.getInventory().getStack(pinned))) {
            if (select) InvUtils.swap(pinned, false);
            return true;
        }

        // Pull a fresh stack into the hotbar via HotbarSupply
        int slot = HotbarSupply.ensureHotbarStack(matcher, refillThreshold.get(), false);
        if (slot < 0) return false;

        // Verify the slot actually reflects the correct block before we trust it.
        // InvUtils.move() queues a slot-click; if the move hasn't been applied to the
        // client inventory yet this tick, the slot still holds the wrong item and we
        // must not pass that slot to wallGoonerPlace (it would place the wrong block).
        if (!matcher.test(mc.player.getInventory().getStack(slot))) return false;

        pinnedHotbarSlotRuntime = slot;
        if (select) InvUtils.swap(slot, false);
        return true;
    }

    // ── Block placement ───────────────────────────────────────────────────────
    private boolean placeBlock(BlockPos pos) {
        boolean success = GoonerPlacement.wallGoonerPlace(pos, legitRotation.get(), pinnedHotbarIndex());
        if (success) {
            lastPlaceTime = System.currentTimeMillis();
            totalBlocksPlaced++;
            StatsHandler.recordPlacement(getBlockForCurrentPattern());
            maybeWarnWitherSuppressed();
        }
        return success;
    }

    // ── Hole patching ─────────────────────────────────────────────────────────
    private int patchDestinationHole(Direction facing, boolean diagonal, int blocks) {
        if (!patchHolesBeforeWalk.get()) return 0;
        if (mc.player == null || mc.world == null) return -1;

        int dx, dz;
        if (detectedAxis != null) {
            // Walk away from origin (+step) — same as doStepRelative.
            dx = detectedAxis.stepDx * blocks;
            dz = detectedAxis.stepDz * blocks;
        } else {
            dx = facing.getOffsetX() * blocks;
            dz = facing.getOffsetZ() * blocks;
            if (diagonal) {
                Direction left = facing.rotateYCounterclockwise();
                dx += left.getOffsetX() * blocks;
                dz += left.getOffsetZ() * blocks;
            }
        }

        BlockPos dest  = mc.player.getBlockPos().add(dx, 0, dz);
        BlockPos below = dest.down();

        Block belowBlock = mc.world.getBlockState(below).getBlock();
        if (holePatchIgnoreBlocks.get().contains(belowBlock)) return 0;
        if (!mc.world.getBlockState(below).isReplaceable())   return 0;

        // Use current pattern block for patching; fall back to pattern 1 if on wither step
        Block patchBlock = (currentPatternIndex >= 1 && currentPatternIndex <= 6)
            ? getBlockForCurrentPattern()
            : pattern1BlockType.get();

        if (!ensurePinnedHotbarBlock(patchBlock, true)) return -1;

        boolean ok = GoonerPlacement.wallGoonerPlace(below, legitRotation.get(), pinnedHotbarIndex());
        return ok ? 1 : -1;
    }

    // ── AutoWither helpers ────────────────────────────────────────────────────
    private void faceInitialFacingForWither() {
        if (mc.player == null) return;
        float yaw;
        if (detectedAxis != null) {
            yaw = detectedAxis.expectedYaw();
        } else {
            Direction f = initialFacing != null ? initialFacing : mc.player.getHorizontalFacing();
            yaw = switch (f) {
                case SOUTH ->  0f;
                case WEST  ->  90f;
                case NORTH ->  180f;
                case EAST  -> -90f;
                default    -> mc.player.getYaw();
            };
        }
        mc.player.setYaw(yaw);
    }

    private boolean canAttemptWitherNow() {
        return enableAutoWither.get()
            && blocksSinceWither >= witherEveryBlocks.get()
            && hasPatternBlockBuffer64();
    }

    private boolean hasPatternBlockBuffer64() {
        if (mc.player == null) return false;
        List<Block> toCheck = new ArrayList<>();
        toCheck.add(pattern1BlockType.get());
        if (usePattern2.get()) addUnique(toCheck, pattern2BlockType.get());
        if (usePattern3.get()) addUnique(toCheck, pattern3BlockType.get());
        if (usePattern4.get()) addUnique(toCheck, pattern4BlockType.get());
        if (usePattern5.get()) addUnique(toCheck, pattern5BlockType.get());
        if (usePattern6.get()) addUnique(toCheck, pattern6BlockType.get());
        for (Block b : toCheck) {
            if (HotbarSupply.getTotalCount(b) < 64) return false;
        }
        return true;
    }

    private static void addUnique(List<Block> list, Block b) {
        if (b != null && !list.contains(b)) list.add(b);
    }

    private void maybeWarnWitherSuppressed() {
        if (!enableAutoWither.get()) return;
        if (blocksSinceWither < witherEveryBlocks.get()) return;
        if (hasPatternBlockBuffer64()) { lastWitherWarnAt = totalBlocksPlaced; return; }
        if (totalBlocksPlaced - lastWitherWarnAt >= 32) {
            warning("AutoWither suppressed — need 64+ of every enabled pattern block before placing a wither.");
            lastWitherWarnAt = totalBlocksPlaced;
        }
    }

    // ── Baritone availability (throttled check) ────────────────────────────────
    private boolean ensureBaritoneAvailable(boolean logOnChange) {
        long now = System.currentTimeMillis();
        if (now - lastBaritoneCheckMs < BARITONE_RECHECK_MS) return baritoneAvailable;

        boolean prev      = baritoneAvailable;
        baritoneAvailable = BaritoneInterface.isBaritoneAvailable();
        lastBaritoneCheckMs = now;

        if (logOnChange && baritoneAvailable != prev) {
            if (baritoneAvailable) {
                baritoneWarned = false;
                info("Baritone detected — auto-walk enabled.");
            } else if (!baritoneWarned) {
                baritoneWarned = true;
                warning("Baritone not detected — auto-walk will be skipped.");
            }
        }
        return baritoneAvailable;
    }
}
