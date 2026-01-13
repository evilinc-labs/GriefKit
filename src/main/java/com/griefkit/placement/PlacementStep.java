/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.class_2248
 *  net.minecraft.class_2338
 *  net.minecraft.class_2350
 */
package com.griefkit.placement;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class PlacementStep {
    public final BlockPos pos;
    public final Block block;
    public final Direction supportFace;

    public PlacementStep(BlockPos pos, Block block, Direction supportFace) {
        this.pos = pos;
        this.block = block;
        this.supportFace = supportFace;
    }

    public PlacementStep(BlockPos pos, Block block) {
        this(pos, block, Direction.UP);
    }
}

