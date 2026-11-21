package com.bilalutility;

import com.bilalutility.anchor.AnchorConfigScreen;
import com.bilalutility.hud.HudConfig;
import com.bilalutility.hud.HudConfigScreen;
import com.bilalutility.regear.RegearConfig;
import com.bilalutility.regear.RegearConfigScreen;
import com.bilalutility.regear.RegearRunner;
import com.bilalutility.tpa.TpaConfig;
import com.bilalutility.tpa.TpaConfigScreen;
import com.bilalutility.tpa.TpaQueue;
import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.function.Supplier;

/** One settings entry point for the whole mod: a card per module with its icon and current status. */
public class BilalHubScreen extends BilalScreen {
    private static final int CARD_H = 56;

    private record Card(int x, int y, int w, Item icon, String title, Supplier<String> line1, Supplier<String> line2) {}

    private final java.util.List<Card> cards = new java.util.ArrayList<>();

    public BilalHubScreen(Screen parent) {
        super("Bilal Utility", "Pick a module to configure", 0xFFC84A, parent);
    }

    @Override
    protected void build() {
        cards.clear();
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int cw = (inner - 4) / 2;
        int y = contentTop() + 4;

        add(left, y, cw, Items.COMPASS, "HUD",
                () -> HudConfig.d.enabled ? "Enabled" : "Disabled",
                () -> "Scoreboard: " + (HudConfig.d.sbHidden ? "hidden" : HudConfig.d.sbMode == 1 ? "custom" : "server's"),
                () -> client.setScreen(new HudConfigScreen(this)));
        add(left + cw + 4, y, cw, Items.RESPAWN_ANCHOR, "Anchor Macro",
                () -> "Speed: " + AnchorConfigScreen.currentPreset(),
                () -> "One key, tap or hold",
                () -> client.setScreen(new AnchorConfigScreen(this)));
        y += CARD_H + 4;
        add(left, y, cw, Items.ENDER_PEARL, "TPAHere",
                () -> TpaQueue.isRunning() ? "Queue running" : "Idle",
                () -> TpaConfig.totalSent + " requests sent",
                () -> client.setScreen(new TpaConfigScreen(this)));
        add(left + cw + 4, y, cw, Items.TOTEM_OF_UNDYING, "Auto Regear",
                () -> RegearRunner.isRunning() ? "Running" : "Kit: " + (RegearConfig.activeKit.isEmpty() ? "custom" : RegearConfig.activeKit),
                () -> "Buys your kit from the shop",
                () -> client.setScreen(new RegearConfigScreen(this)));

        setHint("Keys: Options > Controls > Key Binds > Bilal Utility");
    }

    private void add(int x, int y, int w, Item icon, String title, Supplier<String> l1, Supplier<String> l2, Runnable open) {
        buttonSized("", x, y, w, CARD_H, open, null);
        cards.add(new Card(x, y, w, icon, title, l1, l2));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        for (Card c : cards) {
            ctx.drawItem(c.icon().getDefaultStack(), c.x() + 8, c.y() + 8);
            ctx.drawTextWithShadow(textRenderer, c.title(), c.x() + 30, c.y() + 12, 0xFFFFFFFF);
            ctx.drawTextWithShadow(textRenderer, c.line1().get(), c.x() + 8, c.y() + 30, 0xFFFFCC66);
            ctx.drawTextWithShadow(textRenderer, c.line2().get(), c.x() + 8, c.y() + 41, 0xFF9A9AA8);
        }
    }
}
