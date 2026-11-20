package com.bilalutility;

import com.bilalutility.regear.RegearConfigScreen;
import com.bilalutility.tpa.TpaConfigScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** One settings entry point for the whole mod: pick which module to configure. */
public class BilalHubScreen extends Screen {
    private final Screen parent;

    public BilalHubScreen(Screen parent) {
        super(Text.literal("Bilal Utility"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        int y = height / 2 - 40;
        addDrawableChild(ButtonWidget.builder(Text.literal("TPAHere settings"),
                b -> client.setScreen(new TpaConfigScreen(this))).dimensions(x, y, 200, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Auto Regear settings"),
                b -> client.setScreen(new RegearConfigScreen(this))).dimensions(x, y + 24, 200, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 - 50, height - 28, 100, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Anchor Macro has no settings - rebind its key in Controls."),
                width / 2, y(), 0xFFAAAAAA);
    }

    private int y() {
        return height / 2 + 20;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
