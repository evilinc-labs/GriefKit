package com.griefkit;
import com.griefkit.modules.Cross;
import com.griefkit.modules.Wither;
import com.griefkit.hud.WitherCounter;
import com.griefkit.hud.WitherPlacements;
import com.griefkit.managers.InventoryManager;
import com.griefkit.managers.PlacementManager;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import java.lang.invoke.MethodHandles;

public class GriefKit extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("GriefKit");
    public static final HudGroup HUD_GROUP = new HudGroup("GriefKit");
    public static final InventoryManager INVENTORY = new InventoryManager();
    public static final PlacementManager PLACEMENT = new PlacementManager();

    @Override
    public void onInitialize() {
        // Register lambda factory for ALL of your addon packages (modules, managers, hud, etc.)
        MeteorClient.EVENT_BUS.registerLambdaFactory("com.griefkit", (lookupInMethod, klass) -> {
            try {
                return (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup());
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        // Modules
        //Modules.get().add(new Wither());
        Modules.get().add(new Cross());

        // HUD
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
