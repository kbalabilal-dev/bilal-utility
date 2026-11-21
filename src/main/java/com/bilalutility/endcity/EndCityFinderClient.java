package com.bilalutility.endcity;

import com.bilalutility.BilalKeys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class EndCityFinderClient implements ClientModInitializer {
    private static KeyBinding openKey;
    private static KeyBinding nextKey;
    private static KeyBinding clearKey;

    @Override
    public void onInitializeClient() {
        EndCityFinderConfig.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.endcityfinder.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, BilalKeys.CATEGORY));
        nextKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.endcityfinder.next", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, BilalKeys.CATEGORY));
        clearKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.endcityfinder.clear", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, BilalKeys.CATEGORY));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("bilal_utility", "end_city_target"), EndCityHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new EndCityFinderScreen(null));
                }
            }
            while (nextKey.wasPressed()) {
                selectNextCluster(client);
            }
            while (clearKey.wasPressed()) {
                EndCityFinderConfig.clearTarget();
                if (client.player != null) client.player.sendMessage(Text.literal("End City target cleared."), true);
            }
        });
    }

    private static void selectNextCluster(MinecraftClient client) {
        boolean inEnd = client.player != null && client.world != null
                && client.world.getRegistryKey().equals(World.END);
        double x = inEnd ? client.player.getX() : EndCityFinderConfig.centerX;
        double z = inEnd ? client.player.getZ() : EndCityFinderConfig.centerZ;
        List<EndCityCluster> clusters = EndCityScanner.clusters(EndCityFinderConfig.clusterSize, x, z);
        if (clusters.isEmpty()) {
            if (client.player != null) client.player.sendMessage(Text.literal("No ship-city clusters found yet."), true);
            return;
        }

        int next = 0;
        if (EndCityFinderConfig.hasTarget) {
            for (int i = 0; i < clusters.size(); i++) {
                EndCityCluster cluster = clusters.get(i);
                if (cluster.x() == EndCityFinderConfig.targetX && cluster.z() == EndCityFinderConfig.targetZ) {
                    next = (i + 1) % clusters.size();
                    break;
                }
            }
        }
        EndCityCluster target = clusters.get(next);
        EndCityFinderConfig.setTarget(target.x(), target.z());
        if (client.player != null) {
            client.player.sendMessage(Text.literal("Target " + (next + 1) + "/" + clusters.size()
                    + ": " + target.x() + ", " + target.z()), true);
        }
    }
}
