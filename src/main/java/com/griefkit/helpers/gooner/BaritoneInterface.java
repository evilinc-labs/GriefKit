package com.griefkit.helpers.gooner;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-reflection Baritone bridge.
 * No hard references to baritone.api.* so classloading can't crash when Baritone isn't present.
 */
public final class BaritoneInterface {
    private BaritoneInterface() {}

    private static boolean globalSettingsApplied = false;
    private static Class<?> cachedApiClass = null;
    private static boolean raceConditionWarned = false;
    private static boolean detectionErrorLogged = false; // only log once, not every frame

    /**
     * Global Baritone detection state. Updated every 2 seconds by a background poller.
     * Any module can read this to check if Baritone is loaded and ready.
     */
    public static volatile boolean DETECTED = false;

    /**
     * Attempt to detect Baritone right now. Updates {@link #DETECTED} and returns result.
     * Called by the background poller and on-demand by modules.
     */
    public static boolean isBaritoneAvailable() {
        try {
            if (cachedApiClass == null) {
                try {
                    cachedApiClass = Class.forName("baritone.api.BaritoneAPI", true,
                            BaritoneInterface.class.getClassLoader());
                } catch (Throwable t1) {
                    try {
                        cachedApiClass = Class.forName("baritone.api.BaritoneAPI", true,
                                Thread.currentThread().getContextClassLoader());
                    } catch (Throwable t2) {
                        cachedApiClass = Class.forName("baritone.api.BaritoneAPI");
                    }
                }
            }
            Object provider = cachedApiClass.getMethod("getProvider").invoke(null);
            if (provider != null && !globalSettingsApplied) {
                globalSettingsApplied = true;
                raceConditionWarned = false;
                applyGlobalSettings();
            }
            DETECTED = provider != null;
            if (!DETECTED && !detectionErrorLogged) {
                detectionErrorLogged = true;
                System.err.println("[Baritone] Class found but getProvider() returned null");
            }
            if (DETECTED) detectionErrorLogged = false;
        } catch (Throwable t) {
            DETECTED = false;
            if (!detectionErrorLogged) {
                detectionErrorLogged = true;
                System.err.println("[Baritone] Detection failed: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }
        }
        return DETECTED;
    }

    /**
     * Register the perpetual Baritone detection system. Call once during mod init.
     *
     * <p>On game join: resets state, checks after 3 seconds, warns if not found.
     * <p>Every 2 seconds on the game thread: re-checks and updates {@link #DETECTED}.
     * If Baritone loads late (Fabric race condition), it will be picked up automatically.
     */
    public static void registerGameJoinCheck() {
        // no-op for GriefKit — Gooner calls isBaritoneAvailable() on enable
    }

    /** Apply global Baritone settings on first detection. */
    private static void applyGlobalSettings() {
        try {
            setSetting("allowSprint", true);
        } catch (Throwable ignored) {}
    }

    /** Set throwaway items to prefer netherrack for shelter building. */
    public static void setShelterThrowaway() {
        List<Item> throwaway = new ArrayList<>();
        throwaway.add(Items.NETHERRACK);
        throwaway.add(Items.COBBLESTONE);
        throwaway.add(Items.DIRT);
        setCachedThrowaway(throwaway);
    }

    /** Reset throwaway items — NO netherrack (prevents Baritone from grabbing it to hotbar). */
    public static void resetThrowaway() {
        List<Item> throwaway = new ArrayList<>();
        throwaway.add(Items.COBBLESTONE);
        throwaway.add(Items.DIRT);
        setCachedThrowaway(throwaway);
    }

    // ── Cached interface classes for obfuscation-safe reflection ──
    private static Class<?> iProvider;
    private static Class<?> iBaritone;
    private static Class<?> iCustomGoalProcess;
    private static Class<?> iPathingBehavior;
    private static Class<?> iGoal;

    // ── Cached Method + Object for hot-path calls (avoid getMethod()/forName() per tick) ──
    private static java.lang.reflect.Method mGetProvider;
    private static java.lang.reflect.Method mGetPrimaryBaritone;
    private static java.lang.reflect.Method mGetPathingBehavior;
    private static java.lang.reflect.Method mIsPathing;
    private static Class<?> cachedBaritoneApiClass;

    // ── Cached settings fields (MOAR PathWalker pattern) ──
    private static Object settingsInstance;
    private static java.lang.reflect.Field settingValueField;
    private static Object throwawayItemsSetting;
    private static Object allowPlaceSetting;
    private static Object allowParkourSetting;
    private static Object allowInventorySetting;
    private static Object maxFallHeightSetting;
    private static Object blockFreeLookSetting;
    private static boolean settingsCached = false;

    /** Public entry point for other classes to trigger settings cache. */
    public static void ensureSettingsCachePublic() { ensureSettingsCache(); }

    /** Get the cached maxFallHeightNoWater setting object (for direct setCachedSetting calls). */
    public static Object getMaxFallHeightSettingObj() {
        ensureSettingsCache();
        return maxFallHeightSetting;
    }

    /** Read back the current value of maxFallHeightNoWater for verification. */
    public static Object readMaxFallHeightValue() {
        try {
            ensureSettingsCache();
            if (maxFallHeightSetting != null && settingValueField != null) {
                return settingValueField.get(maxFallHeightSetting);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void ensureSettingsCache() {
        if (settingsCached) return;
        try {
            // Resolve directly — same path as primaryBaritone() which always works.
            // Do NOT use isBaritoneAvailable() — it has a separate cache that fails intermittently.
            ClassLoader cl = BaritoneInterface.class.getClassLoader();
            Class<?> api = Class.forName("baritone.api.BaritoneAPI", false, cl);
            settingsInstance = api.getMethod("getSettings").invoke(null);
            if (settingsInstance == null) return;

            // Boolean + numeric settings — these MUST work
            allowPlaceSetting = settingsInstance.getClass().getField("allowPlace").get(settingsInstance);
            allowParkourSetting = settingsInstance.getClass().getField("allowParkour").get(settingsInstance);
            allowInventorySetting = settingsInstance.getClass().getField("allowInventory").get(settingsInstance);
            try {
                maxFallHeightSetting = settingsInstance.getClass().getField("maxFallHeightNoWater").get(settingsInstance);
            } catch (Throwable ignored) {}
            try {
                blockFreeLookSetting = settingsInstance.getClass().getField("blockFreeLook").get(settingsInstance);
            } catch (Throwable ignored) {}
            settingValueField = allowPlaceSetting.getClass().getField("value");
            settingValueField.setAccessible(true);
            settingsCached = true; // Mark cached even if throwaway fails

            // Throwaway list — may fail on obfuscated builds, non-fatal
            try {
                throwawayItemsSetting = settingsInstance.getClass().getField("acceptableThrowawayItems").get(settingsInstance);
            } catch (Throwable t) {
                // Baritone defaults (dirt, cobblestone, netherrack, stone) still apply
            }
        } catch (Throwable e) {
            // Will retry next call
        }
    }

    /** Set a cached Baritone setting directly (MOAR pattern — no class resolution per call). */
    public static void setCachedSetting(Object settingObj, Object value) {
        try {
            ensureSettingsCache();
            if (settingValueField != null && settingObj != null) {
                settingValueField.set(settingObj, value);
            }
        } catch (Throwable ignored) {}
    }

    /** Set throwaway items via cached field (avoids ClassNotFoundException on obfuscated builds). */
    public static void setCachedThrowaway(java.util.List<net.minecraft.item.Item> items) {
        try {
            ensureSettingsCache();
            if (settingsCached && throwawayItemsSetting != null && settingValueField != null) {
                settingValueField.set(throwawayItemsSetting, items);
            } else {
                // Fallback to generic setSetting
                setSetting("acceptableThrowawayItems", items);
            }
        } catch (Throwable ignored) {}
    }

    private static void ensureInterfaces() throws Exception {
        if (iProvider != null) return;
        ClassLoader cl = BaritoneInterface.class.getClassLoader();
        iProvider = Class.forName("baritone.api.IBaritoneProvider", false, cl);
        iBaritone = Class.forName("baritone.api.IBaritone", false, cl);
        iCustomGoalProcess = Class.forName("baritone.api.process.ICustomGoalProcess", false, cl);
        iPathingBehavior = Class.forName("baritone.api.behavior.IPathingBehavior", false, cl);
        iGoal = Class.forName("baritone.api.pathing.goals.Goal", false, cl);
    }

    private static Object primaryBaritone() throws Exception {
        ensureInterfaces();
        // Cache all Method objects on first call — zero getMethod()/forName() on hot path
        if (mGetProvider == null) {
            ClassLoader cl = BaritoneInterface.class.getClassLoader();
            cachedBaritoneApiClass = Class.forName("baritone.api.BaritoneAPI", false, cl);
            mGetProvider = cachedBaritoneApiClass.getMethod("getProvider");
            mGetPrimaryBaritone = iProvider.getMethod("getPrimaryBaritone");
        }
        Object provider = mGetProvider.invoke(null);
        return mGetPrimaryBaritone.invoke(provider);
    }

    private static Object customGoalProcess() throws Exception {
        Object baritone = primaryBaritone();
        return iBaritone.getMethod("getCustomGoalProcess").invoke(baritone);
    }

    private static void setGoalAndPath(Object goalObj) throws Exception {
        Object proc = customGoalProcess();
        Method m = iCustomGoalProcess.getMethod("setGoalAndPath", iGoal);
        m.invoke(proc, goalObj);
    }

    private static void setGoalNull() throws Exception {
        Object proc = customGoalProcess();
        try {
            Method m = iCustomGoalProcess.getMethod("setGoal", iGoal);
            m.invoke(proc, new Object[]{null});
        } catch (NoSuchMethodException ignored) {
            Method m2 = iCustomGoalProcess.getMethod("setGoalAndPath", iGoal);
            m2.invoke(proc, new Object[]{null});
        }
    }

    private static Object getGoal() throws Exception {
        Object proc = customGoalProcess();
        return iCustomGoalProcess.getMethod("getGoal").invoke(proc);
    }

    private static void pathNow() throws Exception {
        Object proc = customGoalProcess();
        iCustomGoalProcess.getMethod("path").invoke(proc);
    }

    private static Object newGoalBlock(BlockPos pos) throws Exception {
        Class<?> goalBlock = Class.forName("baritone.api.pathing.goals.GoalBlock");
        Constructor<?> c = goalBlock.getConstructor(BlockPos.class);
        return c.newInstance(pos);
    }

    /** GoalGetToBlock — get ADJACENT to the block (within reach), not necessarily standing on it.
     *  This is what Meteor uses. Critical for goals inside solid blocks (like highway at y=120). */
    private static Object newGoalGetToBlock(BlockPos pos) throws Exception {
        Class<?> goalClass = Class.forName("baritone.api.pathing.goals.GoalGetToBlock");
        Constructor<?> c = goalClass.getConstructor(BlockPos.class);
        return c.newInstance(pos);
    }

    private static Object newGoalXZ(int x, int z) throws Exception {
        Class<?> goalXZ = Class.forName("baritone.api.pathing.goals.GoalXZ");
        Constructor<?> c = goalXZ.getConstructor(int.class, int.class);
        return c.newInstance(x, z);
    }

    private static Object newGoalNear(BlockPos pos, int range) throws Exception {
        Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
        Constructor<?> c = goalNear.getConstructor(BlockPos.class, int.class);
        return c.newInstance(pos, range);
    }

    /** Check if Baritone is actively pathing. Uses cached Method objects — no getMethod() per call. */
    public static boolean isBaritonePathing() {
        try {
            if (!isBaritoneAvailable()) return false;
            ensureInterfaces();
            // Cache Method objects on first call
            if (mGetPathingBehavior == null) {
                mGetPathingBehavior = iBaritone.getMethod("getPathingBehavior");
                mIsPathing = iPathingBehavior.getMethod("isPathing");
            }
            Object baritone = primaryBaritone();
            Object pathBehavior = mGetPathingBehavior.invoke(baritone);
            return (boolean) mIsPathing.invoke(pathBehavior);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Check if Baritone has a specific input forced (CLICK_LEFT, CLICK_RIGHT, SNEAK, JUMP, etc.) */
    public static boolean isInputForced(String inputName) {
        try {
            Object baritone = primaryBaritone();
            Object inputOverride = iBaritone.getMethod("getInputOverrideHandler").invoke(baritone);
            Class<?> inputEnum = Class.forName("baritone.api.utils.input.Input");
            Object input = Enum.valueOf((Class<Enum>) inputEnum, inputName);
            return (boolean) inputOverride.getClass().getMethod("isInputForcedDown", inputEnum)
                    .invoke(inputOverride, input);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void stop() {
        try {
            if (!isBaritoneAvailable()) return;
            setGoalNull();
            pathNow();
        } catch (Throwable ignored) {}
    }

    /** Cancel everything including elytra process. */
    public static void cancelAll() {
        try {
            if (!isBaritoneAvailable()) return;
            ensureInterfaces();
            Object baritone = primaryBaritone();
            Object pathBehavior = iBaritone.getMethod("getPathingBehavior").invoke(baritone);
            iPathingBehavior.getMethod("cancelEverything").invoke(pathBehavior);
        } catch (Throwable ignored) {}
        BaritoneState.needsPathingCheck = false;
        BaritoneState.pathing = false;
    }

    public static boolean isActive() {
        try {
            if (!isBaritoneAvailable()) return false;
            return getGoal() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Extract a BlockPos from the current Baritone goal, or null if not a block-based goal. */
    public static BlockPos getGoalBlockPos() {
        try {
            Object goal = getGoal();
            if (goal == null) return null;
            // GoalBlock and GoalGetToBlock have getGoalPos()
            try {
                Object pos = goal.getClass().getMethod("getGoalPos").invoke(goal);
                if (pos instanceof BlockPos bp) return bp;
            } catch (NoSuchMethodException ignored) {}
            // GoalNear: try getGoalPos too (it extends GoalGetToBlock in some versions)
            // Fallback: try x/y/z fields
            try {
                int x = (int) goal.getClass().getField("x").get(goal);
                int y = (int) goal.getClass().getField("y").get(goal);
                int z = (int) goal.getClass().getField("z").get(goal);
                return new BlockPos(x, y, z);
            } catch (NoSuchFieldException ignored) {}
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Get the block positions along Baritone's current path that are solid and need breaking.
     * Returns the next few movement destinations from the current executor position.
     * Uses INTERFACE-based method lookup (not concrete class) to work with obfuscated Baritone.
     * Returns empty list if no path or Baritone not active.
     */
    public static List<BlockPos> getCurrentPathBlocksToBreak() {
        List<BlockPos> result = new ArrayList<>();
        try {
            if (!isBaritoneAvailable()) return result;
            ClassLoader cl = BaritoneInterface.class.getClassLoader();

            // Load API interfaces (unobfuscated)
            Class<?> iPathingBehavior = Class.forName("baritone.api.behavior.IPathingBehavior", false, cl);
            Class<?> iPathExecutor = Class.forName("baritone.api.pathing.path.IPathExecutor", false, cl);
            Class<?> iPath = Class.forName("baritone.api.pathing.calc.IPath", false, cl);
            Class<?> iMovement = Class.forName("baritone.api.pathing.movement.IMovement", false, cl);

            Object baritone = primaryBaritone();
            Object pathBehavior = iBaritone.getMethod("getPathingBehavior").invoke(baritone);

            // Use INTERFACE methods (not concrete class methods) — works with obfuscated builds
            Object executor = iPathingBehavior.getMethod("getCurrent").invoke(pathBehavior);
            if (executor == null) {
                logPathDebug("executor=null");
                return result;
            }

            int pos = (int) iPathExecutor.getMethod("getPosition").invoke(executor);
            Object path = iPathExecutor.getMethod("getPath").invoke(executor);
            if (path == null) {
                logPathDebug("path=null pos=" + pos);
                return result;
            }

            @SuppressWarnings("unchecked")
            List<?> movements = (List<?>) iPath.getMethod("movements").invoke(path);
            if (movements == null || movements.isEmpty()) {
                logPathDebug("movements=" + (movements == null ? "null" : "empty") + " pos=" + pos);
                return result;
            }

            // Get the next 10 movements from current position
            MinecraftClient mc = MinecraftClient.getInstance();
            java.lang.reflect.Method getDest = iMovement.getMethod("getDest");
            java.lang.reflect.Method getSrc = iMovement.getMethod("getSrc");
            int checked = 0;

            for (int i = pos; i < Math.min(pos + 10, movements.size()); i++) {
                Object movement = movements.get(i);
                Object src = getSrc.invoke(movement);
                Object dest = getDest.invoke(movement);
                checked++;

                if (dest instanceof BlockPos destBp && mc.world != null) {
                    // Check dest position and above (2-tall player clearance)
                    if (!mc.world.getBlockState(destBp).isAir()) result.add(destBp);
                    BlockPos destUp = destBp.up();
                    if (!mc.world.getBlockState(destUp).isAir()) result.add(destUp);
                }
                if (src instanceof BlockPos srcBp && dest instanceof BlockPos destBp2 && mc.world != null) {
                    // If going UP (pillar), check block above dest
                    if (destBp2.getY() > srcBp.getY()) {
                        BlockPos aboveDest = destBp2.up();
                        if (!mc.world.getBlockState(aboveDest).isAir()) result.add(aboveDest);
                    }
                }
            }
            logPathDebug("pos=" + pos + " mvmts=" + movements.size() + " checked=" + checked + " solid=" + result.size());
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastPathErrorLog > 5000) {
                lastPathErrorLog = now;
                logPathDebug("EXCEPTION " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return result;
    }
    private static long lastPathErrorLog = 0;
    private static long lastPathDebugLog = 0;

    private static void logPathDebug(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastPathDebugLog < 3000) return; // throttle to every 3s
        lastPathDebugLog = now;
        try {
            // telemetry stripped
        } catch (Throwable ignored) {}
    }

    public static void stepRelative(MinecraftClient mc, Direction facing, boolean diagonal, int blocks) {
        if (mc == null || mc.player == null || blocks <= 0) return;
        try {
            if (!isBaritoneAvailable()) return;
            Direction back = facing.getOpposite();
            int dx = back.getOffsetX() * blocks;
            int dz = back.getOffsetZ() * blocks;
            if (diagonal) {
                Direction right = facing.rotateYClockwise();
                dx += right.getOffsetX() * blocks;
                dz += right.getOffsetZ() * blocks;
            }
            BlockPos target = mc.player.getBlockPos().add(dx, 0, dz);
            setGoalAndPath(newGoalBlock(target));
        } catch (Throwable ignored) {}
    }

    public static void walkBackXZ(MinecraftClient mc, Direction facing, int blocks) {
        if (mc == null || mc.player == null || blocks <= 0) return;
        try {
            if (!isBaritoneAvailable()) return;
            BlockPos start = mc.player.getBlockPos();
            Direction back = facing.getOpposite();
            int tx = start.getX() + back.getOffsetX() * blocks;
            int tz = start.getZ() + back.getOffsetZ() * blocks;
            setGoalAndPath(newGoalXZ(tx, tz));
        } catch (Throwable ignored) {}
    }

    public static void walkBackRightXZ(MinecraftClient mc, Direction facing, int blocks) {
        if (mc == null || mc.player == null || blocks <= 0) return;
        try {
            if (!isBaritoneAvailable()) return;
            BlockPos start = mc.player.getBlockPos();
            Direction back = facing.getOpposite();
            Direction right = facing.rotateYClockwise();
            int tx = start.getX() + (back.getOffsetX() + right.getOffsetX()) * blocks;
            int tz = start.getZ() + (back.getOffsetZ() + right.getOffsetZ()) * blocks;
            setGoalAndPath(newGoalXZ(tx, tz));
        } catch (Throwable ignored) {}
    }

    public static void setGoal(BlockPos pos) {
        try {
            if (!isBaritoneAvailable()) return;
            BaritoneState.needsPathingCheck = true;
            setGoalAndPath(newGoalBlock(pos));
        } catch (Throwable ignored) {}
    }

    /** Set goal to get ADJACENT to the block (within reach) — mirrors Meteor's moveTo behavior.
     *  Use this for goals inside solid blocks (e.g. highway at y=120). */
    public static void setGoalGetTo(BlockPos pos) {
        try {
            if (!isBaritoneAvailable()) return;
            BaritoneState.needsPathingCheck = true;
            setGoalAndPath(newGoalGetToBlock(pos));
        } catch (Throwable ignored) {}
    }

    public static void setGoalNear(BlockPos pos, int range) {
        try {
            if (!isBaritoneAvailable()) return;
            BaritoneState.needsPathingCheck = true;
            setGoalAndPath(newGoalNear(pos, range));
        } catch (Throwable ignored) {}
    }

    /** Set a 2D goal (x, z only — Baritone ignores Y). */
    public static void setGoalXZ(int x, int z) {
        try {
            if (!isBaritoneAvailable()) return;
            BaritoneState.needsPathingCheck = true;
            setGoalAndPath(newGoalXZ(x, z));
        } catch (Throwable ignored) {}
    }

    // ── Elytra Process ──

    // Elytra process interface — cached lazily since it may not exist in all Baritone versions
    private static Class<?> iElytraProcess;
    private static boolean elytraInterfaceChecked = false;

    private static Class<?> getElytraInterface() {
        if (!elytraInterfaceChecked) {
            elytraInterfaceChecked = true;
            try {
                iElytraProcess = Class.forName("baritone.api.process.IElytraProcess", false,
                        BaritoneInterface.class.getClassLoader());
            } catch (Throwable ignored) {}
        }
        return iElytraProcess;
    }

    private static Object elytraProcess() throws Exception {
        ensureInterfaces();
        Object baritone = primaryBaritone();
        // Try interface first, fall back to concrete class
        Class<?> elyIf = getElytraInterface();
        if (elyIf != null) {
            try {
                return iBaritone.getMethod("getElytraProcess").invoke(baritone);
            } catch (NoSuchMethodException ignored) {}
        }
        // Fallback for versions where IBaritone doesn't declare getElytraProcess
        return baritone.getClass().getMethod("getElytraProcess").invoke(baritone);
    }

    /** Start elytra flight to a block position. */
    public static boolean elytraPathTo(BlockPos pos) {
        try {
            if (!isBaritoneAvailable()) {
                return false;
            }
            Object proc = elytraProcess();
            if (proc == null) return false;
            // Clamp Y to valid nether range (1-126) to avoid
            // NetherPathfinder "Invalid y1 or y2" native crash
            BlockPos safePos = new BlockPos(pos.getX(),
                    Math.max(1, Math.min(126, pos.getY())),
                    pos.getZ());
            Class<?> elyIf = getElytraInterface();
            if (elyIf != null) {
                elyIf.getMethod("pathTo", BlockPos.class).invoke(proc, safePos);
            } else {
                proc.getClass().getMethod("pathTo", BlockPos.class).invoke(proc, safePos);
            }
            return true;
        } catch (Throwable t) {
            try {
                // telemetry stripped
            } catch (Throwable ignored) {}
            return false;
        }
    }

    /** Start elytra flight to a Goal object. */
    public static boolean elytraPathToGoal(Object goal) {
        try {
            if (!isBaritoneAvailable()) return false;
            Object proc = elytraProcess();
            if (proc == null) return false;
            Class<?> elyIf = getElytraInterface();
            if (elyIf != null) {
                elyIf.getMethod("pathTo", iGoal).invoke(proc, goal);
            } else {
                proc.getClass().getMethod("pathTo", iGoal).invoke(proc, goal);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Check if the elytra process is currently active. */
    public static boolean isElytraActive() {
        try {
            if (!isBaritoneAvailable()) return false;
            Object proc = elytraProcess();
            if (proc == null) return false;
            // IBaritoneProcess.isActive() — use that interface
            Class<?> iProcess = Class.forName("baritone.api.process.IBaritoneProcess", false,
                    BaritoneInterface.class.getClassLoader());
            return (boolean) iProcess.getMethod("isActive").invoke(proc);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Get the current elytra destination, or null. */
    public static BlockPos elytraCurrentDestination() {
        try {
            if (!isBaritoneAvailable()) return null;
            Object proc = elytraProcess();
            if (proc == null) return null;
            Class<?> elyIf = getElytraInterface();
            Object dest;
            if (elyIf != null) {
                dest = elyIf.getMethod("currentDestination").invoke(proc);
            } else {
                dest = proc.getClass().getMethod("currentDestination").invoke(proc);
            }
            return dest instanceof BlockPos bp ? bp : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Set a Baritone setting by name via reflection.
     *  Uses SettingsUtil.parseAndApply for string-serializable values (most reliable),
     *  falls back to direct field access for List types. */
    public static void setSetting(String name, Object value) {
        try {
            if (!isBaritoneAvailable()) return; // ensures cachedApiClass is set
            Object settings = cachedApiClass.getMethod("getSettings").invoke(null);
            if (settings == null) return;
            java.lang.reflect.Field field = settings.getClass().getField(name);
            Object setting = field.get(settings);

            if (value instanceof java.util.List) {
                // Lists can't be parsed from string — set directly
                setting.getClass().getField("value").set(setting, value);
            } else {
                // Use SettingsUtil.parseAndApply — this is how Baritone's #set command works
                // It properly handles type conversion and triggers any internal hooks
                try {
                    ClassLoader cl2 = cachedApiClass.getClassLoader();
                    Class<?> settingsUtil = Class.forName("baritone.api.utils.SettingsUtil", false, cl2);
                    Class<?> settingClass = Class.forName("baritone.api.Settings$Setting", false, cl2);
                    java.lang.reflect.Method parseAndApply = settingsUtil.getMethod("parseAndApply", settingClass, String.class);
                    parseAndApply.invoke(null, setting, String.valueOf(value));
                } catch (Throwable parseEx) {
                    // Fallback to direct field set
                    setting.getClass().getField("value").set(setting, value);
                }
            }
        } catch (Throwable e) {
            // removed stdout
            try {
                // telemetry stripped
            } catch (Throwable ignored) {}
        }
    }

    // ── Placement Mode (ported from MOAR's PathWalker.BaritoneDelegate) ──

    private static boolean savedAllowPlace;
    private static boolean savedAllowParkour;
    private static boolean savedAllowInventory;
    private static int savedMaxFallHeight;
    private static Object savedThrowawayItems;
    private static boolean placementSaved = false;

    /**
     * Enable Baritone pillar/bridge mode. Saves current settings and enables
     * allowPlace + allowParkour + allowInventory. Call restorePlacement() when done.
     * Mirrors MOAR's PathWalker.BaritoneDelegate.enablePlacement().
     * Uses cached fields to avoid ClassNotFoundException on obfuscated builds.
     */
    public static void enablePlacement() {
        try {
            ensureSettingsCache(); // resolves class directly, no isBaritoneAvailable() gate
            if (!settingsCached) {
                // Fallback to string-based setSetting for non-list types
                savedAllowPlace = getBoolSetting("allowPlace");
                savedAllowParkour = getBoolSetting("allowParkour");
                savedAllowInventory = getBoolSetting("allowInventory");
                savedMaxFallHeight = getIntSetting("maxFallHeightNoWater");
                placementSaved = true;
                setSetting("allowPlace", true);
                setSetting("allowParkour", true);
                setSetting("allowInventory", true);
                setSetting("maxFallHeightNoWater", 3);
                setSetting("blockFreeLook", true);
                try {
                    // telemetry stripped
                } catch (Throwable ignored) {}
                return;
            }

            // MOAR pattern: cached direct field access
            savedAllowPlace = (Boolean) settingValueField.get(allowPlaceSetting);
            savedAllowParkour = (Boolean) settingValueField.get(allowParkourSetting);
            savedAllowInventory = (Boolean) settingValueField.get(allowInventorySetting);
            if (throwawayItemsSetting != null) savedThrowawayItems = settingValueField.get(throwawayItemsSetting);
            if (maxFallHeightSetting != null) savedMaxFallHeight = (Integer) settingValueField.get(maxFallHeightSetting);
            placementSaved = true;

            settingValueField.set(allowPlaceSetting, true);
            settingValueField.set(allowParkourSetting, true);
            settingValueField.set(allowInventorySetting, true);
            if (maxFallHeightSetting != null) {
                settingValueField.set(maxFallHeightSetting, 2);
            }
            // blockFreeLook stays false (default) — Baritone forces client rotation for
            // break/place. This makes isReallyCloseTo() pass in Movement.prepared().
            // crosshairTarget is refreshed post-Baritone-rotation via ClientPlayerEntityMixin.

            // Verify + log ALL critical placement settings
            try {
                boolean ap = (Boolean) settingValueField.get(allowPlaceSetting);
                boolean ai = (Boolean) settingValueField.get(allowInventorySetting);
                Object mfh = maxFallHeightSetting != null ? settingValueField.get(maxFallHeightSetting) : "null";
                Object throwaway = throwawayItemsSetting != null ? "set" : "null";
                // telemetry stripped (ap, ai, mfh, throwaway suppressed)
                if (false) System.out.println(ap + " " + ai + " " + mfh + " " + throwaway);
            } catch (Throwable ignored) {}
        } catch (Throwable e) {
            // Non-fatal — placement features degraded
            try {
                // telemetry stripped
            } catch (Throwable ignored) {}
        }
    }

    /** Restore Baritone settings to pre-placement values. */
    public static void restorePlacement() {
        if (!placementSaved) return;
        try {
            ensureSettingsCache();
            if (settingsCached) {
                settingValueField.set(allowPlaceSetting, savedAllowPlace);
                settingValueField.set(allowParkourSetting, savedAllowParkour);
                settingValueField.set(allowInventorySetting, savedAllowInventory);
                if (savedThrowawayItems != null && throwawayItemsSetting != null) {
                    settingValueField.set(throwawayItemsSetting, savedThrowawayItems);
                }
                if (maxFallHeightSetting != null) {
                    settingValueField.set(maxFallHeightSetting, savedMaxFallHeight);
                }
                if (blockFreeLookSetting != null) {
                    settingValueField.set(blockFreeLookSetting, false); // restore default
                }
            } else {
                setSetting("allowPlace", savedAllowPlace);
                setSetting("allowParkour", savedAllowParkour);
                setSetting("allowInventory", savedAllowInventory);
            }
            placementSaved = false;
        } catch (Throwable e) {
            // Non-fatal
        }
    }

    public static boolean isPlacementEnabled() {
        return placementSaved;
    }

    /** Read a boolean Baritone setting. */
    private static boolean getBoolSetting(String name) {
        try {
            Object val = getRawSetting(name);
            return val instanceof Boolean b ? b : false;
        } catch (Throwable t) { return false; }
    }

    /** Read an int Baritone setting. */
    private static int getIntSetting(String name) {
        try {
            Object val = getRawSetting(name);
            return val instanceof Integer i ? i : 0;
        } catch (Throwable t) { return 0; }
    }

    /** Read a raw Baritone setting value via reflection. */
    private static Object getRawSetting(String name) {
        try {
            ClassLoader cl = BaritoneInterface.class.getClassLoader();
            Class<?> api = Class.forName("baritone.api.BaritoneAPI", false, cl);
            Object settings = api.getMethod("getSettings").invoke(null);
            java.lang.reflect.Field field = settings.getClass().getField(name);
            Object setting = field.get(settings);
            return setting.getClass().getField("value").get(setting);
        } catch (Throwable t) { return null; }
    }

    /** Write a raw value directly to a Baritone setting's value field. */
    private static void setRawSetting(String name, Object value) {
        try {
            ClassLoader cl = BaritoneInterface.class.getClassLoader();
            Class<?> api = Class.forName("baritone.api.BaritoneAPI", false, cl);
            Object settings = api.getMethod("getSettings").invoke(null);
            java.lang.reflect.Field field = settings.getClass().getField(name);
            Object setting = field.get(settings);
            setting.getClass().getField("value").set(setting, value);
        } catch (Throwable t) {
            // removed stdout
        }
    }

    public static List<BlockPos[]> getSelections() {
        List<BlockPos[]> result = new ArrayList<>();
        try {
            if (!isBaritoneAvailable()) return result;
            ensureInterfaces();
            Object baritone = primaryBaritone();
            Object selManager = iBaritone.getMethod("getSelectionManager").invoke(baritone);
            // ISelectionManager and ISelection are API interfaces
            ClassLoader cl = BaritoneInterface.class.getClassLoader();
            Class<?> iSelManager = Class.forName("baritone.api.selection.ISelectionManager", false, cl);
            Class<?> iSelection = Class.forName("baritone.api.selection.ISelection", false, cl);
            Object selectionsObj = iSelManager.getMethod("getSelections").invoke(selManager);
            if (selectionsObj == null) return result;
            Object[] selections = (Object[]) selectionsObj;
            for (Object sel : selections) {
                Object min = iSelection.getMethod("min").invoke(sel);
                Object max = iSelection.getMethod("max").invoke(sel);
                result.add(new BlockPos[]{(BlockPos) min, (BlockPos) max});
            }
        } catch (Throwable ignored) {}
        return result;
    }
}
