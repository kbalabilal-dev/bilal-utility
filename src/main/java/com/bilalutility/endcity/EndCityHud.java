package com.bilalutility.endcity;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.world.World;

public final class EndCityHud {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private EndCityHud() {}

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null
                || !client.world.getRegistryKey().equals(World.END)
                || !EndCityFinderConfig.hasTarget) return;

        double dx = EndCityFinderConfig.targetX - client.player.getX();
        double dz = EndCityFinderConfig.targetZ - client.player.getZ();
        double distance = Math.hypot(dx, dz);
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double relativeYaw = wrapDegrees(targetYaw - client.player.getYaw());
        int arrowIndex = Math.floorMod((int) Math.round(relativeYaw / 45.0), 8);
        String label = ARROWS[arrowIndex] + "  " + Math.round(distance) + " blocks";

        int x = context.getScaledWindowWidth() / 2;
        int y = 12;
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(label), x, y, 0xFFFFD36B);
    }

    private static double wrapDegrees(double value) {
        value %= 360.0;
        if (value >= 180.0) value -= 360.0;
        if (value < -180.0) value += 360.0;
        return value;
    }
}
