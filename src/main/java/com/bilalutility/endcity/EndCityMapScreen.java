package com.bilalutility.endcity;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.List;

/** Lightweight pan/zoom map of cached ship-bearing End Cities and their clusters. */
public class EndCityMapScreen extends BilalScreen {
    private double centerX;
    private double centerZ;
    private double blocksPerPixel = 1_200.0;
    private int mapLeft;
    private int mapTop;
    private int mapRight;
    private int mapBottom;

    private boolean centerInitialized;

    public EndCityMapScreen(Screen parent) {
        super("Ship City Map", "Drag to pan • scroll to zoom • click a marker to set target", 0xFFB84D, parent);
        centerX = EndCityFinderConfig.centerX;
        centerZ = EndCityFinderConfig.centerZ;
    }

    @Override
    protected boolean hasDoneButton() {
        return false;
    }

    @Override
    protected void build() {
        if (!centerInitialized) {
            if (EndCityFinderConfig.hasTarget) {
                centerX = EndCityFinderConfig.targetX;
                centerZ = EndCityFinderConfig.targetZ;
            } else if (client != null && client.player != null && client.world != null
                    && client.world.getRegistryKey().equals(World.END)) {
                centerX = client.player.getX();
                centerZ = client.player.getZ();
            }
            centerInitialized = true;
        }
        mapLeft = panelLeft() + 12;
        mapRight = panelRight() - 12;
        mapTop = contentTop() + 3;
        mapBottom = height - 40;
        button("Back", width / 2 - 45, height - 28, 90, this::close, null);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        ctx.fill(mapLeft, mapTop, mapRight, mapBottom, 0xFF080B12);
        ctx.fill(mapLeft, mapTop, mapRight, mapTop + 1, 0xFF41414F);
        ctx.fill(mapLeft, mapBottom - 1, mapRight, mapBottom, 0xFF41414F);
        ctx.fill(mapLeft, mapTop, mapLeft + 1, mapBottom, 0xFF41414F);
        ctx.fill(mapRight - 1, mapTop, mapRight, mapBottom, 0xFF41414F);

        double pxPerBlock = 1.0 / blocksPerPixel;
        double halfW = (mapRight - mapLeft) / 2.0;
        double halfH = (mapBottom - mapTop) / 2.0;
        int grid = 64;
        for (int gx = mapLeft + grid; gx < mapRight; gx += grid) {
            ctx.fill(gx, mapTop + 1, gx + 1, mapBottom - 1, 0x182A405A);
        }
        for (int gy = mapTop + grid; gy < mapBottom; gy += grid) {
            ctx.fill(mapLeft + 1, gy, mapRight - 1, gy + 1, 0x182A405A);
        }

        boolean inEnd = client != null && client.player != null && client.world != null
                && client.world.getRegistryKey().equals(World.END);
        double fromX = inEnd ? client.player.getX() : centerX;
        double fromZ = inEnd ? client.player.getZ() : centerZ;
        List<EndCityCluster> clusters = EndCityScanner.clusters(EndCityFinderConfig.clusterSize, fromX, fromZ);

        for (EndCity city : EndCityScanner.results()) {
            int x = mapLeft + (int) Math.round(halfW + (city.x() - centerX) * pxPerBlock);
            int y = mapTop + (int) Math.round(halfH + (city.z() - centerZ) * pxPerBlock);
            if (x >= mapLeft + 2 && x < mapRight - 2 && y >= mapTop + 2 && y < mapBottom - 2) {
                ctx.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFD36B);
            }
        }

        for (EndCityCluster cluster : clusters) {
            int x = mapLeft + (int) Math.round(halfW + (cluster.x() - centerX) * pxPerBlock);
            int y = mapTop + (int) Math.round(halfH + (cluster.z() - centerZ) * pxPerBlock);
            if (x < mapLeft + 4 || x >= mapRight - 4 || y < mapTop + 4 || y >= mapBottom - 4) continue;
            int radius = cluster.count() >= 5 ? 5 : cluster.count() >= 2 ? 4 : 3;
            int color = cluster.count() >= 5 ? 0xFFFF8A4C : 0xFFFFD36B;
            ctx.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
        }

