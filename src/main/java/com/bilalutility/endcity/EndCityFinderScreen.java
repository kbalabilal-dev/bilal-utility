package com.bilalutility.endcity;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Seed entry, scan controls, and ranked ship-city clusters. */
public class EndCityFinderScreen extends BilalScreen {
    private enum Page { SCAN, CLUSTERS }
    private Page page = Page.SCAN;
    private TextFieldWidget seedField;
    private TextFieldWidget radiusField;
    private TextFieldWidget ignoreField;
    private TextFieldWidget centerXField;
    private TextFieldWidget centerZField;
    private ButtonWidget scanButton;
    private ButtonWidget cancelButton;
    private ButtonWidget mapButton;
    private ButtonWidget nearestButton;
    private int listTop;
    private int listBottom;
    private int scroll;
    private int visibleRows;
    private List<EndCityCluster> shownClusters = new ArrayList<>();

    public EndCityFinderScreen(Screen parent) {
        super("End City Finder", "Find vanilla-generated End Ships from a world seed", 0xFFB84D, parent);
    }

    @Override
    protected boolean hasDoneButton() { return false; }

    @Override
    protected void build() {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int y = contentTop();
        int tabW = (inner - 4) / 2;

        ButtonWidget scanTab = button("Scan", left, y, tabW, () -> {
            page = Page.SCAN;
            clearAndInit();
        }, null);
        ButtonWidget clustersTab = button("Clusters", left + tabW + 4, y, tabW, () -> {
            page = Page.CLUSTERS;
            clearAndInit();
        }, null);
        scanTab.active = page != Page.SCAN;
        clustersTab.active = page != Page.CLUSTERS;

        y += 26;
        if (page == Page.SCAN) buildScan(left, inner, y);
        else buildClusters(left, inner, y);
        button("Done", width / 2 - 45, height - (height < 280 ? 24 : 28), 90, this::close, null);
    }

    private void buildScan(int left, int inner, int y) {
        label("World seed", left, y + 1, 0xFFFFFFFF);
        seedField = new TextFieldWidget(textRenderer, left, y + 10, inner, 20, Text.literal("Seed"));
        seedField.setMaxLength(32);
        seedField.setText(EndCityFinderConfig.seed);
        seedField.setPlaceholder(Text.literal("Enter the world seed"));
        seedField.setChangedListener(value -> {
            EndCityFinderConfig.seed = value.trim();
            EndCityFinderConfig.save();
        });
        addDrawableChild(seedField);

        boolean compact = height < 280;
        int rowGap = compact ? 25 : 32;
        y += rowGap;
        int half = (inner - 6) / 2;
        label("Scan radius", left, y, 0xFFFFFFFF);
        label("Ignore spawn radius", left + half + 6, y, 0xFFFFFFFF);
        radiusField = numericField(left, y + 9, half, "200000", EndCityFinderConfig.scanRadius,
                value -> EndCityFinderConfig.scanRadius = EndCityFinderConfig.clamp(value, 1_000, 400_000));
        ignoreField = numericField(left + half + 6, y + 9, half, "50000", EndCityFinderConfig.ignoreSpawnRadius,
                value -> EndCityFinderConfig.ignoreSpawnRadius = EndCityFinderConfig.clamp(value, 0, 400_000));

        y += rowGap;
        int centerFieldW = (inner - 78) / 2;
        int centerZX = left + centerFieldW + 6;
        int usePosX = centerZX + centerFieldW + 8;
        label("Center X", left, y, 0xFFFFFFFF);
        label("Center Z", centerZX, y, 0xFFFFFFFF);
        centerXField = numericField(left, y + 9, centerFieldW, "0", EndCityFinderConfig.centerX,
                value -> EndCityFinderConfig.centerX = EndCityFinderConfig.clamp(value, -30_000_000, 30_000_000));
        centerZField = numericField(centerZX, y + 9, centerFieldW, "0", EndCityFinderConfig.centerZ,
                value -> EndCityFinderConfig.centerZ = EndCityFinderConfig.clamp(value, -30_000_000, 30_000_000));
        button("Use pos", usePosX, y + 9, 64, this::useCurrentEndPosition, "Use your current End coordinates as the scan center");

        y += rowGap;
        int gap = 4;
        int buttonW = (inner - gap) / 2;
        int actionY = y + (compact ? 4 : 0);
        scanButton = button("Start scan", left, actionY, buttonW, () -> startScan(),
                "Uses vanilla's End City generator and structure templates");
        cancelButton = button("Stop scan", left + buttonW + gap, actionY, buttonW,
                EndCityScanner::cancel, null);
        scanButton.active = !EndCityScanner.isRunning();
        cancelButton.active = EndCityScanner.isRunning();

        dynamic(EndCityScanner::status, left, actionY + (compact ? 20 : 22), inner, 0xFFFFCC66);
        setHint("");
    }

