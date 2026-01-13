/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  meteordevelopment.meteorclient.MeteorClient
 *  meteordevelopment.meteorclient.settings.BoolSetting$Builder
 *  meteordevelopment.meteorclient.settings.ColorSetting$Builder
 *  meteordevelopment.meteorclient.settings.DoubleSetting$Builder
 *  meteordevelopment.meteorclient.settings.IntSetting$Builder
 *  meteordevelopment.meteorclient.settings.Setting
 *  meteordevelopment.meteorclient.settings.SettingGroup
 *  meteordevelopment.meteorclient.systems.hud.HudElement
 *  meteordevelopment.meteorclient.systems.hud.HudElementInfo
 *  meteordevelopment.meteorclient.systems.hud.HudRenderer
 *  meteordevelopment.meteorclient.utils.render.color.Color
 *  meteordevelopment.meteorclient.utils.render.color.SettingColor
 *  net.minecraft.class_1297
 *  net.minecraft.class_1299
 */
package com.griefkit.hud;

import com.griefkit.GriefKit;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;

public class WitherCounter
    extends HudElement {
    public static final HudElementInfo<WitherCounter> INFO = new HudElementInfo(GriefKit.HUD_GROUP, "wither-counter", "Shows how many withers are in your chunk and in a 4x4 chunk area.", WitherCounter::new);
    private final SettingGroup sgGeneral;
    private double originalWidth;
    private double originalHeight;
    private boolean recalculateSize;
    public final Setting<Boolean> shadow;
    public final Setting<Integer> border;
    private final Setting<Double> textScale;
    private final Setting<Boolean> textShadow;
    private final Setting<SettingColor> textColor;

    public WitherCounter() {
        super(INFO);
        this.sgGeneral = this.settings.getDefaultGroup();

        this.shadow = this.sgGeneral.add(new BoolSetting.Builder()
            .name("shadow")
            .description("Renders shadow behind text.")
            .defaultValue(true)
            .onChanged(aBoolean -> this.recalculateSize = true)
            .build());
        this.border = this.sgGeneral.add(new IntSetting.Builder()
            .name("border")
            .description("How much space to add around the text.")
            .defaultValue(0)
            .onChanged(integer -> super.setSize(this.originalWidth + (double)(integer * 2), this.originalHeight + (double)(integer * 2)))
            .build());
        this.textScale = this.sgGeneral.add(new DoubleSetting.Builder()
            .name("text-scale")
            .description("Scale of the text.")
            .defaultValue(1.0)
            .min(0.1).sliderRange(0.1, 3.0)
            .build());
        this.textShadow = this.sgGeneral.add(new BoolSetting.Builder()
            .name("text-shadow")
            .description("Render shadow behind the text.")
            .defaultValue(true)
            .build());
        this.textColor = this.sgGeneral.add(new ColorSetting.Builder()
            .name("title-color")
            .description("Color for the text.")
            .defaultValue(new SettingColor(255, 255, 255, 255))
            .build());

    }

    public void render(HudRenderer renderer) {
        if (MeteorClient.mc.world == null || MeteorClient.mc.player == null) {
            if (this.isInEditor()) {
                String demoText = "Withers: 4 (4x4: 20)";
                renderer.text(demoText, (double)this.x, (double)this.y, (Color)this.textColor.get(), ((Boolean)this.textShadow.get()).booleanValue(), ((Double)this.textScale.get()).doubleValue());
                this.setSize(renderer.textWidth(demoText, ((Boolean)this.textShadow.get()).booleanValue(), ((Double)this.textScale.get()).doubleValue()), renderer.textHeight(((Boolean)this.textShadow.get()).booleanValue(), ((Double)this.textScale.get()).doubleValue()));
            }
            return;
        }
        int playerChunkX = MeteorClient.mc.player.getBlockX() >> 4;
        int playerChunkZ = MeteorClient.mc.player.getBlockZ() >> 4;
        int inPlayerChunk = 0;
        for (Entity e : MeteorClient.mc.world.getEntities()) {
            if (e.getType() != EntityType.WITHER) continue;
            int cx = e.getBlockX() >> 4;
            int cz = e.getBlockZ() >> 4;
            if (cx != playerChunkX || cz != playerChunkZ) continue;
            ++inPlayerChunk;
        }
        int bestAreaCount = 0;
        for (int minX = playerChunkX - 3; minX <= playerChunkX; ++minX) {
            for (int minZ = playerChunkZ - 3; minZ <= playerChunkZ; ++minZ) {
                int sumInThisGrid = 0;
                for (Entity e : MeteorClient.mc.world.getEntities()) {
                    boolean insideZ;
                    if (e.getType() != EntityType.WITHER) continue;
                    int cx = e.getBlockX() >> 4;
                    int cz = e.getBlockZ() >> 4;
                    boolean insideX = cx >= minX && cx <= minX + 3;
                    boolean bl = insideZ = cz >= minZ && cz <= minZ + 3;
                    if (!insideX || !insideZ) continue;
                    ++sumInThisGrid;
                }
                if (sumInThisGrid <= bestAreaCount) continue;
                bestAreaCount = sumInThisGrid;
            }
        }
        String text = String.format("Withers: %d (4x4: %d)", inPlayerChunk, bestAreaCount);
        renderer.text(text, (double)this.x, (double)this.y, (Color)this.textColor.get(), ((Boolean)this.textShadow.get()).booleanValue(), ((Double)this.textScale.get()).doubleValue());
        this.setSize(renderer.textWidth(text, ((Boolean)this.textShadow.get()).booleanValue(), ((Double)this.textScale.get()).doubleValue()), renderer.textHeight(((Boolean)this.textShadow.get()).booleanValue(), ((Double)this.textScale.get()).doubleValue()));
    }
}

