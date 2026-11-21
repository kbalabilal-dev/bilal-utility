package com.bilalutility.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Shared look for every Bilal Utility screen: a dark panel in the middle, a coloured header with a
 * title and subtitle, section titles, a row layout with a name on the left and controls on the right,
 * and a footer with a hint line and a Done button.
 *
 * Subclasses only implement build() and add their widgets; the chrome (panel, header, labels) is drawn
 * behind the widgets in renderBackground, so nothing here changes how buttons or text boxes behave.
 */
public abstract class BilalScreen extends Screen {
    public static final int PANEL_W = 340;
    public static final int HEADER_H = 32;

    protected final Screen parent;
    protected final int accent;          // 0xRRGGBB colour of this module
    private final String subtitle;

    private record Label(String text, int x, int y, int color, boolean center) {}
    private record Dyn(Supplier<String> text, int x, int y, int w, int color) {}
    private record Rule(int x1, int x2, int y) {}

    private final List<Label> labels = new ArrayList<>();
    private final List<Dyn> dyn = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private String hint = "";

    protected BilalScreen(String title, String subtitle, int accent, Screen parent) {
        super(Text.literal(title));
        this.subtitle = subtitle;
        this.accent = accent & 0xFFFFFF;
        this.parent = parent;
    }

    // ---------- layout ----------

    protected final int panelW() {
        // Use the full logical viewport instead of a fixed 340px card. This prevents
        // controls and labels from being squeezed on small/mobile-sized game windows.
        return Math.max(1, width - 12);
    }

    protected final int panelLeft() {
        return width / 2 - panelW() / 2;
    }

    protected final int panelRight() {
        return panelLeft() + panelW();
    }

    /** First y inside the panel, below the header. */
    protected final int contentTop() {
        return 8 + HEADER_H + 8;
    }

    protected final int centerX() {
        return width / 2;
    }

    protected final void setHint(String text) {
        this.hint = text == null ? "" : text;
    }

    /** Implemented by each screen: add buttons / text boxes / labels. Called on every init. */
    protected abstract void build();

