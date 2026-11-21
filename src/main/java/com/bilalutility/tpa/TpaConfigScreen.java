package com.bilalutility.tpa;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Locale;

public class TpaConfigScreen extends BilalScreen {
    private static final String[] TABS = {"Timing", "Options", "Progress box"};
    private static final String[] HUD_NAMES = {"off", "top center", "top left", "top right"};
    private static int lastPage = 0;

    private int page;
    private ButtonWidget pauseBtn;

    public TpaConfigScreen(Screen parent) {
        super("TPAHere settings", "Queued /tpahere with combat detection", 0x3FA9FF, parent);
        this.page = lastPage;
    }

    private static String secs(int ticks) {
        return String.format(Locale.ROOT, "%.1f s", ticks / 20.0);
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
        if (page == 0) timing(y); else if (page == 1) options(y); else hudBox(y);
    }

    private void timing(int y) {
        section("Timing", y);
        y += 12;
        stepper(y, "Delay", () -> TpaConfig.delayTicks, v -> TpaConfig.delayTicks = v, 2, 100, 2, 10,
                TpaConfigScreen::secs, TpaConfig::save, "Pause between players");
        stepper(y + 22, "Skip wait", () -> TpaConfig.windowTimeoutTicks, v -> TpaConfig.windowTimeoutTicks = v,
                10, 100, 2, 10, TpaConfigScreen::secs, TpaConfig::save, "How long to wait for the confirm window");
        stepper(y + 44, "Round pause", () -> TpaConfig.roundPauseTicks, v -> TpaConfig.roundPauseTicks = v,
                10, 600, 10, 100, TpaConfigScreen::secs, TpaConfig::save, "Pause before a new round (auto mode)");
        stepper(y + 66, "Auto hit", () -> TpaConfig.autoHitDelayMs, v -> TpaConfig.autoHitDelayMs = v,
                50, 5000, 50, 100, v -> v + " ms", TpaConfig::save, "Right click once every X milliseconds");
        stepper(y + 88, "Max rounds", () -> TpaConfig.maxRounds, v -> TpaConfig.maxRounds = v,
                0, 999, 1, 5, v -> v == 0 ? "endless" : v + " rounds", TpaConfig::save,
                "Auto repeat stops after this many rounds");
    }

    private void options(int y) {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int half = (inner - 4) / 2;

        section("Command", y);
        y += 12;
        TextFieldWidget field = new TextFieldWidget(textRenderer, left, y, inner, 20, Text.literal("Command"));
        field.setMaxLength(40);
        field.setText(TpaConfig.command);
        field.setChangedListener(s -> {
            TpaConfig.command = TpaConfig.cleanCommand(s);
            TpaConfig.save();
        });
        addDrawableChild(field);
        dynamic(() -> "sends  /" + TpaConfig.command + " <player>", left, y + 30, inner, 0xFF9A9AA8);

        y += 50;
        section("Behaviour", y);
        y += 12;
        toggle(left, y, half, "Confirm window", () -> !TpaConfig.hideWindow, v -> TpaConfig.hideWindow = !v,
                TpaConfig::save, "ON = you see the confirm window, OFF = it is clicked without being shown");
        toggle(left + half + 4, y, half, "Combat pause", () -> TpaConfig.combatPause,
                v -> TpaConfig.combatPause = v, TpaConfig::save, "Hold the queue while 'Combat: Xs' shows");
        y += 22;
        button("Skip list (" + TpaConfig.skipCount() + ")", left, y, half,
                () -> client.setScreen(new TpaSkipScreen(this)), "Players that are never sent a request");
        pauseBtn = button(TpaQueue.isPaused() ? "Resume queue" : "Pause queue", left + half + 4, y, half, () -> {
            TpaQueue.togglePause(client);
            pauseBtn.setMessage(Text.literal(TpaQueue.isPaused() ? "Resume queue" : "Pause queue"));
        }, null);
        pauseBtn.active = TpaQueue.isRunning();

        dynamic(() -> "Requests sent, all time: " + TpaConfig.totalSent, left, y + 40, inner, 0xFFFFCC66);
    }

    private void hudBox(int y) {
        int left = panelLeft() + 12;
        section("Progress box", y);
        y += 12;
        cycle(left, y, panelW() - 24, "Position", 4, () -> TpaConfig.hudPos, v -> TpaConfig.hudPos = v,
                v -> HUD_NAMES[v], TpaConfig::save, "Where the box shows while a queue runs");
        y += 30;
        stepper(y, "Size", () -> TpaConfig.hudScale, v -> TpaConfig.hudScale = v, 50, 200, 5, 25,
                v -> v + "%", TpaConfig::save, null);
        stepper(y + 22, "Move X", () -> TpaConfig.hudX, v -> TpaConfig.hudX = v, -600, 600, 1, 10,
                v -> v + " px", TpaConfig::save, null);
        stepper(y + 44, "Move Y", () -> TpaConfig.hudY, v -> TpaConfig.hudY = v, -600, 600, 1, 10,
                v -> v + " px", TpaConfig::save, null);
        button("Reset box", left, y + 76, panelW() - 24, () -> {
            TpaConfig.hudScale = 100;
            TpaConfig.hudX = 0;
            TpaConfig.hudY = 0;
            TpaConfig.save();
            clearAndInit();
        }, null);
    }

    @Override
    protected void saveAndLeave() {
        TpaConfig.save();
    }
}
