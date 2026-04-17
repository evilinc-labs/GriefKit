package com.griefkit.mixin;

import com.griefkit.modules.DoubleMine;
import com.griefkit.modules.PacketMine;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void onAttackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (DoubleMine.INSTANCE != null && DoubleMine.INSTANCE.isActive()) {
            DoubleMine.INSTANCE.addTarget(pos);
            cir.setReturnValue(true);
        } else if (PacketMine.INSTANCE != null && PacketMine.INSTANCE.isActive()) {
            PacketMine.INSTANCE.enqueueBlock(pos);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void onUpdateBlockBreakingProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (DoubleMine.INSTANCE != null && DoubleMine.INSTANCE.isActive()) {
            if (DoubleMine.INSTANCE.isTargeted(pos)) cir.setReturnValue(true);
        } else if (PacketMine.INSTANCE != null && PacketMine.INSTANCE.isActive()) {
            if (PacketMine.INSTANCE.isAlreadyQueued(pos)) cir.setReturnValue(true);
        }
    }
}
