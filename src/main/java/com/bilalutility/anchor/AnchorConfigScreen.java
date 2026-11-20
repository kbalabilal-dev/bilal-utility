package com.bilalutility.anchor;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class AnchorConfigScreen extends Screen {
    private final Screen parent;

    public AnchorConfigScreen(Screen parent) {
        super(Text.literal("Anchor Macro settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x0 = width / 2 - 100;
        int y = 30;

        // Speed presets: set all four values in one click.
        addPreset(x0, y, "Safe", 2, 3, 6, 14);
        addPreset(x0 + 68, y, "Normal", 1, 2, 4, 10);
        addPreset(x0 + 136, y, "Fast", 0, 1, 2, 8);

        y += 30;
        addStepper(y, "Step delay", () -> AnchorConfig.stepDelayTicks, v -> AnchorConfig.stepDelayTicks = v, 0, 20);
        addStepper(y + 24, "Place wait", () -> AnchorConfig.placeWaitTicks, v -> AnchorConfig.placeWaitTicks = v, 0, 20);
        addStepper(y + 48, "Chain gap", () -> AnchorConfig.chainGapTicks, v -> AnchorConfig.chainGapTicks = v, 0, 40);
        addStepper(y + 72, "Max wait", () -> AnchorConfig.maxWaitTicks, v -> AnchorConfig.maxWaitTicks = v, 3, 60);

        addDrawableChild(ButtonWidget.builder(offhandLabel(), b -> {
                    AnchorConfig.requireOffhandTotem = !AnchorConfig.requireOffhandTotem;
                    AnchorConfig.save();
                    b.setMessage(offhandLabel());
                }).dimensions(x0, y + 104, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 - 50, height - 28, 100, 20).build());
    }

    private static Text offhandLabel() {
        return Text.literal(AnchorConfig.requireOffhandTotem
                ? "Need offhand totem to blow: ON" : "Need offhand totem to blow: OFF");
    }

    private void addPreset(int x, int y, String name, int step, int place, int chain, int maxWait) {
        addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> {
                    AnchorConfig.applyPreset(step, place, chain, maxWait);
                    client.setScreen(new AnchorConfigScreen(parent));
                }).dimensions(x, y, 64, 20).build());
    }

    private void addStepper(int y, String name, IntSupplier get, IntConsumer set, int min, int max) {
        int x0 = width / 2 - 100;
        ButtonWidget label = ButtonWidget.builder(Text.literal(name + ": " + get.getAsInt() + " ticks"), b -> { })
                .dimensions(x0 + 22, y, 156, 20).build();
        label.active = false;
        int[] steps = {-1, 1};
        int[] xs = {x0, x0 + 180};
        for (int i = 0; i < 2; i++) {
            int d = steps[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(d < 0 ? "-" : "+"), b -> {
                        set.accept(MathHelper.clamp(get.getAsInt() + d, min, max));
                        AnchorConfig.save();
                        label.setMessage(Text.literal(name + ": " + get.getAsInt() + " ticks"));
                    }).dimensions(xs[i], y, 20, 20).build());
        }
        addDrawableChild(label);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("20 ticks = 1 second. Lower = faster, but too low can fail on laggy servers."),
                width / 2, height - 46, 0xFFAAAAAA);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
