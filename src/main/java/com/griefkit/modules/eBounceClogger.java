package com.griefkit.modules;

import com.griefkit.GriefKit;
import com.griefkit.helpers.HotbarSupply;
import com.griefkit.placement.PlacementStep;
import java.util.Random;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Position;
import net.minecraft.util.math.Vec3d;

public class eBounceClogger extends Module {
    private final SettingGroup sgGeneral;
    private final SettingGroup sgSupply;
    private final SettingGroup sgIntegration;
    
    private final Setting<Block> block;
    private final Setting<Integer> backMin;
    private final Setting<Integer> backMax;
    private final Setting<Integer> halfWidth;
    private final Setting<Integer> height;
    private final Setting<Integer> placementsPerMove;
    private final Setting<Double> moveThreshold;
    private final Setting<Double> chance;
    private final Setting<Boolean> requireReplaceableNow;
    
    // Replenishment settings
    private final Setting<Boolean> autoReplenish;
    private final Setting<Integer> replenishThreshold;
    private final Setting<Boolean> lowInventoryWarning;
    
    // Integration settings
    private final Setting<Boolean> huntAddonIntegration;
    
    private final Random rng;
    private Vec3d lastPos;
    
    // Track warned state to prevent spam
    private boolean hasWarnedLowInventory;
    
    // Hunt addon state tracking
    private boolean huntAddonDetected;
    private boolean elytraFlyWasEnabled;
    private boolean freeLookWasEnabled;

    public eBounceClogger() {
        super(GriefKit.CATEGORY, "ebounce-clogger", "Randomly clogs behind you while flying. Useful for eBounce highway travel.");
        
        this.sgGeneral = this.settings.getDefaultGroup();
        this.sgSupply = this.settings.createGroup("Supply");
        this.sgIntegration = this.settings.createGroup("Integration");
        
        this.block = this.sgGeneral.add(new BlockSetting.Builder()
            .name("block")
            .description("Block to place for clogging.")
            .defaultValue(Blocks.OBSIDIAN)
            .build());
        this.backMin = this.sgGeneral.add(new IntSetting.Builder()
            .name("back-min")
            .description("Minimum blocks behind player to place.")
            .defaultValue(2)
            .min(1).max(16)
            .build());
        this.backMax = this.sgGeneral.add(new IntSetting.Builder()
            .name("back-max")
            .description("Maximum blocks behind player to place.")
            .defaultValue(6)
            .min(1).max(32)
            .build());
        this.halfWidth = this.sgGeneral.add(new IntSetting.Builder()
            .name("half-width")
            .description("Horizontal half-width of the clog box (left/right). 2 => width 5.")
            .defaultValue(2)
            .min(0).max(8)
            .build());
        this.height = this.sgGeneral.add(new IntSetting.Builder()
            .name("height")
            .description("Vertical range (0..height-1) above the base Y.")
            .defaultValue(3)
            .min(1).max(6)
            .build());
        this.placementsPerMove = this.sgGeneral.add(new IntSetting.Builder()
            .name("placements-per-move")
            .description("How many placements to schedule each time you move enough.")
            .defaultValue(6)
            .min(1).max(20)
            .build());
        this.moveThreshold = this.sgGeneral.add(new DoubleSetting.Builder()
            .name("move-threshold")
            .description("Distance (blocks) you must move before scheduling more placements.")
            .defaultValue(2.5)
            .min(0.05).max(2.0)
            .build());
        this.chance = this.sgGeneral.add(new DoubleSetting.Builder()
            .name("chance")
            .description("Chance per scheduled attempt to actually enqueue (adds randomness).")
            .defaultValue(1.0)
            .min(0.0).max(1.0)
            .build());
        this.requireReplaceableNow = this.sgGeneral.add(new BoolSetting.Builder()
            .name("require-replaceable-now")
            .description("Only enqueue if the world is currently replaceable at the target.")
            .defaultValue(true)
            .build());
        
        // Supply settings
        this.autoReplenish = this.sgSupply.add(new BoolSetting.Builder()
            .name("auto-replenish")
            .description("Automatically refill the selected block from inventory.")
            .defaultValue(true)
            .build());
        this.replenishThreshold = this.sgSupply.add(new IntSetting.Builder()
            .name("replenish-threshold")
            .description("Refill hotbar when block count falls below this value.")
            .defaultValue(32)
            .min(1).max(64)
            .sliderMax(64)
            .build());
        this.lowInventoryWarning = this.sgSupply.add(new BoolSetting.Builder()
            .name("low-inventory-warning")
            .description("Warn when total block count falls below threshold + 64.")
            .defaultValue(true)
            .build());
        
        // Integration settings
        this.huntAddonIntegration = this.sgIntegration.add(new BoolSetting.Builder()
            .name("hunt-addon-integration")
            .description("Auto-disable Hunt addon modules when clogger stops placing.")
            .defaultValue(false)
            .build());
        
        this.rng = new Random();
        this.lastPos = null;
        this.hasWarnedLowInventory = false;
        
        // Hunt addon tracking
        this.huntAddonDetected = false;
        this.elytraFlyWasEnabled = false;
        this.freeLookWasEnabled = false;
    }

