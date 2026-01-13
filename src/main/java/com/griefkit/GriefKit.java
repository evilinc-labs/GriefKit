package com.griefkit;

import com.griefkit.hud.WitherCounter;
import com.griefkit.hud.WitherPlacements;
import com.griefkit.managers.InventoryManager;
import com.griefkit.managers.PlacementManager;
import com.griefkit.modules.Cross;
import com.griefkit.modules.HighwayClogger;
import com.griefkit.modules.Wither;
import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandles;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class GriefKit extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("GriefKit");
    public static final HudGroup HUD_GROUP = new HudGroup("GriefKit");

    public static InventoryManager INVENTORY;
    public static PlacementManager PLACEMENT;

    @Override
    public void onInitialize() {
        // Required for Orbit (Meteor's event bus) on Java 16+ for addon packages.
        MeteorClient.EVENT_BUS.registerLambdaFactory("com.griefkit", (lookupInMethod, klass) -> {
            try {
                return (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup());
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        INVENTORY = new InventoryManager();
        PLACEMENT = new PlacementManager(INVENTORY);

        Modules.get().add(new Wither());
        Modules.get().add(new Cross());
        Modules.get().add(new HighwayClogger());

        Hud.get().register(WitherCounter.INFO);
        Hud.get().register(WitherPlacements.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.griefkit";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("leonetics", "griefkit");
    }
}
