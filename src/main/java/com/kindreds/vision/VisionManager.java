package com.kindreds.vision;

import com.kindreds.Kindreds;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillTree;
import com.kindreds.data.VisionLenses;
import com.kindreds.network.SetVisionLensC2S;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import com.kindreds.vision.lens.KeenSightLens;
import com.kindreds.vision.lens.StoneSenseLens;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side "which vision lens is on, and which ones can I cycle to" authority. Exactly one lens
 * is ever active at a time (per the task brief), matching {@link KindredData#activeVisionLens()}'s
 * shape (a single optional {@link Identifier}).
 *
 * <h2>Single active lens</h2>
 * The active lens is server-authoritative ({@link KindredData#activeVisionLens()}, synced via
 * {@code SyncKindredDataS2C} and mirrored in {@link ClientKindredData#INSTANCE}) - {@link
 * #activeLens()} just reads it. {@link #cycle} additionally writes it optimistically (so world
 * render/HUD react the instant the keybind is pressed, rather than waiting a network round trip)
 * before telling the server via {@link SetVisionLensC2S}, which re-validates and remains the actual
 * source of truth the moment it disagrees (e.g. after the next {@code SyncKindredDataS2C}, such as
 * on rejoin) - see that class's javadoc for what it enforces.
 *
 * <h2>Which lenses are unlockable</h2>
 * Derived, not stored: {@link #unlockedLenses()} scans the client's synced {@code SKILL_TREE}
 * dynamic registry (available off {@code client.world.getRegistryManager()} once in a world) for
 * every node the player owns ({@link ClientKindredData#INSTANCE}'s {@code unlockedNodes}) that
 * grants a {@code vision_unlock} ability, via the same {@link VisionLenses} helper the server uses
 * to validate {@link SetVisionLensC2S} - the two sides can never disagree about what counts as
 * "unlocked" since they share the one method.
 *
 * <h2>Iris</h2>
 * {@link #shaderPackActive()} is a best-effort, reflection-only probe for Iris's {@code
 * IrisApi.getInstance().isShaderPackInUse()} - guarded so a missing/changed Iris API can never
 * break this mod. Outline-based lenses ({@link StoneSenseLens}, {@link KeenSightLens}) check {@link
 * #isLensLive} every frame and skip their render entirely while a shaderpack is active, since custom
 * {@code NO_DEPTH_TEST} layers have known draw-order/depth friction under Iris (see {@code
 * research/lore/vision-rendering-tech-1.21.8.md}). HUD-only lenses ({@code HudTintOverlay}) are
 * Iris-safe and aren't gated by this at all.
 */
public final class VisionManager {
    private VisionManager() {
    }

    /** Lens ids that draw see-through world outlines and must degrade under Iris. */
    public static final Set<Identifier> OUTLINE_LENSES = Set.of(StoneSenseLens.ID, KeenSightLens.ID);

    // --- Active lens --------------------------------------------------------------------------

    /** The currently active lens, or {@code null} if none is equipped. Mirrors the
     * server-authoritative {@link KindredData#activeVisionLens()}. */
    public static Identifier activeLens() {
        KindredData data = ClientKindredData.INSTANCE;
        return data == null ? null : data.activeVisionLens();
    }

    /** Whether {@code lensId} is both the active lens AND actually rendering right now - i.e. not
     * degraded by an active Iris shaderpack. Outline lenses gate their render on this rather than
     * {@link #activeLens()} directly, so the Iris check lives in exactly one place. */
    public static boolean isLensLive(Identifier lensId) {
        if (!lensId.equals(activeLens())) {
            return false;
        }
        return !(OUTLINE_LENSES.contains(lensId) && shaderPackActive());
    }

    /** The scan/effect radius authored on the node that unlocked {@code lensId}, or {@code
     * fallback} if the lens isn't currently resolvable (registry not yet synced, stale data, etc.)
     * - defensive, since this is read every render frame. */
    public static int radiusFor(Identifier lensId, int fallback) {
        Integer radius = unlockedLenses().get(lensId);
        return radius != null ? radius : fallback;
    }

    // --- Unlocked lenses + cycling -------------------------------------------------------------

    /** Every lens id the player has actually unlocked, in authoring order, mapped to its authored
     * radius. Empty if not yet in a world, or the registry/player data isn't ready yet. */
    public static Map<Identifier, Integer> unlockedLenses() {
        MinecraftClient client = MinecraftClient.getInstance();
        KindredData data = ClientKindredData.INSTANCE;
        if (client.world == null || data == null) {
            return Map.of();
        }
        Registry<SkillTree> trees;
        try {
            trees = client.world.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        } catch (RuntimeException e) {
            // Registry not synced yet (e.g. very first client tick after joining) - treat as
            // "nothing unlocked yet" rather than propagating, since this is polled every frame.
            return Map.of();
        }
        return VisionLenses.unlockedLenses(data, trees);
    }

    /** Advances the active lens to the next one in {@code [off, ...unlocked lenses]} (wrapping
     * around), applying the change optimistically client-side and telling the server via {@link
     * SetVisionLensC2S} so it persists. No-ops if the player hasn't unlocked any lens yet. */
    public static void cycle(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        List<Identifier> unlocked = new ArrayList<>(unlockedLenses().keySet());
        if (unlocked.isEmpty()) {
            return;
        }
        List<Identifier> order = new ArrayList<>(unlocked.size() + 1);
        order.add(null); // "off"
        order.addAll(unlocked);

        Identifier current = activeLens();
        int currentIndex = order.indexOf(current);
        Identifier next = order.get((currentIndex + 1) % order.size());

        KindredData data = ClientKindredData.INSTANCE;
        if (data != null) {
            data.setActiveVisionLens(next); // optimistic; SetVisionLensC2S re-validates server-side
        }
        ClientPlayNetworking.send(new SetVisionLensC2S(Optional.ofNullable(next)));
    }

    // --- Iris detection -------------------------------------------------------------------------

    private static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
    private static Object irisApiInstance;
    private static Method isShaderPackInUseMethod;
    private static boolean loggedShaderPackActive = false;

    static {
        if (IRIS_LOADED) {
            try {
                Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Method getInstance = apiClass.getMethod("getInstance");
                irisApiInstance = getInstance.invoke(null);
                isShaderPackInUseMethod = irisApiInstance.getClass().getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | RuntimeException e) {
                Kindreds.LOGGER.warn("[Kindreds] Iris is loaded but its API could not be reflected into; "
                        + "vision lenses will not detect shaderpacks this session", e);
                irisApiInstance = null;
                isShaderPackInUseMethod = null;
            }
        }
    }

    /** Best-effort: {@code true} only if Iris is loaded, its API resolved cleanly at class-init
     * time, AND it currently reports a shaderpack in use. Any failure along the way (Iris absent,
     * API missing/changed, reflective invocation throwing) resolves to {@code false} - this must
     * never break the vision system; worst case it just doesn't degrade when it maybe should have. */
    public static boolean shaderPackActive() {
        if (isShaderPackInUseMethod == null) {
            return false;
        }
        try {
            boolean active = Boolean.TRUE.equals(isShaderPackInUseMethod.invoke(irisApiInstance));
            if (active && !loggedShaderPackActive) {
                loggedShaderPackActive = true;
                Kindreds.LOGGER.info("[Kindreds] Iris shaderpack detected; disabling outline-based vision "
                        + "lenses (stone-sense/keen-sight) for this session");
            }
            return active;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
