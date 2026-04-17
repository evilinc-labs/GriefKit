package com.griefkit.helpers.gooner;

import com.griefkit.helpers.gooner.HighwayDetector.HighwayAxis;
import com.griefkit.helpers.gooner.HighwayDetector.HighwayInfo;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates clog placement plans from detected highway structure.
 *
 * Full clog pattern per step (bottom to top):
 *   Y+0 (floor): cover floor between rails (exclusive of rail blocks)
 *   Y+1 (above): cover entire row INCLUDING rail tops (width + rails)
 *   Y+2 (top):   rails ALWAYS + checkered between rails
 *   Y+3 (extra): rails ALWAYS + alternating checker (offset from Y+2)
 *
 * ClogMode controls density:
 *   BITCH    = pattern every 8th step (sparse, saves obsidian)
 *   STANDARD = pattern every 4th step (default)
 *   CLOG     = pattern every step (full clog)
 */
public class ClogPatternGenerator {

    public enum ClogMode {
        BITCH("Bitch Mode", 8),
        STANDARD("Standard", 4),
        CLOG("Clog Mode", 1);

        public final String displayName;
        public final int stepSpacing;

        ClogMode(String displayName, int spacing) {
            this.displayName = displayName;
            this.stepSpacing = spacing;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public static class PlacementEntry {
        public final BlockPos pos;
        public final boolean isRailCap;
        public final int yLayer;

        public PlacementEntry(BlockPos pos, boolean isRailCap, int yLayer) {
            this.pos = pos;
            this.isRailCap = isRailCap;
            this.yLayer = yLayer;
        }
    }

    /**
     * Generate the clog pattern for one step using ABSOLUTE rail positions.
     * No projection math — directly uses rail X/Z coords as bounds.
     *
     * For cardinal Z axis: X varies (perpendicular), Z comes from stepPos.
     * For cardinal X axis: Z varies (perpendicular), X comes from stepPos.
     */
    public static List<PlacementEntry> generateStepPattern(HighwayInfo info, BlockPos stepPos) {
        List<PlacementEntry> entries = new ArrayList<>();
        HighwayAxis axis = info.axis;
        int floorY = info.floorY;

        // Absolute rail bounds
        int railMinX = Math.min(info.leftRail.getX(), info.rightRail.getX());
        int railMaxX = Math.max(info.leftRail.getX(), info.rightRail.getX());
        int railMinZ = Math.min(info.leftRail.getZ(), info.rightRail.getZ());
        int railMaxZ = Math.max(info.leftRail.getZ(), info.rightRail.getZ());

        // Checker coordinate for alternating pattern
        int axisCoord = stepPos.getX() * axis.stepDx + stepPos.getZ() * axis.stepDz;

        if (axis.stepDz != 0 && axis.stepDx == 0) {
            // CARDINAL Z AXIS — perpendicular is X. Rails define X bounds.
            int z = stepPos.getZ();
            generateCardinalLayers(entries, railMinX, railMaxX, floorY, axisCoord,
                    (x, y) -> new BlockPos(x, y, z));

        } else if (axis.stepDx != 0 && axis.stepDz == 0) {
            // CARDINAL X AXIS — perpendicular is Z. Rails define Z bounds.
            int x = stepPos.getX();
            generateCardinalLayers(entries, railMinZ, railMaxZ, floorY, axisCoord,
                    (z, y) -> new BlockPos(x, y, z));

        } else {
            // DIAGONAL — use offset-based approach from center
            generateDiagonalLayers(entries, info, stepPos, floorY, axisCoord);
        }

        return entries;
    }

    @FunctionalInterface
    private interface PosFactory {
        BlockPos create(int perpCoord, int y);
    }

    /**
     * Generate all 4 Y layers for a cardinal highway.
     * @param min  minimum perpendicular coordinate (rail position)
     * @param max  maximum perpendicular coordinate (rail position)
     */
    private static void generateCardinalLayers(
            List<PlacementEntry> entries, int min, int max, int floorY,
            int axisCoord, PosFactory pos) {

        // Y+0: floor BETWEEN rails (exclusive — don't place on the rail blocks at floor level)
        for (int p = min + 1; p < max; p++) {
            entries.add(new PlacementEntry(pos.create(p, floorY), false, 0));
        }

        // Y+1: full width INCLUDING rails (cap the rail tops)
        for (int p = min; p <= max; p++) {
            boolean isRail = (p == min || p == max);
            entries.add(new PlacementEntry(pos.create(p, floorY + 1), isRail, 1));
        }

        // Y+2: rails always + checkered between
        for (int p = min; p <= max; p++) {
            boolean isRail = (p == min || p == max);
            if (isRail || (axisCoord + p) % 2 == 0) {
                entries.add(new PlacementEntry(pos.create(p, floorY + 2), isRail, 2));
            }
        }

        // Y+3: rails + alternating checker (offset by 1 from Y+2)
        for (int p = min; p <= max; p++) {
            boolean isRail = (p == min || p == max);
            if (isRail || (axisCoord + p + 1) % 2 == 0) {
                entries.add(new PlacementEntry(pos.create(p, floorY + 3), isRail, 3));
            }
        }
    }

    /** Diagonal pattern — uses perpDx/perpDz offsets from highway center. */
    private static void generateDiagonalLayers(
            List<PlacementEntry> entries, HighwayInfo info, BlockPos stepPos,
            int floorY, int axisCoord) {

        HighwayAxis axis = info.axis;
        int perpDx = axis.perpDx();
        int perpDz = axis.perpDz();
        int halfWidth = info.width / 2;

        // Use stepPos as center — advances with the player each step, just like cardinals
        int cx = stepPos.getX();
        int cz = stepPos.getZ();

        for (int dy = 0; dy <= 3; dy++) {
            int start = (dy == 0) ? -halfWidth : -(halfWidth + 1);
            int end = (dy == 0) ? halfWidth : (halfWidth + 1);

            for (int i = start; i <= end; i++) {
                boolean isRail = (dy > 0) && (i == start || i == end);
                int bx = cx + perpDx * i;
                int bz = cz + perpDz * i;

                if (dy >= 2 && !isRail) {
                    int checker = (dy == 2) ? 0 : 1;
                    if ((axisCoord + i + checker) % 2 != 0) continue;
                }

                entries.add(new PlacementEntry(new BlockPos(bx, floorY + dy, bz), isRail, dy));
            }
        }
    }

    /**
     * Generate a plan for one step at a specific position.
     * Used for incremental building (generate one step at a time as player walks).
     */
    public static List<PlacementEntry> generateNextStep(HighwayInfo info, BlockPos currentPos) {
        BlockPos stepCenter = new BlockPos(currentPos.getX(), info.floorY, currentPos.getZ());
        return generateStepPattern(info, stepCenter);
    }

    /**
     * Generate the complete placement plan for N steps along the highway axis.
     * ClogMode spacing controls which steps get patterns.
     */
    public static List<PlacementEntry> generateFullPlan(
            HighwayInfo info, BlockPos startPos, int stepCount, ClogMode mode) {
        List<PlacementEntry> plan = new ArrayList<>();

        int stepDx = info.axis.stepDx;
        int stepDz = info.axis.stepDz;
        int floorY = info.floorY;

        for (int step = 0; step < stepCount; step++) {
            if (step % mode.stepSpacing != 0) continue;

            int sx = startPos.getX() + stepDx * step;
            int sz = startPos.getZ() + stepDz * step;
            BlockPos stepCenter = new BlockPos(sx, floorY, sz);

            plan.addAll(generateStepPattern(info, stepCenter));
        }

        return plan;
    }

    /** Count total blocks needed for one step pattern at a given width. */
    public static int blocksPerStep(int width) {
        int floor = width;
        int above = width + 2;
        int railsTop = 2;
        int checkerBetween = (width + 2 - 2 + 1) / 2;
        return floor + above + railsTop + checkerBetween;
    }
}