    @Override
    public void onActivate() {
        this.lastPos = null;
        this.hasWarnedLowInventory = false;
        
        if (this.mc.player == null || this.mc.world == null) {
            this.warning("Player/world not loaded", new Object[0]);
            this.toggle();
            return;
        }
        
        // Module enabled notification
        ChatUtils.info("eBounceClogger enabled");
        
        // Check if replenishment is possible on activation
        Block targetBlock = this.block.get();
        if (!HotbarSupply.canReplenish(targetBlock, this.replenishThreshold.get())) {
            this.warning("Insufficient blocks in inventory (need " + this.replenishThreshold.get() + ")", new Object[0]);
            // Don't toggle off - let user decide, but warn them
        }
        
        // Hunt addon integration - capture state on enable
        if (this.huntAddonIntegration.get()) {
            this.captureHuntAddonState();
        }
    }

    @Override
    public void onDeactivate() {
        this.lastPos = null;
        this.hasWarnedLowInventory = false;
        
        // Module disabled notification
        ChatUtils.info("eBounceClogger disabled");
        
        // Hunt addon integration - restore state on disable if clogger isn't placing
        if (this.huntAddonIntegration.get() && this.huntAddonDetected) {
            this.restoreHuntAddonState();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null) {
            return;
        }
        
        ClientPlayerEntity p = this.mc.player;
        
        // Handle replenishment BEFORE scheduling placements
        if (this.autoReplenish.get()) {
            Block targetBlock = this.block.get();
            
            // Check if we can replenish
            if (!HotbarSupply.canReplenish(targetBlock)) {
                // No blocks available - don't schedule placements
                // If Hunt integration is enabled, disable flight modules
                if (this.huntAddonIntegration.get() && this.huntAddonDetected) {
                    this.disableHuntModules();
                }
                return;
            }
            
            // Low inventory warning (threshold + 64 = at least 1 stack remaining)
            if (this.lowInventoryWarning.get() && !this.hasWarnedLowInventory) {
                int totalCount = HotbarSupply.getTotalCount(targetBlock);
                int warningThreshold = this.replenishThreshold.get() + 64;
                
                if (totalCount < warningThreshold) {
                    this.hasWarnedLowInventory = true;
                    ChatUtils.warning("Low inventory: " + totalCount + " blocks remaining");
                    
                    // Play warning sound if player exists
                    if (this.mc.player != null) {
                        this.mc.player.playSound(
                            SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                            1.0f,
                            1.0f
                        );
                    }
                }
            }
            
            // Ensure block is in hotbar and refill if needed
            HotbarSupply.ensureHotbarStack(
                HotbarSupply.blockIs(targetBlock),
                this.replenishThreshold.get(),
                false // Don't auto-select the slot
            );
        }
        
        //? if >=1.21.11 {
        Vec3d now = p.getEntityPos();
        //?} else
        /*Vec3d now = p.getPos();*/
        if (this.lastPos == null) {
            this.lastPos = now;
            this.scheduleBatch(p, (Integer)this.placementsPerMove.get());
            return;
        }
        
        double moved = now.distanceTo(this.lastPos);
        if (moved >= (Double)this.moveThreshold.get()) {
            this.lastPos = now;
            this.scheduleBatch(p, (Integer)this.placementsPerMove.get());
        }
    }