        if (EndCityFinderConfig.hasTarget) {
            int tx = mapLeft + (int) Math.round(halfW + (EndCityFinderConfig.targetX - centerX) * pxPerBlock);
            int tz = mapTop + (int) Math.round(halfH + (EndCityFinderConfig.targetZ - centerZ) * pxPerBlock);
            if (tx >= mapLeft + 6 && tx < mapRight - 6 && tz >= mapTop + 6 && tz < mapBottom - 6) {
                ctx.fill(tx - 6, tz - 1, tx + 7, tz + 2, 0xFF6CFF8F);
                ctx.fill(tx - 1, tz - 6, tx + 2, tz + 7, 0xFF6CFF8F);
            }
        }

        if (client != null && client.player != null && client.world != null
                && client.world.getRegistryKey().equals(World.END)) {
            int x = mapLeft + (int) Math.round(halfW + (client.player.getX() - centerX) * pxPerBlock);
            int y = mapTop + (int) Math.round(halfH + (client.player.getZ() - centerZ) * pxPerBlock);
            if (x >= mapLeft + 3 && x < mapRight - 3 && y >= mapTop + 3 && y < mapBottom - 3) {
                ctx.fill(x - 3, y - 3, x + 4, y + 4, 0xFF55D6FF);
            }
        }

        ctx.drawTextWithShadow(textRenderer, "Gold: ship  Orange: cluster  Green: target  Blue: me",
                mapLeft + 6, mapBottom - 12, 0xFFBFC6D3);
        ctx.drawTextWithShadow(textRenderer, String.format(java.util.Locale.ROOT, "Zoom: %.0f blocks / px", blocksPerPixel),
                mapLeft + 6, mapTop + 6, 0xFF9CA7B8);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() == 0 && insideMap(mouseX, mouseY)) {
            double halfW = (mapRight - mapLeft) / 2.0;
            double halfH = (mapBottom - mapTop) / 2.0;
            double worldX = centerX + (mouseX - mapLeft - halfW) * blocksPerPixel;
            double worldZ = centerZ + (mouseY - mapTop - halfH) * blocksPerPixel;

            boolean inEnd = client != null && client.player != null && client.world != null
                    && client.world.getRegistryKey().equals(World.END);
            double fromX = inEnd ? client.player.getX() : centerX;
            double fromZ = inEnd ? client.player.getZ() : centerZ;
            List<EndCityCluster> clusters = EndCityScanner.clusters(
                    EndCityFinderConfig.clusterSize, fromX, fromZ);
            EndCityCluster nearest = null;
            double nearestPixels = 10.0;
            for (EndCityCluster cluster : clusters) {
                double dx = (cluster.x() - worldX) / blocksPerPixel;
                double dz = (cluster.z() - worldZ) / blocksPerPixel;
                double distance = Math.hypot(dx, dz);
                if (distance < nearestPixels) {
                    nearestPixels = distance;
                    nearest = cluster;
                }
            }
            if (nearest != null) {
                EndCityFinderConfig.setTarget(nearest.x(), nearest.z());
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("End City target set: "
                            + nearest.x() + ", " + nearest.z()), true);
                }
            }
            return true; // Capture map clicks so dragging can pan even away from markers.
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (click.button() == 0 && insideMap(click.x(), click.y())) {
            centerX -= deltaX * blocksPerPixel;
            centerZ -= deltaY * blocksPerPixel;
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (insideMap(mouseX, mouseY)) {
            blocksPerPixel = Math.max(250.0, Math.min(8_000.0,
                    blocksPerPixel * (verticalAmount > 0 ? 0.8 : 1.25)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private boolean insideMap(double x, double y) {
        return x >= mapLeft && x <= mapRight && y >= mapTop && y <= mapBottom;
    }
}
