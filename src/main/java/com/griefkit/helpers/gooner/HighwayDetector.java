package com.griefkit.helpers.gooner;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Smart highway detection using 3-signal triangulation.
 * Detects axis, width, guardrails, and walk direction from player position + surroundings.
 */
public class HighwayDetector {

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // Highway floor blocks — ONLY obsidian and crying obsidian.
    // Everything else (netherrack, stone, etc.) is terrain noise.
    private static final Set<Block> HIGHWAY_FLOOR_BLOCKS = Set.of(
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN
    );

    // Blocks that count as "air" for highway detection (traversable)
    private static final Set<Block> AIR_LIKE = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.FIRE, Blocks.SOUL_FIRE
    );

    // ======================== HIGHWAY TYPE CLASSIFICATION ========================

    /** What kind of highway this is. */
    public enum HighwayCategory {
        CARDINAL,   // ±X or ±Z through origin
        DIAGONAL,   // ±X±Z (intercardinal) through origin
        RING,       // side of an axis-aligned square at known distance from origin
        DIAMOND     // side of a 45°-rotated square at known |x|+|z| distance
    }

    /** Which side of a ring road square the player is on. */
    public enum RingSide {
        NORTH, // z = -D, runs along X
        SOUTH, // z = +D, runs along X
        EAST,  // x = +D, runs along Z
        WEST   // x = -D, runs along Z
    }

    /** Which side of a diamond road square the player is on. */
    public enum DiamondSegment {
        NE, // x>0, z>0, x+z=D, step along DIAG_PX_MZ / DIAG_MX_PZ
        NW, // x<0, z>0, -x+z=D, step along DIAG_MX_MZ / DIAG_PX_PZ
        SW, // x<0, z<0, x+z=-D
        SE  // x>0, z<0, -x+z=-D
    }

    // ======================== KNOWN HIGHWAY DISTANCES ========================

    /** Ring road distances from origin (axis-aligned squares). */
    public static final double[] RING_DISTANCES = {
            500, 1000, 1500, 2000, 2500, 7500.5,
            55000, 62500, 100000, 125000, 250000, 500000,
            750000, 1000000, 1250000, 1875000, 2500000, 3750000
    };

    /** Diamond road manhattan distances (|x|+|z|). */
    public static final double[] DIAMOND_DISTANCES = {
            2500, 5000, 25000, 50000, 125000, 250000, 500000, 3750000
    };

    /** Tolerance in blocks for matching ring/diamond distances. */
    private static final double RING_DIAMOND_TOLERANCE = 5.0;

    // ======================== HIGHWAY AXES ========================

    public enum HighwayAxis {
        PLUS_X(false, 1, 0),
        MINUS_X(false, -1, 0),
        PLUS_Z(false, 0, 1),
        MINUS_Z(false, 0, -1),
        DIAG_PX_PZ(true, 1, 1),
        DIAG_PX_MZ(true, 1, -1),
        DIAG_MX_PZ(true, -1, 1),
        DIAG_MX_MZ(true, -1, -1);

        public final boolean diagonal;
        public final int stepDx;
        public final int stepDz;

        HighwayAxis(boolean diagonal, int stepDx, int stepDz) {
            this.diagonal = diagonal;
            this.stepDx = stepDx;
            this.stepDz = stepDz;
        }

        /** Get the perpendicular offset direction (right-hand rule from step direction). */
        public int perpDx() {
            if (diagonal) return stepDz; // rotate 90° CW
            return stepDz == 0 ? 0 : -stepDz;
        }

        public int perpDz() {
            if (diagonal) return -stepDx; // rotate 90° CW
            return stepDx == 0 ? 0 : stepDx;
        }

        /** Expected yaw angle for walking along this axis (degrees, Minecraft convention). */
        public float expectedYaw() {
            // MC yaw: 0=South(+Z), 90=West(-X), 180=North(-Z), 270=East(+X)
            return switch (this) {
                case PLUS_X -> 270f;
                case MINUS_X -> 90f;
                case PLUS_Z -> 0f;
                case MINUS_Z -> 180f;
                case DIAG_PX_PZ -> 315f; // SE
                case DIAG_PX_MZ -> 225f; // NE
                case DIAG_MX_PZ -> 45f;  // SW
                case DIAG_MX_MZ -> 135f; // NW
            };
        }

        public Direction toFacingDirection() {
            // For cardinals, map directly. For diagonals, use primary component.
            return switch (this) {
                case PLUS_X, DIAG_PX_PZ, DIAG_PX_MZ -> Direction.EAST;
                case MINUS_X, DIAG_MX_PZ, DIAG_MX_MZ -> Direction.WEST;
                case PLUS_Z -> Direction.SOUTH;
                case MINUS_Z -> Direction.NORTH;
            };
        }
    }

    public static class HighwayInfo {
        public HighwayAxis axis;
        public boolean diagonal;
        public int width;                // 3 or 5
        public BlockPos leftRail;        // world pos of left guardrail (relative to walk direction)
        public BlockPos rightRail;       // world pos of right guardrail
        public int floorY;               // Y level of the floor
        public Direction walkDirection;  // direction the gooner walks (away from clog target)
        public Direction facingDirection; // direction toward the clog target
        public float confidence;         // 0.0-1.0

        // Ring/diamond classification (null for cardinal/diagonal highways)
        public HighwayCategory category = HighwayCategory.CARDINAL;
        public double ringOrDiamondDist = 0;  // which ring/diamond distance (0 for cardinal/diagonal)
        public RingSide ringSide = null;       // which side of the ring square
        public DiamondSegment diamondSegment = null; // which side of the diamond square

        @Override
        public String toString() {
            String base = String.format("Highway[%s, w=%d, y=%d, conf=%.0f%%, walk=%s",
                    axis, width, floorY, confidence * 100, walkDirection);
            if (category == HighwayCategory.RING) {
                return base + String.format(", ring=%.0f, side=%s]", ringOrDiamondDist, ringSide);
            } else if (category == HighwayCategory.DIAMOND) {
                return base + String.format(", diamond=%.0f, seg=%s]", ringOrDiamondDist, diamondSegment);
            }
            return base + "]";
        }
    }

    public static class NearbyHighway {
        public HighwayAxis axis;
        public int perpendicularOffset;  // blocks from main highway center
        public BlockPos samplePos;       // a floor block on this highway
        public int width;

        @Override
        public String toString() {
            return String.format("NearbyHighway[%s, offset=%d, w=%d, at %s]",
                    axis, perpendicularOffset, width, samplePos.toShortString());
        }
    }

    // ======================== DETECTION ========================

    /**
     * Run full 3-signal detection. Returns null if confidence < minConfidence.
     * Only detects cardinal/diagonal highways (legacy behavior).
     */
    public static HighwayInfo detect(MinecraftClient mc, float minConfidence) {
        List<HighwayInfo> all = detectAll(mc, minConfidence, false, false);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Run full 3-signal detection with ring/diamond support.
     * Returns null if confidence < minConfidence.
     */
    public static HighwayInfo detect(MinecraftClient mc, float minConfidence,
                                     boolean detectRings, boolean detectDiamonds) {
        List<HighwayInfo> all = detectAll(mc, minConfidence, detectRings, detectDiamonds);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Detect ALL highways at the player's position above minConfidence.
     * Returns multiple results if the player is at a junction (where two highways cross).
     * Results sorted by confidence descending.
     */
    public static List<HighwayInfo> detectAll(MinecraftClient mc, float minConfidence,
                                               boolean detectRings, boolean detectDiamonds) {
        List<HighwayInfo> results = new ArrayList<>();
        if (mc.player == null || mc.world == null) return results;

        BlockPos playerPos = mc.player.getBlockPos();
        float playerYaw = mc.player.getYaw();
        int px = playerPos.getX();
        int pz = playerPos.getZ();

        // === Signal 1: Coordinate analysis ===
        CoordResult coordResult = analyzeCoordinates(px, pz, detectRings, detectDiamonds);

        // === Evaluate each candidate independently ===
        // For junction detection, we need to keep ALL candidates that pass threshold,
        // not just the single best one.
        for (AxisCandidate candidate : coordResult.candidates) {
            float yawConf = yawConfidence(playerYaw, candidate.axis);
            float combinedConf = candidate.confidence * 0.6f + yawConf * 0.4f;

            // === Signal 2: Block pattern scan ===
            ScanResult scan = scanHighwayStructure(playerPos, candidate.axis);
            if (scan == null) {
                combinedConf *= 0.5f;
                if (combinedConf < minConfidence) continue;

                scan = new ScanResult();
                scan.width = 5;
                scan.floorY = playerPos.getY() - 1;
                scan.leftRailOffset = -(scan.width / 2 + 1);
                scan.rightRailOffset = scan.width / 2 + 1;
                scan.blockConfidence = 0f;
            } else {
                combinedConf = combinedConf * 0.6f + scan.blockConfidence * 0.4f;
                // Block signal dominance: if the floor+rails clearly look like a highway,
                // trust the block scan even when yaw is perpendicular or coord match is weak.
                // This lets the detector work when you're standing on a highway facing any direction.
                combinedConf = Math.max(combinedConf, scan.blockConfidence * 0.85f);
            }

            if (combinedConf < minConfidence) continue;

            // Build HighwayInfo
            HighwayInfo info = new HighwayInfo();
            info.axis = candidate.axis;
            info.diagonal = candidate.axis.diagonal;
            info.width = scan.width;
            info.floorY = scan.floorY;
            info.confidence = Math.min(1f, combinedConf);
            info.category = candidate.category;
            info.ringOrDiamondDist = candidate.ringOrDiamondDist;
            info.ringSide = candidate.ringSide;
            info.diamondSegment = candidate.diamondSegment;

            // Calculate rail world positions
            int perpDx = candidate.axis.perpDx();
            int perpDz = candidate.axis.perpDz();
            BlockPos center = new BlockPos(px, scan.floorY, pz);
            info.leftRail = center.add(perpDx * scan.leftRailOffset, 0, perpDz * scan.leftRailOffset);
            info.rightRail = center.add(perpDx * scan.rightRailOffset, 0, perpDz * scan.rightRailOffset);

            info.facingDirection = candidate.axis.toFacingDirection();
            info.walkDirection = info.facingDirection.getOpposite();

            // Avoid duplicates: don't add if we already have this axis+category
            boolean duplicate = results.stream().anyMatch(r ->
                    r.axis == info.axis && r.category == info.category
                    && r.ringOrDiamondDist == info.ringOrDiamondDist);
            if (!duplicate) {
                results.add(info);
            }
        }

        // Sort by confidence descending
        results.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        return results;
    }

    // ======================== SIGNAL 1: COORDINATES ========================

    private static class AxisCandidate {
        HighwayAxis axis;
        float confidence;
        // Ring/diamond metadata (null for cardinal/diagonal)
        HighwayCategory category;
        double ringOrDiamondDist;
        RingSide ringSide;
        DiamondSegment diamondSegment;

        AxisCandidate(HighwayAxis axis, float confidence) {
            this.axis = axis;
            this.confidence = confidence;
            this.category = axis.diagonal ? HighwayCategory.DIAGONAL : HighwayCategory.CARDINAL;
        }

        static AxisCandidate ring(HighwayAxis axis, float confidence, double dist, RingSide side) {
            AxisCandidate c = new AxisCandidate(axis, confidence);
            c.category = HighwayCategory.RING;
            c.ringOrDiamondDist = dist;
            c.ringSide = side;
            return c;
        }

        static AxisCandidate diamond(HighwayAxis axis, float confidence, double dist, DiamondSegment seg) {
            AxisCandidate c = new AxisCandidate(axis, confidence);
            c.category = HighwayCategory.DIAMOND;
            c.ringOrDiamondDist = dist;
            c.diamondSegment = seg;
            return c;
        }
    }

    private static class CoordResult {
        List<AxisCandidate> candidates = new ArrayList<>();
    }

    private static CoordResult analyzeCoordinates(int x, int z) {
        return analyzeCoordinates(x, z, false, false);
    }

    private static CoordResult analyzeCoordinates(int x, int z, boolean detectRings, boolean detectDiamonds) {
        CoordResult result = new CoordResult();
        int absX = Math.abs(x);
        int absZ = Math.abs(z);

        // Cardinal detection: one coordinate near zero, other far from origin
        if (absZ < 50 && absX > 100) {
            float conf = 1f - (absZ / 50f);
            result.candidates.add(new AxisCandidate(x > 0 ? HighwayAxis.PLUS_X : HighwayAxis.MINUS_X, conf));
        }
        if (absX < 50 && absZ > 100) {
            float conf = 1f - (absX / 50f);
            result.candidates.add(new AxisCandidate(z > 0 ? HighwayAxis.PLUS_Z : HighwayAxis.MINUS_Z, conf));
        }

        // Diagonal detection: |x ± z| near zero
        int sumXZ = x + z;
        int diffXZ = x - z;

        if (Math.abs(diffXZ) < 50 && absX > 100 && absZ > 100) {
            float conf = 1f - (Math.abs(diffXZ) / 50f);
            if (x > 0 && z > 0) result.candidates.add(new AxisCandidate(HighwayAxis.DIAG_PX_PZ, conf));
            else if (x < 0 && z < 0) result.candidates.add(new AxisCandidate(HighwayAxis.DIAG_MX_MZ, conf));
        }
        if (Math.abs(sumXZ) < 50 && absX > 100 && absZ > 100) {
            float conf = 1f - (Math.abs(sumXZ) / 50f);
            if (x > 0 && z < 0) result.candidates.add(new AxisCandidate(HighwayAxis.DIAG_PX_MZ, conf));
            else if (x < 0 && z > 0) result.candidates.add(new AxisCandidate(HighwayAxis.DIAG_MX_PZ, conf));
        }

        // Ring road detection: |x| ≈ D or |z| ≈ D for known ring distances
        // Ring roads are axis-aligned squares — each side is parallel to a cardinal axis
        if (detectRings) {
            for (double D : RING_DISTANCES) {
                // East side: x ≈ +D
                if (Math.abs(absX - D) < RING_DIAMOND_TOLERANCE && x > 0) {
                    float conf = 1f - (float) (Math.abs(absX - D) / RING_DIAMOND_TOLERANCE);
                    // Runs along Z axis
                    HighwayAxis axis = z > 0 ? HighwayAxis.PLUS_Z : HighwayAxis.MINUS_Z;
                    result.candidates.add(AxisCandidate.ring(axis, conf * 0.8f, D, RingSide.EAST));
                }
                // West side: x ≈ -D
                if (Math.abs(absX - D) < RING_DIAMOND_TOLERANCE && x < 0) {
                    float conf = 1f - (float) (Math.abs(absX - D) / RING_DIAMOND_TOLERANCE);
                    HighwayAxis axis = z > 0 ? HighwayAxis.PLUS_Z : HighwayAxis.MINUS_Z;
                    result.candidates.add(AxisCandidate.ring(axis, conf * 0.8f, D, RingSide.WEST));
                }
                // South side: z ≈ +D
                if (Math.abs(absZ - D) < RING_DIAMOND_TOLERANCE && z > 0) {
                    float conf = 1f - (float) (Math.abs(absZ - D) / RING_DIAMOND_TOLERANCE);
                    HighwayAxis axis = x > 0 ? HighwayAxis.PLUS_X : HighwayAxis.MINUS_X;
                    result.candidates.add(AxisCandidate.ring(axis, conf * 0.8f, D, RingSide.SOUTH));
                }
                // North side: z ≈ -D
                if (Math.abs(absZ - D) < RING_DIAMOND_TOLERANCE && z < 0) {
                    float conf = 1f - (float) (Math.abs(absZ - D) / RING_DIAMOND_TOLERANCE);
                    HighwayAxis axis = x > 0 ? HighwayAxis.PLUS_X : HighwayAxis.MINUS_X;
                    result.candidates.add(AxisCandidate.ring(axis, conf * 0.8f, D, RingSide.NORTH));
                }
            }
        }

        // Diamond road detection: |x| + |z| ≈ D for known diamond distances
        // Diamond roads are 45°-rotated squares — each side is parallel to a diagonal axis
        if (detectDiamonds) {
            double manhattan = (double) absX + absZ;
            for (double D : DIAMOND_DISTANCES) {
                if (Math.abs(manhattan - D) < RING_DIAMOND_TOLERANCE) {
                    float conf = 1f - (float) (Math.abs(manhattan - D) / RING_DIAMOND_TOLERANCE);
                    // Determine segment from quadrant
                    DiamondSegment seg;
                    HighwayAxis axis;
                    if (x >= 0 && z >= 0) {
                        seg = DiamondSegment.NE;
                        // NE segment: from (D,0) to (0,D), step direction is (-1,+1) or (+1,-1)
                        axis = HighwayAxis.DIAG_MX_PZ; // going from +X toward +Z
                    } else if (x < 0 && z >= 0) {
                        seg = DiamondSegment.NW;
                        axis = HighwayAxis.DIAG_MX_MZ;
                    } else if (x < 0 && z < 0) {
                        seg = DiamondSegment.SW;
                        axis = HighwayAxis.DIAG_PX_MZ;
                    } else {
                        seg = DiamondSegment.SE;
                        axis = HighwayAxis.DIAG_PX_PZ;
                    }
                    result.candidates.add(AxisCandidate.diamond(axis, conf * 0.8f, D, seg));
                }
            }
        }

        // Always add ALL axes as weak candidates so yaw + block scan can still detect
        // side highways, parallel diagonals, or any highway not on a main axis.
        for (HighwayAxis axis : HighwayAxis.values()) {
            boolean alreadyAdded = result.candidates.stream().anyMatch(c -> c.axis == axis);
            if (!alreadyAdded) {
                result.candidates.add(new AxisCandidate(axis, 0.1f));
            }
        }

        return result;
    }

    // ======================== SIGNAL 2: BLOCK SCAN ========================

    private static class ScanResult {
        int width;              // 3 or 5
        int floorY;
        int leftRailOffset;     // negative (left of center)
        int rightRailOffset;    // positive (right of center)
        float blockConfidence;
    }

    /**
     * Scan along the axis ±20 blocks and perpendicular at each step.
     * Aggregates floor edges across many samples to find consistent rail positions.
     * Only counts obsidian/crying obsidian as highway blocks.
     */
    private static ScanResult scanHighwayStructure(BlockPos playerPos, HighwayAxis axis) {
        if (mc.world == null) return null;

        int perpDx = axis.perpDx();
        int perpDz = axis.perpDz();
        int stepDx = axis.stepDx;
        int stepDz = axis.stepDz;

        // Try multiple Y levels for the floor
        for (int yOff = -1; yOff <= 1; yOff++) {
            int floorY = playerPos.getY() + yOff;
            ScanResult result = scanAlongAxis(playerPos.getX(), floorY, playerPos.getZ(),
                    perpDx, perpDz, stepDx, stepDz, axis);
            if (result != null) return result;
        }

        return null;
    }

    /**
     * Scan ±20 blocks along the axis. At each step, measure perpendicular floor extent.
     * Use the MEDIAN left/right edge to determine consistent rail positions.
     */
    private static ScanResult scanAlongAxis(int px, int floorY, int pz,
                                             int perpDx, int perpDz,
                                             int stepDx, int stepDz,
                                             HighwayAxis axis) {
        int scanRange = 20; // blocks along axis in each direction
        int maxPerp = 8;    // max perpendicular scan distance

        List<Integer> leftEdges = new ArrayList<>();
        List<Integer> rightEdges = new ArrayList<>();
        int validSamples = 0;

        // First, find the highway center at player position (shift perp if needed)
        int centerX = px;
        int centerZ = pz;
        if (!isHighwayBlock(centerX, floorY, centerZ)) {
            boolean found = false;
            for (int shift = 1; shift <= 4; shift++) {
                if (isHighwayBlock(px + perpDx * shift, floorY, pz + perpDz * shift)) {
                    centerX = px + perpDx * shift;
                    centerZ = pz + perpDz * shift;
                    found = true;
                    break;
                }
                if (isHighwayBlock(px - perpDx * shift, floorY, pz - perpDz * shift)) {
                    centerX = px - perpDx * shift;
                    centerZ = pz - perpDz * shift;
                    found = true;
                    break;
                }
            }
            if (!found) return null;
        }

        // Scan along axis, at each step measure perpendicular extent
        for (int step = -scanRange; step <= scanRange; step++) {
            int sx = centerX + stepDx * step;
            int sz = centerZ + stepDz * step;

            // Check if this axis position has highway floor at center
            if (!isHighwayBlock(sx, floorY, sz)) continue;

            // Measure perpendicular extent at this axis position
            int left = 0;
            for (int i = 1; i <= maxPerp; i++) {
                if (isHighwayBlock(sx - perpDx * i, floorY, sz - perpDz * i)) left = i;
                else break;
            }
            int right = 0;
            for (int i = 1; i <= maxPerp; i++) {
                if (isHighwayBlock(sx + perpDx * i, floorY, sz + perpDz * i)) right = i;
                else break;
            }

            leftEdges.add(left);
            rightEdges.add(right);
            validSamples++;
        }

        if (validSamples < 5) return null; // not enough samples

        // Use MEDIAN of edges — filters out outliers from debris/branches
        leftEdges.sort(null);
        rightEdges.sort(null);
        int medianLeft = leftEdges.get(leftEdges.size() / 2);
        int medianRight = rightEdges.get(rightEdges.size() / 2);

        int width = medianLeft + 1 + medianRight;
        if (width < 2 || width > 7) return null;

        // Check guardrails at the median edge positions
        boolean leftRail = hasGuardrail(centerX - perpDx * (medianLeft + 1), floorY, centerZ - perpDz * (medianLeft + 1));
        boolean rightRail = hasGuardrail(centerX + perpDx * (medianRight + 1), floorY, centerZ + perpDz * (medianRight + 1));

        // Confidence: linearity (valid samples / total scan) + guardrails
        float linearityRatio = validSamples / (float) (scanRange * 2 + 1);
        float blockConf = 0f;
        if (linearityRatio > 0.5f) blockConf += 0.4f;
        else if (linearityRatio > 0.3f) blockConf += 0.2f;
        if (leftRail) blockConf += 0.2f;
        if (rightRail) blockConf += 0.2f;
        if (width >= 3 && width <= 7) blockConf += 0.2f;
        blockConf = Math.min(1f, blockConf);

        if (blockConf < 0.3f) return null;

        ScanResult result = new ScanResult();
        result.width = width;
        result.floorY = floorY;
        result.leftRailOffset = -(medianLeft + 1);
        result.rightRailOffset = medianRight + 1;
        result.blockConfidence = blockConf;
        return result;
    }

    private static boolean isHighwayBlock(int x, int y, int z) {
        if (mc.world == null) return false;
        BlockState state = mc.world.getBlockState(new BlockPos(x, y, z));
        return HIGHWAY_FLOOR_BLOCKS.contains(state.getBlock());
    }

    private static boolean hasGuardrail(int x, int floorY, int z) {
        if (mc.world == null) return false;
        // Guardrail = obsidian/crying obby at floor level AND at least 1 block above
        Block base = mc.world.getBlockState(new BlockPos(x, floorY, z)).getBlock();
        Block above = mc.world.getBlockState(new BlockPos(x, floorY + 1, z)).getBlock();
        return HIGHWAY_FLOOR_BLOCKS.contains(base) && HIGHWAY_FLOOR_BLOCKS.contains(above);
    }

    // ======================== SIGNAL 3: YAW ========================

    private static float yawConfidence(float playerYaw, HighwayAxis axis) {
        float expected = axis.expectedYaw();
        float diff = MathHelper.wrapDegrees(playerYaw - expected);
        float absDiff = Math.abs(diff);

        // Also check the opposite direction (player may face either way on the highway)
        float oppositeExpected = MathHelper.wrapDegrees(expected + 180f);
        float oppDiff = Math.abs(MathHelper.wrapDegrees(playerYaw - oppositeExpected));
        absDiff = Math.min(absDiff, oppDiff);

        if (absDiff < 15f) return 1f;
        if (absDiff < 30f) return 0.7f;
        if (absDiff < 45f) return 0.4f;
        if (absDiff < 60f) return 0.2f;
        return 0f;
    }

    // ======================== NEARBY HIGHWAY SCAN ========================

    /**
     * Scan for nearby parallel/side highways within range blocks perpendicular to primary.
     */
    public static List<NearbyHighway> scanNearbyHighways(MinecraftClient mc, HighwayInfo primary, int range) {
        List<NearbyHighway> found = new ArrayList<>();
        if (mc.player == null || mc.world == null || primary == null) return found;

        BlockPos playerPos = mc.player.getBlockPos();
        int perpDx = primary.axis.perpDx();
        int perpDz = primary.axis.perpDz();

        // Scan at intervals of 10 blocks in both perpendicular directions
        for (int dir = -1; dir <= 1; dir += 2) {
            for (int offset = 10; offset <= range; offset += 10) {
                int scanX = playerPos.getX() + perpDx * offset * dir;
                int scanZ = playerPos.getZ() + perpDz * offset * dir;

                // Quick check: is there a floor strip at this offset?
                ScanResult miniScan = scanAlongAxis(scanX, primary.floorY, scanZ,
                        perpDx, perpDz, primary.axis.stepDx, primary.axis.stepDz, primary.axis);
                if (miniScan != null && miniScan.blockConfidence > 0.4f) {
                    NearbyHighway nearby = new NearbyHighway();
                    nearby.axis = primary.axis; // parallel highway, same axis
                    nearby.perpendicularOffset = offset * dir;
                    nearby.samplePos = new BlockPos(scanX, miniScan.floorY, scanZ);
                    nearby.width = miniScan.width;
                    found.add(nearby);
                    break; // found one in this direction, stop scanning further
                }
            }
        }

        return found;
    }
}
