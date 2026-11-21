package com.bilalutility.regear;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Amount and shop slot of every kit item, with the item icons. Everything typed is saved right away. */
public class RegearKitEditScreen extends BilalScreen {
    private static final int ROW_H = 22;

    private int rowsTop;

    public RegearKitEditScreen(Screen parent) {
        super("Kit items", "How many of each item to carry, and where it sits in the shop", 0x55FF55, parent);
    }

    @Override
    protected boolean hasDoneButton() {
        return false;
    }

    @Override
    protected void build() {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int y = contentTop();

        label("Shop slot", left + 108, y, 0xFF9A9AA8);
        label("Amount (0 = skip)", left + 190, y, 0xFF9A9AA8);
        rowsTop = y + 12;

        KitItem[] items = KitItem.values();
        TextFieldWidget[] amount = new TextFieldWidget[items.length];
        for (int i = 0; i < items.length; i++) {
            KitItem k = items[i];
            int idx = i;
            int ry = rowsTop + i * ROW_H;
            label(k.label, left + 24, ry + 6, 0xFFFFFFFF);

            TextFieldWidget slot = new TextFieldWidget(textRenderer, left + 108, ry, 40, 20, Text.literal(k.label + " slot"));
            slot.setMaxLength(2);
            slot.setText(String.valueOf(RegearConfig.kitSlot[idx]));
            slot.setChangedListener(s -> {
                RegearConfig.kitSlot[idx] = RegearConfig.parse(s, RegearConfig.kitSlot[idx], 0, 53);
                RegearConfig.save();
            });
            addDrawableChild(slot);

            int step = Math.max(1, Math.min(16, k.maxStack()));
            amount[idx] = new TextFieldWidget(textRenderer, left + 212, ry, 52, 20, Text.literal(k.label + " amount"));
            amount[idx].setMaxLength(4);
            amount[idx].setText(String.valueOf(RegearConfig.kitAmount[idx]));
            amount[idx].setChangedListener(s -> {
                RegearConfig.kitAmount[idx] = RegearConfig.parse(s, RegearConfig.kitAmount[idx], 0, 9999);
                RegearConfig.save();
            });
            addDrawableChild(amount[idx]);

            button("-", left + 188, ry, 20, () -> {
                int v = Math.max(0, RegearConfig.kitAmount[idx] - step);
                RegearConfig.kitAmount[idx] = v;
                amount[idx].setText(String.valueOf(v));
                RegearConfig.save();
            }, "-" + step);
            button("+", left + 268, ry, 20, () -> {
                int v = Math.min(9999, RegearConfig.kitAmount[idx] + step);
                RegearConfig.kitAmount[idx] = v;
                amount[idx].setText(String.valueOf(v));
                RegearConfig.save();
            }, "+" + step);
        }

        setHint("Slots count from 0: left to right, top to bottom (row 2, column 5 = 13)");

        int fy = height - 30;
        button("Hotbar...", left, fy, 100, () -> client.setScreen(new RegearSortScreen(this)),
                "What goes in the offhand and each hotbar slot");
        button("Done", centerX() - 50, fy, 100, this::close, null);
        button("Reset slots", left + inner - 100, fy, 100, () -> {
            for (KitItem k : KitItem.values()) RegearConfig.kitSlot[k.ordinal()] = k.defaultSlot;
            RegearConfig.save();
            clearAndInit();
        }, "Puts every shop slot back to the default");
    }

    @Override
    protected void saveAndLeave() {
        RegearConfig.save();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int left = panelLeft() + 12;
        KitItem[] items = KitItem.values();
        for (int i = 0; i < items.length; i++) {
            ctx.drawItem(items[i].item.getDefaultStack(), left + 2, rowsTop + i * ROW_H + 2);
        }
    }
}
