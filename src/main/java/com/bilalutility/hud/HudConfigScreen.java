package com.bilalutility.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class HudConfigScreen extends Screen {
    private final Screen parent;
    private int index = 0;

    public HudConfigScreen(Screen parent) {
        super(Text.literal("HUD settings"));
        this.parent = parent;
    }

    private static Text label(String name, boolean on) {
        return Text.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    private static Text armorTextLabel() {
        return Text.literal(HudConfig.d.armorText == 0 ? "Armor: percent" : "Armor: number");
    }

    private static Text targetModeLabel() {
        return Text.literal(HudConfig.d.targetCrosshairOnly ? "Target: crosshair" : "Target: nearest");
    }

    private static Text targetPosLabel() {
        String[] names = {"Panel: right", "Panel: left", "Panel: center"};
        return Text.literal(names[HudConfig.d.targetPos]);
    }

    @Override
    protected void init() {
        index = 0;
        HudConfig.Data c = HudConfig.d;

        toggle("HUD", () -> c.enabled, v -> c.enabled = v);
        toggle("Armor", () -> c.armor, v -> c.armor = v);
        addCell(ButtonWidget.builder(armorTextLabel(), b -> {
            c.armorText = c.armorText == 0 ? 1 : 0;
            HudConfig.save();
            b.setMessage(armorTextLabel());
        }));
        toggle("Swap sides", () -> c.swapSides, v -> c.swapSides = v);
        toggle("Hide zero", () -> c.hideZero, v -> c.hideZero = v);
        toggle("Combat", () -> c.combat, v -> c.combat = v);
        toggle("Totems", () -> c.totems, v -> c.totems = v);
        toggle("Crystals", () -> c.crystals, v -> c.crystals = v);
        toggle("Pearls", () -> c.pearls, v -> c.pearls = v);
        toggle("Gapples", () -> c.gapples, v -> c.gapples = v);
        toggle("Glowstone", () -> c.glowstone, v -> c.glowstone = v);
        toggle("Anchors", () -> c.anchors, v -> c.anchors = v);
        toggle("Obsidian", () -> c.obsidian, v -> c.obsidian = v);
        toggle("XP", () -> c.xp, v -> c.xp = v);
        toggle("Ping", () -> c.ping, v -> c.ping = v);
        toggle("FPS", () -> c.fps, v -> c.fps = v);
        toggle("Target gear", () -> c.targetGear, v -> c.targetGear = v);
        addCell(ButtonWidget.builder(targetModeLabel(), b -> {
            c.targetCrosshairOnly = !c.targetCrosshairOnly;
            HudConfig.save();
            b.setMessage(targetModeLabel());
        }));
        addCell(ButtonWidget.builder(targetPosLabel(), b -> {
            c.targetPos = (c.targetPos + 1) % 3;
            HudConfig.save();
            b.setMessage(targetPosLabel());
        }));

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 - 50, height - 28, 100, 20).build());
    }

    private void toggle(String name, BooleanSupplier get, Consumer<Boolean> set) {
        addCell(ButtonWidget.builder(label(name, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            HudConfig.save();
            b.setMessage(label(name, get.getAsBoolean()));
        }));
    }

    /** Places buttons in a grid of 3 columns. */
    private void addCell(ButtonWidget.Builder builder) {
        int colW = Math.min(100, (width - 16) / 3 - 4);
        int total = colW * 3 + 8;
        int x = width / 2 - total / 2 + (index % 3) * (colW + 4);
        int y = 28 + (index / 3) * 22;
        index++;
        addDrawableChild(builder.dimensions(x, y, colW, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Armor left of hotbar, counters right. Target gear = enemy armor + hands only."),
                width / 2, height - 46, 0xFFAAAAAA);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
