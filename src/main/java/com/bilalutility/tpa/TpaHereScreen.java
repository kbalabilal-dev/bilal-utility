package com.bilalutility.tpa;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TpaHereScreen extends BilalScreen {
    private static final int ROW_H = 24;
    private static final int RESERVED_SINGLE = 40;
    private static final int RESERVED_MULTI = 88;

    // Remembered between openings of the screen.
    private static boolean autoRepeat = false;

    private TextFieldWidget search;
    private ButtonWidget prev, next, multiBtn, sendBtn, selPageBtn, selAllBtn, selPinBtn, autoBtn, pinBtn;
    private final List<ButtonWidget> playerButtons = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private List<String> filtered = List.of();   // players matching the search
    private List<String> pageNames = List.of();  // players shown on this page
    private int page = 0;
    private int top;
    private boolean empty = false;
    private boolean multi = false;
    private boolean pinMode = false;

    public TpaHereScreen() {
        super("TPAHere", "Tap a player to send /" + TpaConfig.command, 0x3FA9FF, null);
    }

    @Override
    protected boolean hasDoneButton() {
        return false;
    }

    @Override
    protected void build() {
        playerButtons.clear();
        int left = panelLeft() + 12;
        int cx = centerX();
        int y = contentTop();
        top = y + 28;

        button("Settings", left, y, 60, () -> client.setScreen(new TpaConfigScreen(this)), "Open the TPAHere settings");

        String oldSearch = search != null ? search.getText() : "";
        search = new TextFieldWidget(textRenderer, left + 64, y, 126, 20, Text.literal("Search"));
        search.setText(oldSearch);
        search.setChangedListener(s -> { page = 0; rebuild(); });
        addDrawableChild(search);

        multiBtn = toggleButton(left + 194, y, 58, "Multi", () -> multi, () -> {
            multi = !multi;
            if (!multi) selected.clear();
            rebuild();
        }, "Select several players and send them as a queue");
        pinBtn = toggleButton(left + 256, y, 60, "Pin", () -> pinMode, () -> {
            pinMode = !pinMode;
            rebuild();
        }, "While ON, tapping a player pins or unpins them (pinned players are listed first)");

        selPageBtn = button("Select page", left + 1, height - 78, 100, () -> {
            List<String> l = selectable(pageNames);
            if (allSelected(l)) selected.removeAll(l); else selected.addAll(l);
            rebuild();
        }, null);
        selAllBtn = button("Select all", left + 108, height - 78, 100, () -> {
            List<String> l = selectable(filtered);
            if (allSelected(l)) selected.removeAll(l); else selected.addAll(l);
            rebuild();
        }, null);
        selPinBtn = button("Pinned", left + 215, height - 78, 100, () -> {
            for (String n : TpaConfig.pinnedList()) {
                if (!TpaConfig.isSkipped(n) && TpaQueue.onlineNames(client, "").contains(n)) selected.add(n);
            }
            rebuild();
        }, "Select every pinned player that is online");

        autoBtn = button(autoLabel(), left + 1, height - 54, 84, () -> {
            autoRepeat = !autoRepeat;
            autoBtn.setMessage(Text.literal(autoLabel()));
        }, "Start a new round when the queue ends");
        sendBtn = button(sendLabel(), left + 92, height - 54, 224, this::sendSelected, null);

        prev = button("<", cx - 104, height - 30, 44, () -> { page--; rebuild(); }, null);
        next = button(">", cx + 60, height - 30, 44, () -> { page++; rebuild(); }, null);
        button("Close", cx - 50, height - 30, 100, this::close, null);
        button(TpaAutoHit.isEnabled() ? "Auto hit: ON" : "Auto hit: OFF", panelRight() - 96, height - 30, 84, () -> {
            TpaAutoHit.toggle(client);
            clearAndInit();
        }, "Right click every few milliseconds (also restarts the last queue)");

        setInitialFocus(search);
        rebuild();
    }

    private ButtonWidget toggleButton(int x, int y, int w, String name, java.util.function.BooleanSupplier on,
                                      Runnable flip, String tip) {
        ButtonWidget[] ref = new ButtonWidget[1];
        ref[0] = button(label(name, on.getAsBoolean()), x, y, w, () -> {
            flip.run();
            ref[0].setMessage(Text.literal(label(name, on.getAsBoolean())));
        }, tip);
        return ref[0];
    }

    private static String label(String name, boolean on) {
        return name + (on ? ": ON" : ": OFF");
    }

    private String autoLabel() {
        return autoRepeat ? "Auto: ON" : "Auto: OFF";
    }

    private String sendLabel() {
        return "Send selected (" + selected.size() + ")";
    }

    private Text nameLabel(String name) {
        if (TpaConfig.isSkipped(name)) return Text.literal(name).formatted(Formatting.RED);
        if (selected.contains(name)) return Text.literal("\u2714 " + name).formatted(Formatting.GREEN);
        if (TpaConfig.isPinned(name)) return Text.literal("\u2605 " + name).formatted(Formatting.GOLD);
        return Text.literal(name);
    }

    /** Players that are not on the skip list. */
    private List<String> selectable(List<String> list) {
        return list.stream().filter(n -> !TpaConfig.isSkipped(n)).toList();
    }

    private boolean allSelected(List<String> list) {
        return !list.isEmpty() && selected.containsAll(list);
    }

    private void rebuild() {
        for (ButtonWidget b : playerButtons) remove(b);
        playerButtons.clear();

        selected.removeIf(TpaConfig::isSkipped);

        // pinned players first, then the rest, both sorted by name
        List<String> all = new ArrayList<>(TpaQueue.onlineNames(client, search.getText()));
        List<String> ordered = new ArrayList<>();
        for (String n : all) if (TpaConfig.isPinned(n)) ordered.add(n);
        for (String n : all) if (!TpaConfig.isPinned(n)) ordered.add(n);
        filtered = ordered;
        empty = filtered.isEmpty();

        int reserved = multi ? RESERVED_MULTI : RESERVED_SINGLE;
        int rows = Math.max(1, (height - top - reserved) / ROW_H);
        int perPage = rows * 3;
        int maxPage = Math.max(0, (filtered.size() - 1) / perPage);
        page = MathHelper.clamp(page, 0, maxPage);

        int x0 = panelLeft() + 13;
        int start = page * perPage;
        int end = Math.min(filtered.size(), start + perPage);
        pageNames = new ArrayList<>(filtered.subList(start, end));

        for (int i = start; i < end; i++) {
            String name = filtered.get(i);
            int idx = i - start;
            ButtonWidget b = ButtonWidget.builder(nameLabel(name), btn -> {
                        if (pinMode) {
                            TpaConfig.setPinned(name, !TpaConfig.isPinned(name));
                            TpaConfig.save();
                            rebuild();
                        } else if (multi) {
                            if (!selected.remove(name)) selected.add(name);
                            btn.setMessage(nameLabel(name));
                            updateButtons();
                        } else {
                            send(name);
                        }
                    })
                    .dimensions(x0 + (idx % 3) * 104, top + (idx / 3) * ROW_H, 102, 20).build();
            // Skip-list players can't be selected for the queue (but a single tap still sends).
            b.active = !(multi && !pinMode && TpaConfig.isSkipped(name));
            playerButtons.add(b);
            addDrawableChild(b);
        }
        prev.active = page > 0;
        next.active = page < maxPage;
        updateButtons();
    }

    private void updateButtons() {
        selPageBtn.visible = multi;
        selAllBtn.visible = multi;
        selPinBtn.visible = multi;
        autoBtn.visible = multi;
        sendBtn.visible = multi;

        List<String> selPage = selectable(pageNames);
        List<String> selAll = selectable(filtered);
        selPageBtn.setMessage(Text.literal(allSelected(selPage) ? "Unselect page" : "Select page"));
        selAllBtn.setMessage(Text.literal(allSelected(selAll) ? "Unselect all" : "Select all"));
        selPageBtn.active = !selPage.isEmpty();
        selAllBtn.active = !selAll.isEmpty();
        selPinBtn.active = !TpaConfig.pinnedList().isEmpty();
        sendBtn.setMessage(Text.literal(sendLabel()));
        sendBtn.active = !selected.isEmpty();
    }

    private void send(String name) {
        if (client != null && client.player != null) {
            client.player.networkHandler.sendChatCommand(TpaConfig.command + " " + name);
        }
        close();
    }

    private void sendSelected() {
        if (client == null || selected.isEmpty()) return;
        // If every online player (except the skip list) is selected, each round re-reads
        // the online list, so players who join later are included and players who left are dropped.
        List<String> online = TpaQueue.onlineSendable(client);
        boolean everyone = !online.isEmpty() && selected.containsAll(online);
        TpaQueue.start(client, new ArrayList<>(selected), autoRepeat, everyone);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        if (empty) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No players found"), width / 2, top + 10, 0xFFAAAAAA);
        }
    }
}
