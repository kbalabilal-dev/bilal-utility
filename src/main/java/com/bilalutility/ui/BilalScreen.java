package com.bilalutility.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.Widget;
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
    public static final int PANEL_W = 440;
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
    private int scrollOffset;
    private int maxScroll;
    private int scrollTop;
    private int scrollBottom;
    private boolean draggingScrollbar;
    private boolean scissorActive;

    protected BilalScreen(String title, String subtitle, int accent, Screen parent) {
        super(Text.literal(title));
        this.subtitle = subtitle;
        this.accent = accent & 0xFFFFFF;
        this.parent = parent;
    }

    // ---------- layout ----------

    protected final int panelW() {
        // Keep a readable, bounded settings panel. Stretching this to the entire game
        // window breaks screens that intentionally use compact control columns.
        return Math.max(1, Math.min(PANEL_W, width - 12));
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
        calculateScrollRange();
    }

    /** Screens with their own bottom row (page buttons, Close) return false. */
    protected boolean hasDoneButton() {
        return true;
    }

    private boolean isTopPinnedWidget(Widget widget) {
        return widget.getY() <= contentTop() + 1;
    }

    private boolean isBottomPinnedWidget(Widget widget) {
        int y = widget.getY();
        // Pin conventional bottom action rows; long forms and list entries still scroll.
        return Math.abs(y - (height - 30)) <= 2 || Math.abs(y - (height - 56)) <= 2
                || Math.abs(y - (height - 54)) <= 2;
    }

    private boolean isPinnedWidget(Widget widget) {
        return isTopPinnedWidget(widget) || isBottomPinnedWidget(widget);
    }

    private void calculateScrollRange() {
        scrollTop = contentTop() - 2;
        int footerTop = height - 30;
        int contentBottom = contentTop();

        for (Element element : children()) {
            if (element instanceof Widget widget) {
                if (isBottomPinnedWidget(widget)) {
                    footerTop = Math.min(footerTop, widget.getY());
                } else if (!isTopPinnedWidget(widget)) {
                    contentBottom = Math.max(contentBottom, widget.getY() + widget.getHeight());
                }
            }
        }
        for (Label label : labels) {
            if (label.y() > contentTop() + 6 && label.y() < height - 58) {
                contentBottom = Math.max(contentBottom, label.y() + 12);
            }
        }
        for (Dyn value : dyn) {
            if (value.y() > contentTop() + 6 && value.y() < height - 58) {
                contentBottom = Math.max(contentBottom, value.y() + 14);
            }
        }
        for (Rule rule : rules) {
            if (rule.y() > contentTop() + 6 && rule.y() < height - 58) {
                contentBottom = Math.max(contentBottom, rule.y() + 2);
            }
        }

        scrollBottom = Math.max(scrollTop + 36, footerTop - 6);
        maxScroll = Math.max(0, contentBottom - scrollBottom);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        if (scrollOffset > 0) {
            for (Element element : children()) {
                if (element instanceof Widget widget && !isPinnedWidget(widget)) {
                    widget.setY(widget.getY() - scrollOffset);
                }
            }
            shiftTextContent();
        }
    }

    private void shiftTextContent() {
        if (scrollOffset <= 0) return;
        for (int i = 0; i < labels.size(); i++) {
            Label l = labels.get(i);
            if (l.y() > contentTop() + 6 && l.y() < height - 58) {
                labels.set(i, new Label(l.text(), l.x(), l.y() - scrollOffset, l.color(), l.center()));
            }
        }
        for (int i = 0; i < dyn.size(); i++) {
            Dyn d = dyn.get(i);
            if (d.y() > contentTop() + 6 && d.y() < height - 58) {
                dyn.set(i, new Dyn(d.text(), d.x(), d.y() - scrollOffset, d.w(), d.color()));
            }
        }
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (r.y() > contentTop() + 6 && r.y() < height - 58) {
                rules.set(i, new Rule(r.x1(), r.x2(), r.y() - scrollOffset));
            }
        }
    }

    private void setScrollFromMouse(double mouseY) {
        int trackHeight = Math.max(1, scrollBottom - scrollTop);
        double fraction = Math.max(0.0, Math.min(1.0, (mouseY - scrollTop) / trackHeight));
        scrollOffset = (int) Math.round(fraction * maxScroll);
        clearAndInit();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll > 0 && mouseX >= panelLeft() && mouseX <= panelRight()
                && mouseY >= scrollTop && mouseY <= scrollBottom) {
            int next = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.round(verticalAmount * 24)));
            if (next != scrollOffset) {
                scrollOffset = next;
                clearAndInit();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int barX = panelRight() - 7;
        if (click.button() == 0 && mouseX >= barX - 4 && mouseX <= barX + 9
                && mouseY >= scrollTop && mouseY <= scrollBottom) {
            draggingScrollbar = true;
            if (maxScroll > 0) setScrollFromMouse(mouseY);
            return true;
        }
        if (maxScroll > 0 && mouseX >= panelLeft() && mouseX <= panelRight() && mouseY < scrollTop) {
            // Do not allow clipped/off-screen content widgets to receive clicks above the viewport.
            return true;
        }
        if (maxScroll > 0 && mouseX >= panelLeft() && mouseX <= panelRight()
                && mouseY > scrollBottom && mouseY < height - 4) {
            boolean overPinned = false;
            for (Element element : children()) {
                if (element instanceof Widget widget && isPinnedWidget(widget)
                        && mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
                        && mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight()) {
                    overPinned = true;
                    break;
                }
            }
            if (!overPinned) return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (draggingScrollbar && maxScroll > 0) {
            setScrollFromMouse(click.y());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingScrollbar = false;
        return super.mouseReleased(click);
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
            if ((r.y() <= contentTop() + 6 || (r.y() >= scrollTop && r.y() <= scrollBottom))
                    && r.x2() > r.x1()) {
                ctx.fill(r.x1(), r.y(), r.x2(), r.y() + 1, 0xFF3A3A48);
            }
        }
        for (Label l : labels) {
            if (l.y() > contentTop() + 6 && (l.y() + 10 < scrollTop || l.y() > scrollBottom)) continue;
            if (l.center()) {
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(l.text()), l.x(), l.y(), l.color());
            } else {
                ctx.drawTextWithShadow(textRenderer, l.text(), l.x(), l.y(), l.color());
            }
        }
        for (Dyn d : dyn) {
            if (d.y() > contentTop() + 6 && (d.y() + 14 < scrollTop || d.y() > scrollBottom)) continue;
            String s = d.text().get();
            ctx.fill(d.x(), d.y() - 6, d.x() + d.w(), d.y() + 14, 0xFF07070B);
            ctx.drawTextWithShadow(textRenderer, s, d.x() + (d.w() - textRenderer.getWidth(s)) / 2, d.y(), d.color());
        }
        if (!hint.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(hint), width / 2, height - 44, 0xFF9A9AA8);
        }

        // Clip child widgets to the content viewport so long forms never paint over
        // the footer. BilalScreen.render disables this scissor after children render.
        if (maxScroll > 0) {
            ctx.enableScissor(panelLeft() + 3, scrollTop, panelRight() - 3, scrollBottom);
            scissorActive = true;
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (scissorActive) {
            ctx.disableScissor();
            scissorActive = false;
        }

        // Keep a scrollbar visible on every Bilal Utility settings screen. When a
        // page fits, the thumb fills the track; when it overflows, the thumb shows
        // the current scroll position and can be dragged.
        int x = panelRight() - 7;
        int top = Math.max(2, scrollTop);
        int bottom = Math.max(top + 12, Math.min(height - 2, scrollBottom));
        int track = bottom - top;
        ctx.fill(x, top, x + 4, bottom, 0xFF30303B);
        int thumbHeight = maxScroll <= 0 ? track : Math.max(14, track * track / (track + maxScroll));
        int thumbY = maxScroll <= 0 ? top : top + (track - thumbHeight) * scrollOffset / maxScroll;
        int thumbColor = maxScroll <= 0 ? 0xFF666675 : (draggingScrollbar ? 0xFFFFD078 : (0xFF000000 | accent));
        ctx.fill(x, thumbY, x + 4, Math.min(bottom, thumbY + thumbHeight), thumbColor);
    }
}
