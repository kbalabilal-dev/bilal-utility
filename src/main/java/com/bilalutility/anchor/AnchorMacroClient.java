package com.bilalutility.anchor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

/**
 * One key: place a respawn anchor on the block you look at, charge it with glowstone,
 * then detonate it while holding a totem of undying.
 *
 * - Tap the key = one anchor. Hold the key = keeps going with the next anchor.
 * - Before every explosion it checks that you hold a totem. If the last one popped, it takes
 *   another totem from your hotbar, or from your inventory into an empty hotbar slot.
 * - If you look at an anchor that is already placed, it only charges and detonates that one.
 *
 * Needs in your hotbar: respawn anchor(s), glowstone, and totem(s) (totems may also be in your
 * inventory, but then keep one hotbar slot empty). The explosion is strong and hurts you too.
 */
public class AnchorMacroClient implements ClientModInitializer {
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("bilal_utility", "main"));
    private static final int STEP_DELAY = 1;   // ticks between steps (20 ticks = 1 second)
    private static final int CHAIN_GAP = 4;    // ticks between two anchors while the key is held
    private static final int MAX_WAIT = 10;    // ticks to wait for the server before giving up

    private static KeyBinding runKey;

    // -1 = idle, 0 = place, 1 = charge, 2 = detonate, 3 = finish
    private static int step = -1;
    private static int wait, timeout;
    private static int chainWait = -1;
    private static int origSlot, anchorSlot, glowSlot;
    private static int totemSlot = -1; // hotbar slot of the totem used last (-1 = none yet)
    private static BlockHitResult placeHit;
    private static BlockPos anchorPos;

    @Override
    public void onInitializeClient() {
        runKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.anchormacro.run", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(AnchorMacroClient::tick);
    }

    private static void msg(MinecraftClient client, String text) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), true);
    }

    private static int findSlot(PlayerInventory inv, Item item) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static int countTotems(PlayerInventory inv) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) n++;
        }
        return n;
    }

    /**
     * Returns a hotbar slot that holds a totem. Uses one already in the hotbar, otherwise moves
     * one from the inventory into an empty hotbar slot. Returns -1 if that is not possible.
     */
    private static int prepareTotem(MinecraftClient client, int preferred) {
        PlayerInventory inv = client.player.getInventory();

        if (preferred >= 0 && inv.getStack(preferred).isOf(Items.TOTEM_OF_UNDYING)) return preferred;

        int slot = findSlot(inv, Items.TOTEM_OF_UNDYING);
        if (slot >= 0) {
            if (preferred >= 0) {
                msg(client, "Totem popped, next one ready (" + (countTotems(inv) - 1) + " left)");
            }
            return slot;
        }

        int from = -1;
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            msg(client, "Anchor macro: no totem left");
            return -1;
        }
        int target = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            msg(client, "Anchor macro: keep one hotbar slot empty for the totem");
            return -1;
        }
        // Same as hovering the totem in your inventory and pressing the number key of the empty slot.
        client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, from, target,
                SlotActionType.SWAP, client.player);
        msg(client, "Totem popped, replaced from inventory (" + (countTotems(inv) - 1) + " left)");
        return target;
    }

    private static void select(MinecraftClient client, int slot) {
        if (slot >= 0) client.player.getInventory().setSelectedSlot(slot);
    }

    private static void interactAt(MinecraftClient client, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private static BlockPos anchorAt(MinecraftClient client, BlockHitResult hit) {
        BlockPos clicked = hit.getBlockPos();
        BlockPos adjacent = clicked.offset(hit.getSide());
        if (client.world.getBlockState(adjacent).isOf(Blocks.RESPAWN_ANCHOR)) return adjacent;
        if (client.world.getBlockState(clicked).isOf(Blocks.RESPAWN_ANCHOR)) return clicked;
        return null;
    }

    private static void abort(MinecraftClient client, String why) {
        if (client.player != null) {
            select(client, origSlot);
            msg(client, "Anchor macro: " + why);
        }
        step = -1;
        chainWait = -1;
    }

    private static void start(MinecraftClient client) {
        if (step >= 0) return; // already running
        if (client.player == null || client.world == null || client.interactionManager == null
                || client.currentScreen != null) return;

        if (client.world.getRegistryKey() == World.NETHER) {
            msg(client, "Anchor macro: anchors don't explode in the Nether");
            return;
        }
        if (client.player.isSneaking()) {
            msg(client, "Anchor macro: stop sneaking first");
            return;
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            msg(client, "Anchor macro: look at a block");
            return;
        }

        PlayerInventory inv = client.player.getInventory();
        boolean existing = client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR);
        anchorSlot = findSlot(inv, Items.RESPAWN_ANCHOR);
        glowSlot = findSlot(inv, Items.GLOWSTONE);
        if (!existing && anchorSlot < 0) {
            msg(client, "Anchor macro: no respawn anchor in your hotbar");
            return;
        }
        if (glowSlot < 0) {
            msg(client, "Anchor macro: no glowstone in your hotbar");
            return;
        }
        // Make sure a totem is ready BEFORE this anchor goes off.
        int ready = prepareTotem(client, totemSlot);
        if (ready < 0) return;
        totemSlot = ready;

        origSlot = inv.getSelectedSlot();
        placeHit = hit;
        timeout = 0;
        wait = 0;
        if (existing) {
            anchorPos = hit.getBlockPos();
            step = 1;
        } else {
            anchorPos = null;
            step = 0;
        }
    }

    private static void tick(MinecraftClient client) {
        while (runKey.wasPressed()) {
            start(client);
        }

        // Keep going with the next anchor while the key is still held.
        if (step < 0) {
            if (chainWait >= 0) {
                if (chainWait > 0) {
                    chainWait--;
                } else {
                    chainWait = -1;
                    if (runKey.isPressed()) start(client);
                }
            }
            return;
        }

        if (client.player == null || client.world == null || client.interactionManager == null) {
            step = -1;
            chainWait = -1;
            return;
        }
        if (client.currentScreen != null) { // e.g. death screen
            abort(client, "stopped");
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }

        switch (step) {
            case 0 -> { // place the anchor
                select(client, anchorSlot);
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, placeHit);
                client.player.swingHand(Hand.MAIN_HAND);
                step = 1;
                wait = 2;
                timeout = 0;
            }
            case 1 -> { // charge it with glowstone
                if (anchorPos == null) {
                    anchorPos = anchorAt(client, placeHit);
                    if (anchorPos == null) {
                        if (++timeout > MAX_WAIT) abort(client, "couldn't place the anchor");
                        return;
                    }
                    timeout = 0;
                }
                select(client, glowSlot);
                interactAt(client, anchorPos);
                step = 2;
                wait = STEP_DELAY;
                timeout = 0;
            }
            case 2 -> { // wait for the charge, check the totem, then detonate
                BlockState s = client.world.getBlockState(anchorPos);
                if (!s.isOf(Blocks.RESPAWN_ANCHOR)) {
                    abort(client, "the anchor is gone");
                    return;
                }
                if (s.get(RespawnAnchorBlock.CHARGES) < 1) {
                    if (++timeout > MAX_WAIT) abort(client, "the anchor didn't charge");
                    return;
                }
                // If the totem popped since the last explosion, replace it now.
                int ready = prepareTotem(client, totemSlot);
                if (ready < 0) {
                    abort(client, "out of totems, not exploding");
                    return;
                }
                totemSlot = ready;
                select(client, totemSlot);
                interactAt(client, anchorPos);
                step = 3;
                wait = STEP_DELAY;
            }
            case 3 -> { // back to the slot you had; continue if the key is still held
                select(client, origSlot);
                step = -1;
                if (runKey.isPressed()) chainWait = CHAIN_GAP;
            }
            default -> step = -1;
        }
    }
}
