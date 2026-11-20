package com.bilalutility.hud;

import com.bilalutility.tpa.TpaCombat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws extra info right next to the hotbar: armor durability on one side, item counters
 * (totems, crystals, pearls, ...) and a few info lines (combat timer, ping, fps) on the other.
 * Everything is switched on or off in the HUD settings.
 */
public final class BilalHud {
    private static final int ROW_H = 18;
    private static final int CELL_W = 40;      // width of one counter cell
    private static final int HOTBAR_GAP = 124; // distance from screen center to the first element
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final Map<Item, ItemStack> ICONS = new HashMap<>();

    private BilalHud() {}

    private static ItemStack icon(Item item) {
        return ICONS.computeIfAbsent(item, Item::getDefaultStack);
    }

    private static int count(MinecraftClient mc, Item item) {
        int n = 0;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(item)) n += s.getCount();
        }
        ItemStack off = mc.player.getOffHandStack();
        if (off.isOf(item)) n += off.getCount();
        return n;
    }

    private static int durabilityColor(int pct) {
        if (pct > 60) return 0xFF55FF55;
        if (pct > 30) return 0xFFFFFF55;
        if (pct > 15) return 0xFFFFAA00;
        return 0xFFFF5555;
    }

    /** One icon + text. "nearSide" is the side of the screen the element sits on: true = left of the hotbar. */
    private static void entry(DrawContext ctx, TextRenderer tr, ItemStack stack, String text, int color,
                              int anchorX, int y, boolean leftOfHotbar) {
        int tw = tr.getWidth(text);
        if (leftOfHotbar) {
            // icon nearest the hotbar, text further out
            int iconX = anchorX - 16;
            ctx.drawItem(stack, iconX, y);
            ctx.drawTextWithShadow(tr, text, iconX - 3 - tw, y + 4, color);
        } else {
            ctx.drawItem(stack, anchorX, y);
            ctx.drawTextWithShadow(tr, text, anchorX + 19, y + 4, color);
        }
    }

    private static void line(DrawContext ctx, TextRenderer tr, String text, int color,
                             int anchorX, int y, boolean leftOfHotbar) {
        int x = leftOfHotbar ? anchorX - tr.getWidth(text) : anchorX;
        ctx.drawTextWithShadow(tr, text, x, y, color);
    }

    private record Counter(Item item, int count, int color) {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        HudConfig.Data c = HudConfig.d;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!c.enabled || mc.player == null || mc.options.hudHidden) return;

        TextRenderer tr = mc.textRenderer;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        int center = sw / 2;
        int leftAnchor = center - HOTBAR_GAP;   // right edge of everything on the left side
        int rightAnchor = center + HOTBAR_GAP;  // left edge of everything on the right side

        int armorAnchor = c.swapSides ? rightAnchor : leftAnchor;
        boolean armorLeft = !c.swapSides;
        int counterAnchor = c.swapSides ? leftAnchor : rightAnchor;
        boolean counterLeft = c.swapSides;

        // ---- armor durability: 4 fixed rows (helmet, chestplate, leggings, boots) ----
        if (c.armor) {
            int top = sh - 4 - ARMOR.length * ROW_H;
            for (int i = 0; i < ARMOR.length; i++) {
                ItemStack s = mc.player.getEquippedStack(ARMOR[i]);
                if (s.isEmpty()) continue;
                String text;
                int color;
                if (s.isDamageable() && s.getMaxDamage() > 0) {
                    int left = s.getMaxDamage() - s.getDamage();
                    int pct = Math.round(left * 100f / s.getMaxDamage());
                    text = c.armorText == 1 ? String.valueOf(left) : pct + "%";
                    color = durabilityColor(pct);
                } else {
                    text = "-";
                    color = 0xFFAAAAAA;
                }
                entry(ctx, tr, s, text, color, armorAnchor, top + i * ROW_H, armorLeft);
            }
        }

        // ---- item counters: a grid of 2 columns, bottom aligned ----
        List<Counter> counters = new ArrayList<>();
        addCounter(mc, counters, c.totems, Items.TOTEM_OF_UNDYING, true);
        addCounter(mc, counters, c.crystals, Items.END_CRYSTAL, false);
        addCounter(mc, counters, c.pearls, Items.ENDER_PEARL, false);
        addCounter(mc, counters, c.gapples, Items.GOLDEN_APPLE, false);
        addCounter(mc, counters, c.glowstone, Items.GLOWSTONE, false);
        addCounter(mc, counters, c.anchors, Items.RESPAWN_ANCHOR, false);
        addCounter(mc, counters, c.obsidian, Items.OBSIDIAN, false);
        addCounter(mc, counters, c.xp, Items.EXPERIENCE_BOTTLE, false);

        int rows = (counters.size() + 1) / 2;
        int gridTop = sh - 4 - rows * ROW_H;
        for (int k = 0; k < counters.size(); k++) {
            Counter ct = counters.get(k);
            int col = k % 2;
            int row = k / 2;
            int x = counterLeft ? counterAnchor - col * CELL_W : counterAnchor + col * CELL_W;
            entry(ctx, tr, icon(ct.item()), String.valueOf(ct.count()), ct.color(),
                    x, gridTop + row * ROW_H, counterLeft);
        }

        // ---- info lines, stacked above the counters ----
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (c.combat && TpaCombat.inCombat()) {
            lines.add("Combat " + TpaCombat.secondsLeft() + "s");
            colors.add(0xFFFF5555);
        }
        if (c.ping) {
            ClientPlayNetworkHandler nh = mc.getNetworkHandler();
            PlayerListEntry e = nh != null ? nh.getPlayerListEntry(mc.player.getGameProfile().name()) : null;
            if (e != null) {
                int ms = e.getLatency();
                lines.add("Ping " + ms + "ms");
                colors.add(ms < 80 ? 0xFF55FF55 : ms < 150 ? 0xFFFFFF55 : 0xFFFF5555);
            }
        }
        if (c.fps) {
            lines.add("FPS " + mc.getCurrentFps());
            colors.add(0xFFFFFFFF);
        }
        int y = (counters.isEmpty() ? sh - 4 : gridTop) - 2;
        for (int i = lines.size() - 1; i >= 0; i--) {
            y -= 11;
            line(ctx, tr, lines.get(i), colors.get(i), counterAnchor, y, counterLeft);
        }

        if (c.targetGear) renderTarget(ctx, mc, tr, sw, sh, c);
    }

    /** The player under your crosshair, otherwise (unless crosshair-only) the nearest one in range. */
    private static PlayerEntity findTarget(MinecraftClient mc, HudConfig.Data c) {
        if (mc.world == null) return null;
        if (mc.targetedEntity instanceof PlayerEntity p && p != mc.player) return p;
        if (c.targetCrosshairOnly) return null;
        PlayerEntity best = null;
        double bestD = (double) c.targetRange * c.targetRange;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Enemy gear panel. Shows only what the server already sends to every client: the 4 armor pieces
     * (with durability) and the items in the main hand and offhand. The rest of an inventory is never
     * sent to other players, so it cannot be shown.
     */
    private static void renderTarget(DrawContext ctx, MinecraftClient mc, TextRenderer tr,
                                     int sw, int sh, HudConfig.Data c) {
        PlayerEntity t = findTarget(mc, c);
        if (t == null) return;

        ItemStack[] stacks = {
                t.getEquippedStack(EquipmentSlot.HEAD), t.getEquippedStack(EquipmentSlot.CHEST),
                t.getEquippedStack(EquipmentSlot.LEGS), t.getEquippedStack(EquipmentSlot.FEET),
                t.getMainHandStack(), t.getOffHandStack()};
        int cell = 28;
        int w = cell * stacks.length;
        int h = 11 + 2 + 16 + 2 + 9;
        int x, y;
        switch (c.targetPos) {
            case 1 -> { x = 10; y = sh / 2 - h / 2; }
            case 2 -> { x = (sw - w) / 2; y = sh / 2 + 30; }
            default -> { x = sw - w - 10; y = sh / 2 - h / 2; }
        }

        ctx.fill(x - 4, y - 4, x + w + 4, y + h + 4, 0x88000000);

        float hp = t.getHealth() + t.getAbsorptionAmount();
        String title = t.getGameProfile().name() + "  " + String.format(Locale.ROOT, "%.1f", hp) + " hp  "
                + Math.round(mc.player.distanceTo(t)) + "m";
        int titleColor = hp > 14 ? 0xFF55FF55 : hp > 7 ? 0xFFFFFF55 : 0xFFFF5555;
        ctx.drawTextWithShadow(tr, title, x, y, titleColor);

        for (int i = 0; i < stacks.length; i++) {
            ItemStack s = stacks[i];
            if (s.isEmpty()) continue;
            int cx = x + i * cell;
            ctx.drawItem(s, cx + (cell - 16) / 2, y + 13);

            String text;
            int color = 0xFFFFFFFF;
            if (i < 4) { // armor: durability left
                if (s.isDamageable() && s.getMaxDamage() > 0) {
                    int pct = Math.round((s.getMaxDamage() - s.getDamage()) * 100f / s.getMaxDamage());
                    text = pct + "%";
                    color = durabilityColor(pct);
                } else {
                    text = "-";
                    color = 0xFFAAAAAA;
                }
            } else { // hands: stack size (a totem in the offhand shows as x1)
                text = "x" + s.getCount();
            }
            ctx.drawTextWithShadow(tr, text, cx + (cell - tr.getWidth(text)) / 2, y + 13 + 18, color);
        }
    }

    private static void addCounter(MinecraftClient mc, List<Counter> out, boolean on, Item item, boolean isTotem) {
        if (!on) return;
        int n = count(mc, item);
        if (n == 0 && !isTotem && HudConfig.d.hideZero) return;
        int color = 0xFFFFFFFF;
        if (isTotem) color = n == 0 ? 0xFFFF5555 : n <= 2 ? 0xFFFFFF55 : 0xFFFFFFFF;
        out.add(new Counter(item, n, color));
    }
}