    private void useCurrentEndPosition() {
        if (client.player == null || client.world == null
                || !client.world.getRegistryKey().equals(net.minecraft.world.World.END)) {
            if (client.player != null) client.player.sendMessage(Text.literal("Enter the End to use your current position."), true);
            return;
        }
        centerXField.setText(Integer.toString((int) Math.floor(client.player.getX())));
        centerZField.setText(Integer.toString((int) Math.floor(client.player.getZ())));
        EndCityFinderConfig.save();
    }

    private TextFieldWidget numericField(int x, int y, int w, String fallback, int initial,
                                         java.util.function.IntConsumer onValue) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, w, 20, Text.literal(fallback));
        field.setMaxLength(10);
        field.setText(Integer.toString(initial));
        field.setChangedListener(text -> {
            try {
                onValue.accept(Integer.parseInt(text.trim()));
                EndCityFinderConfig.save();
            } catch (NumberFormatException ignored) {
                // Leave the last valid setting in place while the user edits.
            }
        });
        addDrawableChild(field);
        return field;
    }

    private void startScan() {
        try {
            long seed = Long.parseLong(seedField.getText().trim());
            EndCityFinderConfig.seed = seedField.getText().trim();
            EndCityFinderConfig.scanRadius = EndCityFinderConfig.clamp(
                    Integer.parseInt(radiusField.getText().trim()), 1_000, 400_000);
            EndCityFinderConfig.ignoreSpawnRadius = EndCityFinderConfig.clamp(
                    Integer.parseInt(ignoreField.getText().trim()), 0, 400_000);
            EndCityFinderConfig.centerX = EndCityFinderConfig.clamp(Integer.parseInt(centerXField.getText().trim()), -30_000_000, 30_000_000);
            EndCityFinderConfig.centerZ = EndCityFinderConfig.clamp(Integer.parseInt(centerZField.getText().trim()), -30_000_000, 30_000_000);
            EndCityFinderConfig.save();
            boolean started = EndCityScanner.start(client, seed, EndCityFinderConfig.centerX, EndCityFinderConfig.centerZ,
                    EndCityFinderConfig.scanRadius, EndCityFinderConfig.ignoreSpawnRadius);
            if (!started && client.player != null) {
                client.player.sendMessage(Text.literal(EndCityScanner.status()), true);
            }
            clearAndInit();
        } catch (NumberFormatException exception) {
            if (client.player != null) client.player.sendMessage(Text.literal("Enter a valid numeric seed and settings."), true);
        }
    }

    private void buildClusters(int left, int inner, int y) {
        label("Cluster bucket size (blocks)", left, y, 0xFFFFFFFF);
        numericField(left, y + 10, inner, "2000", EndCityFinderConfig.clusterSize,
                value -> EndCityFinderConfig.clusterSize = EndCityFinderConfig.clamp(value, 500, 20_000));

        boolean inEnd = client != null && client.player != null && client.world != null
                && client.world.getRegistryKey().equals(net.minecraft.world.World.END);
        double fromX = inEnd ? client.player.getX() : EndCityFinderConfig.centerX;
        double fromZ = inEnd ? client.player.getZ() : EndCityFinderConfig.centerZ;
        shownClusters = EndCityScanner.clusters(EndCityFinderConfig.clusterSize, fromX, fromZ);

        listTop = y + 36;
        listBottom = height - 61;
        int rowHeight = 25;
        visibleRows = Math.max(1, (listBottom - listTop) / rowHeight);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shownClusters.size() - visibleRows)));

        if (shownClusters.isEmpty()) {
            centered(EndCityScanner.results().isEmpty() ? "No ship cities found yet." : "No clusters available.", listTop + 20, 0xFFAAAAAA);
            centered("Run a scan first.", listTop + 34, 0xFF888899);
        } else {
            int end = Math.min(shownClusters.size(), scroll + visibleRows);
            for (int i = scroll; i < end; i++) {
                EndCityCluster cluster = shownClusters.get(i);
                int rowY = listTop + (i - scroll) * rowHeight;
                buttonSized("", left, rowY, inner, 22, () -> selectCluster(cluster), null);
            }
        }

        int bottomY = height - 55;
        int half = (inner - 4) / 2;
        mapButton = button("Map", left, bottomY, half,
                () -> client.setScreen(new EndCityMapScreen(this)), null);
        nearestButton = button("Nearest 5 test", left + half + 4, bottomY, half,
                this::printNearestFive, null);
        mapButton.active = !EndCityScanner.results().isEmpty();
        nearestButton.active = !EndCityScanner.results().isEmpty();

        setHint("");
    }

    private void selectCluster(EndCityCluster cluster) {
        EndCityFinderConfig.setTarget(cluster.x(), cluster.z());
        if (client.player != null) {
            client.player.sendMessage(Text.literal("End City target set: " + cluster.x() + ", " + cluster.z()), true);
        }
    }

    private void printNearestFive() {
        if (client.player == null) return;
        boolean inEnd = client.world != null && client.world.getRegistryKey().equals(net.minecraft.world.World.END);
        double x = inEnd ? client.player.getX() : EndCityFinderConfig.centerX;
        double z = inEnd ? client.player.getZ() : EndCityFinderConfig.centerZ;
        List<EndCity> nearest = EndCityScanner.nearestFive(x, z);
        client.player.sendMessage(Text.literal("Nearest ship cities (X, Z):"), false);
        if (nearest.isEmpty()) {
            client.player.sendMessage(Text.literal("No results yet."), false);
            return;
        }
        for (int i = 0; i < nearest.size(); i++) {
            EndCity city = nearest.get(i);
            client.player.sendMessage(Text.literal((i + 1) + ". " + city.x() + ", " + city.z()), false);
        }
    }

    private static String formatDistance(double distance) {
        return String.format(java.util.Locale.ROOT, "%.1f km", distance / 1000.0);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (page == Page.CLUSTERS && mouseX >= panelLeft() && mouseX <= panelRight()
                && mouseY >= listTop && mouseY <= listBottom) {
            int max = Math.max(0, shownClusters.size() - visibleRows);
            int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(verticalAmount)));
            if (next != scroll) {
                scroll = next;
                clearAndInit();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (scanButton != null && cancelButton != null) {
            scanButton.active = !EndCityScanner.isRunning();
            cancelButton.active = EndCityScanner.isRunning();
        }
        if (mapButton != null && nearestButton != null) {
            boolean hasResults = !EndCityScanner.results().isEmpty();
            mapButton.active = hasResults;
            nearestButton.active = hasResults;
        }
        if (page == Page.SCAN) {
            int left = panelLeft() + 12;
            int inner = panelW() - 24;
            int y = contentTop() + 26 + 32 + 32 + 32 + 32 + 3;
            float progress = EndCityScanner.total() <= 0 ? 0f
                    : Math.min(1f, (float) EndCityScanner.scanned() / (float) EndCityScanner.total());
            ctx.fill(left, y, left + inner, y + 5, 0xFF292933);
            ctx.fill(left, y, left + Math.round(inner * progress), y + 5, 0xFFFFB84D);
        } else {
            int left = panelLeft() + 12;
            int end = Math.min(shownClusters.size(), scroll + visibleRows);
            for (int i = scroll; i < end; i++) {
                EndCityCluster cluster = shownClusters.get(i);
                int rowY = listTop + (i - scroll) * 25;
                String rowText = "#" + (i + 1) + "  " + cluster.count() + " ships  •  "
                        + cluster.x() + ", " + cluster.z() + "  •  " + formatDistance(cluster.distance());
                ctx.drawTextWithShadow(textRenderer, rowText, left + 5, rowY + 7, 0xFFFFFFFF);
            }
        }
        if (page == Page.CLUSTERS && shownClusters.size() > visibleRows) {
            int trackX = panelRight() - 7;
            int trackH = listBottom - listTop;
            ctx.fill(trackX, listTop, trackX + 5, listBottom, 0xFF343440);
            int thumbH = Math.max(12, trackH * visibleRows / shownClusters.size());
            int max = shownClusters.size() - visibleRows;
            int thumbY = listTop + (trackH - thumbH) * scroll / max;
            ctx.fill(trackX, thumbY, trackX + 5, thumbY + thumbH, 0xFFFFB84D);
        }
    }

    @Override
    protected void saveAndLeave() {
        EndCityFinderConfig.save();
    }
}
