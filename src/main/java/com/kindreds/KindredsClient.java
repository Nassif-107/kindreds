package com.kindreds;

import com.kindreds.network.ActivateAbilityC2S;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.playerdata.ClientKindredData;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class KindredsClient implements ClientModInitializer {
    /**
     * "Use ability" keybind: the P1 trigger for the active-ability framework (see
     * {@link ActivateAbilityC2S}). Default-unbound ({@code GLFW_KEY_UNKNOWN}) rather than bound to
     * a concrete key out of the box - with no per-ability selection UI yet (see
     * {@link ActivateAbilityC2S}'s javadoc for the P1 "always activate my first unlocked active
     * ability" simplification), auto-binding this to e.g. an unused key could fire an ability a
     * player never consciously opted into; shipping unbound means it only fires for someone who
     * deliberately binds it via Controls.
     */
    private static final KeyBinding USE_ABILITY_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.kindreds.use_ability",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.category.kindreds"));

    @Override
    public void onInitializeClient() {
        // Store the latest server-authoritative skill data for client-side UI/HUD to read; hop
        // onto the main client thread since network receivers otherwise run on the networking
        // thread (matches the pattern used by the Mithril Locator mod's PolicyPayload receiver).
        ClientPlayNetworking.registerGlobalReceiver(SyncKindredDataS2C.ID, (payload, context) ->
                context.client().execute(() -> ClientKindredData.INSTANCE = payload.data()));

        // Polled once per client tick (rather than reacting to a raw input event) per vanilla/
        // Fabric convention for KeyBinding - wasPressed() drains one queued press per call, so the
        // while-loop below correctly handles multiple presses landing within a single tick without
        // dropping any. abilityId is always sent blank; see ActivateAbilityC2S's javadoc for why
        // the server (not the client) resolves which ability that activates in P1.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (USE_ABILITY_KEY.wasPressed()) {
                if (client.player != null) {
                    ClientPlayNetworking.send(new ActivateAbilityC2S(""));
                }
            }
        });

        Kindreds.LOGGER.info("[Kindreds] client initialized");
    }
}
