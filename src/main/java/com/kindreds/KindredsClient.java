package com.kindreds;

import com.kindreds.client.screen.SkillTreeScreen;
import com.kindreds.network.ActivateAbilityC2S;
import com.kindreds.network.OpenTreeC2S;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.network.UnlockResultS2C;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.vision.VisionManager;
import com.kindreds.vision.lens.KeenSightLens;
import com.kindreds.vision.lens.StoneSenseLens;
import com.kindreds.vision.overlay.HudTintOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class KindredsClient implements ClientModInitializer {
    /**
     * "Use ability" keybind: the P1 trigger for the active-ability framework (see
     * {@link ActivateAbilityC2S}) - activates the player's first unlocked active ability. Defaults
     * to {@code G} so active skills are usable out of the box; the skill-tree detail panel shows
     * this key next to each active skill, and it can be rebound in Controls if it collides with
     * another mod in a given pack.
     */
    private static final KeyBinding USE_ABILITY_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.kindreds.use_ability",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.category.kindreds"));

    /**
     * "Cycle vision" keybind: advances {@link VisionManager}'s active lens among {@code [off,
     * ...unlocked lenses]} (see {@link VisionManager#cycle}). Defaults to {@code V}; vision skills
     * can also be equipped directly from the skill-tree detail panel, and this key can be rebound.
     */
    private static final KeyBinding CYCLE_VISION_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.kindreds.cycle_vision",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.category.kindreds"));

    /** The localized name of the currently-bound "Use ability" key (for the skill-tree UI hint). */
    public static net.minecraft.text.Text useAbilityKeyName() {
        return USE_ABILITY_KEY.getBoundKeyLocalizedText();
    }

    /** The localized name of the currently-bound "Cycle vision" key (for the skill-tree UI hint). */
    public static net.minecraft.text.Text cycleVisionKeyName() {
        return CYCLE_VISION_KEY.getBoundKeyLocalizedText();
    }

    /**
     * "Open skill tree" keybind (Task 11). Unlike {@link #USE_ABILITY_KEY}/{@link #CYCLE_VISION_KEY},
     * this defaults to a bound key ({@code K}) rather than {@code GLFW_KEY_UNKNOWN}: opening a menu
     * carries none of those keybinds' "could fire/swap something the player never opted into" risk -
     * worst case a player presses it once, sees a screen they didn't expect, and closes it (Esc).
     * {@code K} is not a modern vanilla default; this pack's exact keybind layout could not be
     * verified from static analysis alone (see Task 11 report) - if it collides with another mod's
     * bind in a given modpack, the player can simply rebind it in Controls like any other key.
     */
    private static final KeyBinding OPEN_TREE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.kindreds.open_tree",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.category.kindreds"));

    @Override
    public void onInitializeClient() {
        // Store the latest server-authoritative skill data for client-side UI/HUD to read; hop
        // onto the main client thread since network receivers otherwise run on the networking
        // thread (matches the pattern used by the Mithril Locator mod's PolicyPayload receiver).
        ClientPlayNetworking.registerGlobalReceiver(SyncKindredDataS2C.ID, (payload, context) ->
                context.client().execute(() -> ClientKindredData.INSTANCE = payload.data()));

        // Surfaces a rejected unlock/respec (or accepted respec) as a toast on whatever screen is
        // currently open; a no-op if the tree screen never asked for anything (nothing else sends
        // RequestUnlockC2S/RespecC2S yet).
        ClientPlayNetworking.registerGlobalReceiver(UnlockResultS2C.ID, (payload, context) ->
                context.client().execute(() ->
                        SkillTreeScreen.handleUnlockResult(context.client(), payload.ok(), payload.reason())));

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
            while (CYCLE_VISION_KEY.wasPressed()) {
                VisionManager.cycle(client);
            }
            while (OPEN_TREE_KEY.wasPressed()) {
                if (client.player != null) {
                    // Open immediately from whatever ClientKindredData already has cached (see
                    // SkillTreeScreen's javadoc) - no need to block on a round trip - and also ask
                    // the server for a fresh sync in case the client's mirror is stale.
                    SkillTreeScreen.open(client);
                    ClientPlayNetworking.send(new OpenTreeC2S());
                }
            }
        });

        // Vision framework: world-render outline lenses + Iris-safe HUD tint. Each lens registers
        // its own WorldRenderEvents.AFTER_TRANSLUCENT hook and gates itself on VisionManager's
        // active-lens/Iris state, so registering both here unconditionally is safe - at most one
        // renders anything on a given frame.
        StoneSenseLens.register();
        KeenSightLens.register();
        HudTintOverlay.register();

        // FIX (gamma stranded on disconnect): the lenses' own render()-driven gamma restore can't
        // fire once MinecraftClient.world goes null, since WorldRenderEvents.AFTER_TRANSLUCENT stops
        // firing entirely at that point (an ordinary Disconnect/Leave, no crash needed) - see
        // VisionManager.onWorldLeave()'s javadoc. Matches the Mithril Locator mod's
        // ClientPlayConnectionEvents.DISCONNECT-driven state reset.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> VisionManager.onWorldLeave());

        Kindreds.LOGGER.info("[Kindreds] client initialized");
    }
}
