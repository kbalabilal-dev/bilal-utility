package com.bilalutility.regear;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Pick a premade kit or one of your own saved kits. Every card shows the kit's icon, name and items. */
public class RegearKitScreen extends BilalScreen {
    private static final int CARD_H = 44;
    private static int lastTab = 0;

    private record Card(int x, int y, int w, RegearKits.Kit kit) {}

    private final List<Card> cards = new ArrayList<>();
    private int tab;
    private TextFieldWidget nameField;
    private ButtonWidget saveBtn;

    public RegearKitScreen(Screen parent) {
        super("Regear kits", "Click a kit to load it into the regear", 0x55FF55, parent);
        this.tab = lastTab;
    }

    @Override
    protected boolean hasDoneButton() {
        return false;
    }

    @Override
    protected void build() {
        lastTab = tab;
        cards.clear();
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int y = contentTop();

        int tw = (inner - 3) / 2;
        String[] tabs = {"Premade kits", "My kits (" + RegearKits.custom().size() + ")"};
        for (int i = 0; i < 2; i++) {
            int idx = i;
            ButtonWidget b = button(tabs[i], left + i * (tw + 3), y, tw, () -> {
                tab = idx;
                clearAndInit();
            }, null);
            b.active = i != tab;
        }

        int gridTop = y + 28;
        int cw = (inner - 4) / 2;
        List<RegearKits.Kit> kits = tab == 0 ? RegearKits.premade() : RegearKits.custom();
        for (int i = 0; i < kits.size(); i++) {
            RegearKits.Kit kit = kits.get(i);
            int cx = left + (i % 2) * (cw + 4);
            int cy = gridTop + (i / 2) * (CARD_H + 4);
            boolean custom = !kit.builtIn;
            int bw = custom ? cw - 16 : cw;
            buttonSized("", cx, cy, bw, CARD_H, () -> {
                RegearKits.apply(kit);
                if (client.player != null) client.player.sendMessage(Text.literal("Regear kit: " + kit.name), true);
            }, RegearKits.summary(kit));
            if (custom) {
                buttonSized("x", cx + cw - 14, cy, 14, CARD_H, () -> {
                    RegearKits.delete(kit.name);
                    clearAndInit();
                }, "Delete this kit");
            }
            cards.add(new Card(cx, cy, bw, kit));
        }
        if (tab == 1 && kits.isEmpty()) {
            centered("No saved kits yet.", gridTop + 10, 0xFFAAAAAA);
            centered("Set up your items, type a name below and press Save current.", gridTop + 24, 0xFF888899);
        }

        if (tab == 1) {
            int ry = height - 56;
            String old = nameField != null ? nameField.getText() : "";
            nameField = new TextFieldWidget(textRenderer, left, ry, inner - 110, 20, Text.literal("Kit name"));
            nameField.setMaxLength(RegearKits.NAME_MAX);
            nameField.setText(old);
            nameField.setChangedListener(s -> saveBtn.active = RegearKits.canSave(s));
            addDrawableChild(nameField);
            saveBtn = button("Save current", left + inner - 106, ry, 106, () -> {
                if (RegearKits.saveCurrent(nameField.getText())) {
                    nameField.setText("");
                    clearAndInit();
                }
            }, "Saves your current amounts, hotbar and offhand under this name (same name = overwrite)");
            saveBtn.active = RegearKits.canSave(old);
        } else {
            setHint("Premade kits only change what you carry. Shop slots stay as they are.");
        }

        int fy = height - 30;
        button("Edit items...", left, fy, 100, () -> client.setScreen(new RegearKitEditScreen(this)),
                "Amounts and shop slot of every item");
        button("Done", centerX() - 50, fy, 100, this::close, null);
        ButtonWidget buy = button("Buy now", left + inner - 100, fy, 100, () -> {
            client.setScreen(null);
            RegearRunner.start(client);
        }, "Close this screen and buy the loaded kit");
        buy.active = client != null && client.world != null;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        for (Card c : cards) {
            RegearKits.Kit k = c.kit();
            boolean active = k.name.equalsIgnoreCase(RegearConfig.activeKit);
            int color = active ? 0xFF55FF55 : 0xFFFFFFFF;
            ctx.drawItem(KitItem.values()[k.icon].item.getDefaultStack(), c.x() + 6, c.y() + 5);
            ctx.drawTextWithShadow(textRenderer, k.name, c.x() + 28, c.y() + 9, color);
            if (active) {
                ctx.drawTextWithShadow(textRenderer, "ACTIVE", c.x() + c.w() - 6 - textRenderer.getWidth("ACTIVE"),
                        c.y() + 9, 0xFF55FF55);
                box(ctx, c.x(), c.y(), c.x() + c.w(), c.y() + CARD_H, 0x2255FF55, 0xFF55FF55);
            }
            int ix = c.x() + 6;
            for (KitItem it : KitItem.values()) {
                if (k.amounts[it.ordinal()] <= 0) continue;
                ItemStack st = it.item.getDefaultStack();
                ctx.drawItem(st, ix, c.y() + 25);
                ix += 17;
            }
            if (k.fillTotems) {
                ctx.drawItem(KitItem.TOTEM.item.getDefaultStack(), ix, c.y() + 25);
            }
        }
    }
}
