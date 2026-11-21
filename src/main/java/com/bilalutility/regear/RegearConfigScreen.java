package com.bilalutility.regear;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class RegearConfigScreen extends BilalScreen {
    private static final String[] TABS = {"Kit", "Shop", "Progress box"};
    private static final String[] HUD_NAMES = {"off", "top center", "top left", "top right"};
    private static int lastPage = 0;

    private int page;
    private int cardY;
    private ButtonWidget startBtn, pauseBtn;

    public RegearConfigScreen(Screen parent) {
        super("Auto Regear", "Buys your kit from the server shop", 0x55FF55, parent);
        this.page = lastPage;
    }

    private static String secs(int ticks) {
        return String.format(Locale.ROOT, "%.2f s", ticks / 20.0);
    }

    private static String startLabel() {
        return RegearRunner.isRunning() ? "Stop regear" : "Start regear";
    }

    private static String pauseLabel() {
        return RegearRunner.isPaused() ? "Resume" : "Pause";
    }

    @Override
    protected void build() {
        lastPage = page;
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int y = contentTop();

        int tw = (inner - 6) / 3;
        for (int i = 0; i < TABS.length; i++) {
            int idx = i;
            ButtonWidget b = button(TABS[i], left + i * (tw + 3), y, tw, () -> {
                page = idx;
                clearAndInit();
            }, null);
            b.active = i != page;
        }
        y += 28;
        if (page == 0) kitPage(y); else if (page == 1) shopPage(y); else hudPage(y);
    }

    private void kitPage(int y) {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int half = (inner - 4) / 2;

        cardY = y;   // the "current kit" card is drawn here (46 px high)
        y += 52;

        button("Choose a kit...", left, y, half, () -> client.setScreen(new RegearKitScreen(this)),
                "Premade kits and your saved kits, with icons");
        button("Edit items...", left + half + 4, y, half, () -> client.setScreen(new RegearKitEditScreen(this)),
                "Amount and shop slot of every item");
        y += 22;
        button("Hotbar & offhand...", left, y, half, () -> client.setScreen(new RegearSortScreen(this)),
                "Where the regear puts your items after buying");
        button("Sort only", left + half + 4, y, half, () -> {
            client.setScreen(null);
            RegearRunner.sortOnly(client);
        }, "Arrange hotbar and offhand now, without buying anything");

        y += 30;
        section("Run", y);
        y += 12;
        startBtn = button(startLabel(), left, y, half, () -> {
            if (RegearRunner.isRunning()) {
                RegearRunner.cancel(client);
                startBtn.setMessage(Text.literal(startLabel()));
                pauseBtn.active = false;
            } else {
                client.setScreen(null);
                RegearRunner.start(client);
            }
        }, null);
        startBtn.active = client != null && client.world != null;
        pauseBtn = button(pauseLabel(), left + half + 4, y, half, () -> {
            RegearRunner.togglePause(client);
            pauseBtn.setMessage(Text.literal(pauseLabel()));
        }, null);
        pauseBtn.active = RegearRunner.isRunning();

        y += 22;
        toggle(left, y, half, "Top-up", () -> RegearConfig.topUp, v -> RegearConfig.topUp = v, RegearConfig::save,
                "Only buy what is missing from your inventory");
        toggle(left + half + 4, y, half, "Fill totems", () -> RegearConfig.fillTotems,
                v -> RegearConfig.fillTotems = v, RegearConfig::save, "Buy totems last until every free slot is full");
    }

    private void shopPage(int y) {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int half = (inner - 4) / 2;

        section("Shop command (without the /)", y);
        y += 12;
        TextFieldWidget field = new TextFieldWidget(textRenderer, left, y, inner, 20, Text.literal("Shop command"));
        field.setMaxLength(40);
        field.setText(RegearConfig.command);
        field.setChangedListener(s -> {
            RegearConfig.command = RegearConfig.cleanCommand(s);
            RegearConfig.save();
        });
        addDrawableChild(field);

        y += 30;
        section("Timing", y);
        y += 12;
        stepper(y, "Click delay", () -> RegearConfig.clickDelayTicks, v -> RegearConfig.clickDelayTicks = v,
                1, 40, 1, 5, RegearConfigScreen::secs, RegearConfig::save, "Pause between two clicks in the shop");
        stepper(y + 22, "Wait time", () -> RegearConfig.waitTicks, v -> RegearConfig.waitTicks = v,
                10, 200, 5, 20, RegearConfigScreen::secs, RegearConfig::save, "How long to wait for a menu or purchase message");

        y += 52;
        section("Shop layout", y);
        y += 12;
        button("Menu layout...", left, y, half, () -> client.setScreen(layoutScreen()),
                "Slot numbers of the buttons in the shop menus");
        button("Messages...", left + half + 4, y, half, () -> client.setScreen(messagesScreen()),
                "Chat messages the shop sends");
        y += 22;
        toggle(left, y, half, "Windows hidden", () -> RegearConfig.hideWindow, v -> RegearConfig.hideWindow = v,
                RegearConfig::save, "Shop windows are clicked without being shown");
        toggle(left + half + 4, y, half, "Debug", () -> RegearConfig.debug, v -> RegearConfig.debug = v,
                RegearConfig::save, "Print every step in chat");
    }

    private void hudPage(int y) {
        int left = panelLeft() + 12;
        section("Progress box", y);
        y += 12;
        cycle(left, y, panelW() - 24, "Position", 4, () -> RegearConfig.hudPos, v -> RegearConfig.hudPos = v,
                v -> HUD_NAMES[v], RegearConfig::save, "Where the box shows while a regear runs");
        y += 30;
        stepper(y, "Size", () -> RegearConfig.hudScale, v -> RegearConfig.hudScale = v, 50, 200, 5, 25,
                v -> v + "%", RegearConfig::save, null);
        stepper(y + 22, "Move X", () -> RegearConfig.hudX, v -> RegearConfig.hudX = v, -600, 600, 1, 10,
                v -> v + " px", RegearConfig::save, null);
        stepper(y + 44, "Move Y", () -> RegearConfig.hudY, v -> RegearConfig.hudY = v, -600, 600, 1, 10,
                v -> v + " px", RegearConfig::save, null);
        button("Reset box", left, y + 76, panelW() - 24, () -> {
            RegearConfig.hudScale = 100;
            RegearConfig.hudX = 0;
            RegearConfig.hudY = 0;
            RegearConfig.save();
            clearAndInit();
        }, null);
    }

    private Screen layoutScreen() {
        List<RegearFieldsScreen.Row> rows = new ArrayList<>();
        rows.add(slotRow("Category slot", () -> RegearConfig.categorySlot, v -> RegearConfig.categorySlot = v));
        rows.add(slotRow("Item shown", () -> RegearConfig.displaySlot, v -> RegearConfig.displaySlot = v));
        rows.add(slotRow("Minus 64", () -> RegearConfig.minus64Slot, v -> RegearConfig.minus64Slot = v));
        rows.add(slotRow("Minus 10", () -> RegearConfig.minus10Slot, v -> RegearConfig.minus10Slot = v));
        rows.add(slotRow("Minus 1", () -> RegearConfig.minus1Slot, v -> RegearConfig.minus1Slot = v));
        rows.add(slotRow("Plus 1", () -> RegearConfig.plus1Slot, v -> RegearConfig.plus1Slot = v));
        rows.add(slotRow("Plus 10", () -> RegearConfig.plus10Slot, v -> RegearConfig.plus10Slot = v));
        rows.add(slotRow("Plus 64", () -> RegearConfig.plus64Slot, v -> RegearConfig.plus64Slot = v));
        rows.add(slotRow("Cancel", () -> RegearConfig.cancelSlot, v -> RegearConfig.cancelSlot = v));
        rows.add(slotRow("Confirm", () -> RegearConfig.confirmSlot, v -> RegearConfig.confirmSlot = v));
        return new RegearFieldsScreen("Menu layout", this, rows, 2,
                "Slots count from 0: left to right, top to bottom (row 2, column 5 = 13)");
    }

    private static RegearFieldsScreen.Row slotRow(String label, IntSupplier get, IntConsumer set) {
        return new RegearFieldsScreen.Row(label,
                () -> String.valueOf(get.getAsInt()),
                s -> set.accept(RegearConfig.parse(s, get.getAsInt(), 0, 53)));
    }

    private Screen messagesScreen() {
        List<RegearFieldsScreen.Row> rows = new ArrayList<>();
        rows.add(new RegearFieldsScreen.Row("Bought", () -> RegearConfig.msgBought,
                s -> RegearConfig.msgBought = s.isBlank() ? RegearConfig.msgBought : s));
        rows.add(new RegearFieldsScreen.Row("Inventory full", () -> RegearConfig.msgFull,
                s -> RegearConfig.msgFull = s.isBlank() ? RegearConfig.msgFull : s));
        rows.add(new RegearFieldsScreen.Row("No money", () -> RegearConfig.msgMoney,
                s -> RegearConfig.msgMoney = s.isBlank() ? RegearConfig.msgMoney : s));
        return new RegearFieldsScreen("Chat messages", this, rows, 1,
                "Part of the message is enough. Upper/lower case does not matter.");
    }

    @Override
    protected void saveAndLeave() {
        RegearConfig.save();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (page != 0) return;
        // the card for the kit that is set up right now
        int left = panelLeft() + 12;
        int w = panelW() - 24;
        box(ctx, left, cardY, left + w, cardY + 46, 0xFF15151E, 0xFF2F2F3E);
        String name = RegearConfig.activeKit.isEmpty() ? "Custom setup" : RegearConfig.activeKit;
        ctx.drawTextWithShadow(textRenderer, "Current kit", left + 8, cardY + 6, 0xFF9A9AA8);
        ctx.drawTextWithShadow(textRenderer, name, left + 8 + textRenderer.getWidth("Current kit ") , cardY + 6, 0xFF55FF55);
        int ix = left + 8;
        boolean any = false;
        for (KitItem it : KitItem.values()) {
            int n = RegearConfig.kitAmount[it.ordinal()];
            if (n <= 0) continue;
            any = true;
            ctx.drawItem(it.item.getDefaultStack(), ix, cardY + 22);
            String t = String.valueOf(n);
            ctx.drawTextWithShadow(textRenderer, t, ix + 8 - textRenderer.getWidth(t) / 2, cardY + 40 - 1, 0xFFCCCCCC);
            ix += 36;
        }
        if (!any) {
            ctx.drawTextWithShadow(textRenderer,
                    RegearConfig.fillTotems ? "Fills free slots with totems" : "Nothing to buy", left + 8, cardY + 26, 0xFFAAAAAA);
        }
    }
}
