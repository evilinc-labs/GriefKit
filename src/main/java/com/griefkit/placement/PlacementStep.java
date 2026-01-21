package com.griefkit.placement;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class PlacementStep {
    public final BlockPos pos;
    public final Block block;

    // NEW: optional explicit support position
    public final BlockPos supportPos;

    public final Direction supportFace;

    // Full constructor: everything explicit
    public PlacementStep(BlockPos pos, Block block, BlockPos supportPos, Direction supportFace) {
        this.pos = pos;
        this.block = block;
        this.supportPos = supportPos;
        this.supportFace = supportFace;
    }

    // Legacy: face only, no explicit supportPos
    public PlacementStep(BlockPos pos, Block block, Direction supportFace) {
        this(pos, block, null, supportFace);
    }

    // Legacy default: UP face, no explicit supportPos
    public PlacementStep(BlockPos pos, Block block) {
        this(pos, block, null, Direction.UP);
    }

    // NEW: supportPos provided, derive face
    public PlacementStep(BlockPos pos, Block block, BlockPos supportPos) {
        this(pos, block, supportPos, getSupportFace(pos, supportPos));
    }

    // derive supportFace from a support position
    private static Direction getSupportFace(BlockPos pos, BlockPos supportPos) {
        BlockPos delta = pos.subtract(supportPos);

        for (Direction dir : Direction.values()) {
            if (dir.getVector().equals(delta)) {
                return dir;
            }
        }

        // fallback
        return Direction.UP;
    }
}
