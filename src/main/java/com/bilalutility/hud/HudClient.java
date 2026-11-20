package com.bilalutility.hud;

import com.bilalutility.BilalKeys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class HudClient implements ClientModInitializer {
    private static KeyBinding toggleKey;
    private static KeyBinding settingsKey;

    @Override
    public void onInitializeClient() {
        HudConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalhud.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, BilalKeys.CATEGORY));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalhud.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, BilalKeys.CATEGORY));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("bilal_utility", "bilal_hud"), BilalHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                HudConfig.d.enabled = !HudConfig.d.enabled;
                HudConfig.save();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(HudConfig.d.enabled ? "HUD: ON" : "HUD: OFF"), true);
                }
            }
            while (settingsKey.wasPressed()) {
                if (client.world != null && client.currentScreen == null) {
                    client.setScreen(new HudConfigScreen(null));
                }
            }
        });
    }
}