    private void scheduleBatch(ClientPlayerEntity p, int count) {
        Vec3d forward;
        if (this.mc.world == null) {
            return;
        }
        Vec3d vel = p.getVelocity();
        Vec3d moveDir = new Vec3d(vel.x, 0.0, vel.z);
        if (moveDir.lengthSquared() > 1.0E-6) {
            forward = moveDir.normalize();
        } else {
            float yaw = p.getYaw();
            float yawRad = yaw * ((float)Math.PI / 180);
            forward = new Vec3d((double)(-MathHelper.sin((float)yawRad)), 0.0, (double)MathHelper.cos((float)yawRad)).normalize();
        }
        Vec3d behind = forward.negate();
        Vec3d right = new Vec3d(-behind.z, 0.0, behind.x);
        BlockPos base = p.getBlockPos();
        int minB = Math.min((Integer)this.backMin.get(), (Integer)this.backMax.get());
        int maxB = Math.max((Integer)this.backMin.get(), (Integer)this.backMax.get());
        int w = (Integer)this.halfWidth.get();
        int h = (Integer)this.height.get();
        Block placeBlock = (Block)this.block.get();
        
        for (int i = 0; i < count; ++i) {
            if (this.rng.nextDouble() > (Double)this.chance.get()) continue;
            int back = this.randInt(minB, maxB);
            int lateral = w == 0 ? 0 : this.randInt(-w, w);
            int up = h <= 1 ? 0 : this.randInt(0, h - 1);
            Vec3d offset = behind.multiply((double)back).add(right.multiply((double)lateral)).add(0.0, (double)up, 0.0);
            BlockPos target = BlockPos.ofFloored((Position)new Vec3d((double)base.getX() + 0.5, (double)base.getY(), (double)base.getZ() + 0.5).add(offset));
            
            if (target.getX() == base.getX() && target.getZ() == base.getZ() && target.getY() == base.getY()) continue;
            if (((Boolean)this.requireReplaceableNow.get()).booleanValue() && !this.mc.world.getBlockState(target).isReplaceable()) continue;
            
            GriefKit.PLACEMENT.enqueue(new PlacementStep(target, placeBlock, Direction.UP));
        }
    }

    private int randInt(int a, int b) {
        if (a == b) {
            return a;
        }
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return lo + this.rng.nextInt(hi - lo + 1);
    }

    // ----- Hunt Addon Integration -----
    
    /**
     * Check if Hunt addon is loaded by looking for its main class.
     * Returns true if com.stash.hunt.Addon is present.
     */
    private boolean isHuntAddonLoaded() {
        try {
            Class.forName("com.stash.hunt.Addon");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Capture the state of Hunt addon modules when clogger is enabled.
     * Only acts if Hunt addon is detected.
     */
    private void captureHuntAddonState() {
        this.huntAddonDetected = false;
        this.elytraFlyWasEnabled = false;
        this.freeLookWasEnabled = false;
        
        // First check if Hunt addon is loaded
        if (!this.isHuntAddonLoaded()) {
            return;
        }
        
        try {
            // Check for Hunt addon's ElytraFlyPlusPlus module
            Module elytraFly = Modules.get().get("elytraflyplusplus");
            if (elytraFly != null) {
                this.huntAddonDetected = true;
                this.elytraFlyWasEnabled = elytraFly.isActive();
            }
            
            // Check for baseline Meteor's Freelook module
            Module freeLook = Modules.get().get("freelook");
            if (freeLook != null) {
                this.freeLookWasEnabled = freeLook.isActive();
            }
        } catch (Exception e) {
            // Hunt addon not present or modules not found - silent fail
        }
    }
    
    /**
     * Restore Hunt addon module states when clogger is disabled.
     * Only restores if modules were previously enabled.
     */
    private void restoreHuntAddonState() {
        try {
            // Restore ElytraFlyPlusPlus if it was enabled
            if (this.elytraFlyWasEnabled) {
                Module elytraFly = Modules.get().get("elytraflyplusplus");
                if (elytraFly != null && !elytraFly.isActive()) {
                    elytraFly.toggle();
                }
            }
            
            // Restore Freelook if it was enabled
            if (this.freeLookWasEnabled) {
                Module freeLook = Modules.get().get("freelook");
                if (freeLook != null && !freeLook.isActive()) {
                    freeLook.toggle();
                }
            }
        } catch (Exception e) {
            // Silent fail if modules not found
        }
    }
    
    /**
     * Disable Hunt addon modules when clogger stops placing.
     * Called when we run out of blocks and can't continue.
     */
    private void disableHuntModules() {
        try {
            // Disable ElytraFlyPlusPlus if active
            Module elytraFly = Modules.get().get("elytraflyplusplus");
            if (elytraFly != null && elytraFly.isActive()) {
                elytraFly.toggle();
                ChatUtils.info("Disabled ElytraFlyPlusPlus (out of blocks)");
            }
            
            // Disable Freelook if active
            Module freeLook = Modules.get().get("freelook");
            if (freeLook != null && freeLook.isActive()) {
                freeLook.toggle();
                ChatUtils.info("Disabled Freelook (out of blocks)");
            }
        } catch (Exception e) {
            // Silent fail if modules not found
        }
    }
}