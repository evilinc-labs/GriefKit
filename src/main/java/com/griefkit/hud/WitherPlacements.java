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
 */
package com.griefkit.hud;

import com.griefkit.GriefKit;
import com.griefkit.modules.Wither;
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

public class WitherPlacements
    extends HudElement {
    public static final HudElementInfo<WitherPlacements> INFO = new HudElementInfo(GriefKit.HUD_GROUP, "wither-placements", "Shows how many withers you have successfully placed this session.", WitherPlacements::new);
    private final SettingGroup sgGeneral;
    private double originalWidth;
    private double originalHeight;
    private boolean recalculateSize;
    public final Setting<Boolean> shadow;
    public final Setting<Integer> border;
    private final Setting<Double> textScale;
    private final Setting<Boolean> textShadow;
    private final Setting<SettingColor> textColor;

    public WitherPlacements() {
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
            return;
        }
        int count = Wither.getSuccessfulPlacements();
        String text = "Withers placed: " + count;
        renderer.text(text, (double)this.x, (double)this.y, (Color)this.textColor.get(), ((Boolean)this.textShadow.get()).booleanValue(), ((Double)this.textScale.get()).doubleValue());
        this.setSize(renderer.textWidth(text, ((Boolean)this.textShadow.get()).booleanValue(), ((Double)this.textScale.get()).doubleValue()), renderer.textHeight(((Boolean)this.textShadow.get()).booleanValue(), ((Double)this.textScale.get()).doubleValue()));
    }
}

