package com.griefkit.placement;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class PlacementStep {
    public final BlockPos pos;
    public final Block block;

    public final BlockPos supportPos;
    public final Direction supportFace;

    public PlacementStep(BlockPos pos, Block block, BlockPos supportPos, Direction supportFace) {
        this.pos = pos;
        this.block = block;
        this.supportPos = supportPos;
        this.supportFace = supportFace;
    }

    public PlacementStep(BlockPos pos, Block block) {
        this(pos, block, pos, Direction.UP);
    }

    // @todo readd suppost position for wither skulls, otherwise wither is fucky w placement manager support pos
}
