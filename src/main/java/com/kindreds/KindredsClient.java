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

    /** The localized name of the "Cycle ability slot" key (for the HUD ability-bar hint). */
    /** The localized name of the currently-bound "Open skill tree" key (for hints and the welcome). */
    public static net.minecraft.text.Text openTreeKeyName() {
        return OPEN_TREE_KEY.getBoundKeyLocalizedText();
    }

    /** The localized name of the "Open ability loadout" key (for the empty-radial hint + welcome). */
    public static net.minecraft.text.Text openLoadoutKeyName() {
        return OPEN_LOADOUT_KEY.getBoundKeyLocalizedText();
    }

    public static net.minecraft.text.Text cycleAbilityKeyName() {
        return CYCLE_ABILITY_KEY.getBoundKeyLocalizedText();
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

    /** "Open Kindred Codex" - the browsable character/traits menu (defaults to {@code J}). */
    private static final KeyBinding OPEN_CODEX_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.kindreds.open_codex",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "key.category.kindreds"));

    /** "Cycle ability slot" - advances which loadout slot the use-ability key fires (defaults to
     * {@code R}). The selected slot is highlighted in the HUD ability bar. */
    private static final KeyBinding CYCLE_ABILITY_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.kindreds.cycle_ability",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.category.kindreds"));

    /** "Open ability loadout" - the slot-assignment screen (defaults to {@code L}). */
    private static final KeyBinding OPEN_LOADOUT_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.kindreds.open_loadout",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            "key.category.kindreds"));

    /**
     * Per-slot "select ability N" keybinds - the power-user path that skips the radial entirely
     * (the pattern Ars Nouveau and Iron's Spells both offer alongside their wheels). Deliberately
     * <b>unbound</b> by default: 1-{@value com.kindreds.client.loadout.ClientLoadout#SLOTS} would
     * collide with the vanilla hotbar, and silently stealing those would be worse than asking the
     * player to bind them in Controls if they want them.
     */
    private static final KeyBinding[] SELECT_SLOT_KEYS = new KeyBinding[
            com.kindreds.client.loadout.ClientLoadout.SLOTS];
    static {
        for (int i = 0; i < SELECT_SLOT_KEYS.length; i++) {
            SELECT_SLOT_KEYS[i] = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.kindreds.select_slot_" + (i + 1),
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    "key.category.kindreds"));
        }
    }

    /** "Open Kindreds settings" - the server-rules screen (unbound by default; also reachable from
     * the skill tree's Settings button). */
    private static final KeyBinding OPEN_SETTINGS_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.kindreds.open_settings",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.category.kindreds"));

    /** Ticks the wheel key has been held; past {@link #HOLD_TICKS} it opens the radial instead of
     * counting as a tap-cycle. ~250ms is long enough not to trip on a quick tap, short enough that a
     * deliberate hold feels instant. */
    private static final int HOLD_TICKS = 5;
    private static int cycleHeldTicks;
    private static boolean radialOpened;

    /** Whether the "ability wheel" key is currently held - drives scroll-to-switch (MouseScrollMixin). */
    public static boolean cycleAbilityHeld() {
        return CYCLE_ABILITY_KEY.isPressed();
    }

    /** Selects a loadout slot and echoes it on the action bar, so a switch is always confirmed. */
    public static void selectSlot(net.minecraft.client.MinecraftClient client, int slot) {
        com.kindreds.client.loadout.ClientLoadout.setSelected(slot);
        if (client.player != null) {
            String id = com.kindreds.client.loadout.ClientLoadout.slot(slot);
            client.player.sendMessage(net.minecraft.text.Text.translatable("kindreds.hud.selected",
                    slot + 1, com.kindreds.client.loadout.ClientLoadout.displayName(id))
                    .formatted(net.minecraft.util.Formatting.AQUA), true);
        }
    }

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
        // The server's rule settings, for the settings screen (display only - changes go back to the
        // server, which re-checks operator permission).
        ClientPlayNetworking.registerGlobalReceiver(com.kindreds.network.SyncConfigS2C.ID,
                (payload, context) -> context.client().execute(() ->
                        com.kindreds.client.screen.ClientConfigMirror.set(
                                com.kindreds.network.SyncConfigS2C.parse(payload.json()))));

        ClientPlayNetworking.registerGlobalReceiver(UnlockResultS2C.ID, (payload, context) ->
                context.client().execute(() ->
                        SkillTreeScreen.handleUnlockResult(context.client(), payload.ok(), payload.reason())));

        // Polled once per client tick (rather than reacting to a raw input event) per vanilla/
        // Fabric convention for KeyBinding - wasPressed() drains one queued press per call, so the
        // while-loop below correctly handles multiple presses landing within a single tick without
        // dropping any. abilityId is always sent blank; see ActivateAbilityC2S's javadoc for why
        // the server (not the client) resolves which ability that activates in P1.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Watches the synced mirror for discipline level-ups / first-join, and feeds the HUD's
            // unspent-points pip. Cheap: a few map lookups per tick.
            com.kindreds.client.ClientProgress.tick(client);
            while (USE_ABILITY_KEY.wasPressed()) {
                if (client.player != null) {
                    // Fire the selected loadout slot's ability (blank if empty - the server treats a
                    // blank id as "my first unlocked active", a sensible fallback before any assignment).
                    ClientPlayNetworking.send(new ActivateAbilityC2S(
                            com.kindreds.client.loadout.ClientLoadout.selectedAbilityId()));
                }
            }
            // Three ways to switch, so nobody is forced into a menu mid-fight (the mistake of making
            // the radial the *only* route): TAP = advance one slot instantly, HOLD = open the radial,
            // and holding the key while scrolling walks the bar (see MouseScrollMixin).
            while (CYCLE_ABILITY_KEY.wasPressed()) {
                // drained so a queued press can't fire again after the hold/tap handling below
            }
            if (client.currentScreen != null) {
                cycleHeldTicks = 0; // a screen owns the keyboard; never track holds through one
                radialOpened = false;
            } else if (CYCLE_ABILITY_KEY.isPressed()) {
                cycleHeldTicks++;
                if (cycleHeldTicks == HOLD_TICKS && client.player != null) {
                    client.setScreen(new com.kindreds.client.loadout.AbilityRadialScreen());
                    radialOpened = true;
                }
            } else {
                if (cycleHeldTicks > 0 && cycleHeldTicks < HOLD_TICKS && !radialOpened
                        && client.player != null) {
                    selectSlot(client, (com.kindreds.client.loadout.ClientLoadout.selected() + 1)
                            % com.kindreds.client.loadout.ClientLoadout.SLOTS);
                }
                cycleHeldTicks = 0;
                radialOpened = false;
            }
            // Direct per-slot selection (unbound by default; see SELECT_SLOT_KEYS).
            for (int i = 0; i < SELECT_SLOT_KEYS.length; i++) {
                while (SELECT_SLOT_KEYS[i].wasPressed()) {
                    if (client.player != null) {
                        selectSlot(client, i);
                    }
                }
            }
            while (OPEN_LOADOUT_KEY.wasPressed()) {
                if (client.player != null) {
                    client.setScreen(new com.kindreds.client.loadout.KindredLoadoutScreen());
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
                    com.kindreds.client.screen.KindredHubScreen.open(client);
                    ClientPlayNetworking.send(new OpenTreeC2S());
                }
            }
            while (OPEN_SETTINGS_KEY.wasPressed()) {
                if (client.player != null) {
                    com.kindreds.client.screen.KindredsSettingsScreen.open(client);
                }
            }
            while (OPEN_CODEX_KEY.wasPressed()) {
                if (client.player != null) {
                    com.kindreds.client.screen.KindredCodexScreen.open(client);
                    ClientPlayNetworking.send(new OpenTreeC2S()); // refresh the client's data mirror
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

        // Ability loadout: the always-on HUD slot bar. ClientLoadout lazy-loads its saved slots on
        // first access, so no explicit load() call is needed here.
        com.kindreds.client.loadout.LoadoutHud.register();

        // Add a "Kindred Traits" button onto the base mod's race-selection screen (opens the Codex).
        com.kindreds.client.OnboardingCodexButton.register();

        // "Wayfarer" - Men keep their footing on ice (no sliding).
        com.kindreds.client.WayfarerIceGrip.register();

        // FIX (gamma stranded on disconnect): the lenses' own render()-driven gamma restore can't
        // fire once MinecraftClient.world goes null, since WorldRenderEvents.AFTER_TRANSLUCENT stops
        // firing entirely at that point (an ordinary Disconnect/Leave, no crash needed) - see
        // VisionManager.onWorldLeave()'s javadoc. Matches the Mithril Locator mod's
        // ClientPlayConnectionEvents.DISCONNECT-driven state reset.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> VisionManager.onWorldLeave());

        Kindreds.LOGGER.info("[Kindreds] client initialized");
    }
}