    @Override
    protected final void init() {
        labels.clear();
        dyn.clear();
        rules.clear();
        hint = "";
        build();
        if (hasDoneButton()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                    .dimensions(width / 2 - 50, height - 30, 100, 20).build());
        }
    }

    /** Screens with their own bottom row (page buttons, Close) return false. */
    protected boolean hasDoneButton() {
        return true;
    }

    @Override
    public void close() {
        saveAndLeave();
        if (client != null) client.setScreen(parent);
    }

    /** Called before the screen is left (save things here). */
    protected void saveAndLeave() {}

    // ---------- text on the panel ----------

    protected final void label(String text, int x, int y, int color) {
        labels.add(new Label(text, x, y, color, false));
    }

    protected final void centered(String text, int y, int color) {
        labels.add(new Label(text, width / 2, y, color, true));
    }

    /** Small coloured section title with a thin line to its right. */
    protected final void section(String text, int y) {
        int x = panelLeft() + 12;
        labels.add(new Label(text.toUpperCase(), x, y, 0xFF000000 | accent, false));
        int tw = textRenderer == null ? text.length() * 6 : textRenderer.getWidth(text.toUpperCase());
        rules.add(new Rule(x + tw + 6, panelRight() - 12, y + 4));
    }

    /** Text that is read again every frame (a value that changes when a button is pressed). */
    protected final void dynamic(Supplier<String> text, int x, int y, int w, int color) {
        dyn.add(new Dyn(text, x, y, w, color));
    }

    // ---------- widgets ----------

    private ButtonWidget put(ButtonWidget.Builder b, int x, int y, int w, int h, String tip) {
        b.dimensions(x, y, w, h);
        if (tip != null && !tip.isEmpty()) b.tooltip(Tooltip.of(Text.literal(tip)));
        return addDrawableChild(b.build());
    }

    protected final ButtonWidget button(String text, int x, int y, int w, Runnable action, String tip) {
        return put(ButtonWidget.builder(Text.literal(text), b -> action.run()), x, y, w, 20, tip);
    }

    /** A button with a custom height (used for cards and hotbar slots, whose contents are drawn on top). */
    protected final ButtonWidget buttonSized(String text, int x, int y, int w, int h, Runnable action, String tip) {
        return put(ButtonWidget.builder(Text.literal(text), b -> action.run()), x, y, w, h, tip);
    }

    protected static Text onOff(String name, boolean on) {
        return Text.literal(name + ": ")
                .append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    protected final ButtonWidget toggle(int x, int y, int w, String name, BooleanSupplier get,
                                        Consumer<Boolean> set, Runnable save, String tip) {
        return put(ButtonWidget.builder(onOff(name, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            save.run();
            b.setMessage(onOff(name, get.getAsBoolean()));
        }), x, y, w, 20, tip);
    }

    /** A button that shows "Name: value" and goes to the next value on every click. */
    protected final ButtonWidget cycle(int x, int y, int w, String name, int count, IntSupplier get,
                                       IntConsumer set, IntFunction<String> names, Runnable save, String tip) {
        return put(ButtonWidget.builder(Text.literal(name + ": " + names.apply(get.getAsInt())), b -> {
            set.accept((get.getAsInt() + 1) % count);
            save.run();
            b.setMessage(Text.literal(name + ": " + names.apply(get.getAsInt())));
        }), x, y, w, 20, tip);
    }

    /**
     * One row: the name on the left, [-big][-small] value [+small][+big] on the right.
     * Returns nothing; the value box is redrawn every frame from get/format.
     */
    protected final void stepper(int y, String name, IntSupplier get, IntConsumer set, int min, int max,
                                 int small, int big, IntFunction<String> format, Runnable save, String tip) {
        int right = panelRight() - 12;
        int bigW = 30, smallW = 22, valW = 70, gap = 2;
        int total = bigW + smallW + valW + smallW + bigW + gap * 4;
        int x0 = right - total;
        label(name, panelLeft() + 12, y + 6, 0xFFFFFFFF);

        int[] steps = {-big, -small, small, big};
        int[] widths = {bigW, smallW, smallW, bigW};
        int[] xs = {x0, x0 + bigW + gap, x0 + bigW + gap + smallW + gap + valW + gap,
                x0 + bigW + gap + smallW + gap + valW + gap + smallW + gap};
        for (int i = 0; i < 4; i++) {
            int d = steps[i];
            String t = d < 0 ? "-" + Math.abs(d) : "+" + d;
            if (Math.abs(d) < 10 && widths[i] < 30) t = d < 0 ? "-" : "+";
            put(ButtonWidget.builder(Text.literal(t), b -> {
                set.accept(Math.max(min, Math.min(max, get.getAsInt() + d)));
                save.run();
            }), xs[i], y, widths[i], 20, i == 1 ? tip : null);
        }
        dynamic(() -> format.apply(get.getAsInt()), x0 + bigW + gap + smallW + gap, y + 6, valW, 0xFFFFFFFF);
    }

    // ---------- drawing ----------

    /** A filled box with a 1 px border (colours are ARGB). */
    protected static void box(DrawContext ctx, int x1, int y1, int x2, int y2, int fill, int border) {
        ctx.fill(x1, y1, x2, y2, fill);
        ctx.fill(x1, y1, x2, y1 + 1, border);
        ctx.fill(x1, y2 - 1, x2, y2, border);
        ctx.fill(x1, y1, x1 + 1, y2, border);
        ctx.fill(x2 - 1, y1, x2, y2, border);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);

        int px = panelLeft();
        int pw = panelW();
        int py = 0;
        int ph = height;
        int solid = 0xFF000000 | accent;
        int dark = 0xFF000000 | ((accent >> 2) & 0x3F3F3F);

        box(ctx, px, py, px + pw, py + ph, 0xE0101018, 0xFF2A2A36);
        ctx.fill(px + 1, py + 1, px + pw - 1, py + HEADER_H, dark);
        ctx.fill(px + 1, py + HEADER_H, px + pw - 1, py + HEADER_H + 2, solid);
        ctx.fill(px + 1, py + 1, px + 4, py + HEADER_H, solid);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(title.getString()).formatted(Formatting.BOLD),
                width / 2, py + 7, 0xFFFFFFFF);
        if (!subtitle.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(subtitle), width / 2, py + 19, 0xFFBBBBBB);
        }

        for (Rule r : rules) {
            if (r.x2() > r.x1()) ctx.fill(r.x1(), r.y(), r.x2(), r.y() + 1, 0xFF3A3A48);
        }
        for (Label l : labels) {
            if (l.center()) {
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(l.text()), l.x(), l.y(), l.color());
            } else {
                ctx.drawTextWithShadow(textRenderer, l.text(), l.x(), l.y(), l.color());
            }
        }
        for (Dyn d : dyn) {
            String s = d.text().get();
            ctx.fill(d.x(), d.y() - 6, d.x() + d.w(), d.y() + 14, 0xFF07070B);
            ctx.drawTextWithShadow(textRenderer, s, d.x() + (d.w() - textRenderer.getWidth(s)) / 2, d.y(), d.color());
        }
        if (!hint.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(hint), width / 2, height - 44, 0xFF9A9AA8);
        }
    }
}
