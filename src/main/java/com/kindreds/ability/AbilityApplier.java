package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.data.ability.AttributeMod;
import com.kindreds.data.ability.CurseDef;
import com.kindreds.data.ability.StatusEffectDef;
import com.kindreds.data.ability.VisionUnlock;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Applies (and reverses) the live gameplay side-effects a {@link AbilityDef} grants once its
 * owning skill node is unlocked (or strips them again on respec/relock). MC-bound - touches real
 * {@link ServerPlayerEntity} state, so per Task 6's brief this is compile-verified only (no unit
 * test); {@code UnlockService} carries the tested pure rules.
 *
 * <h2>Attribute modifiers</h2>
 * Every persistent attribute change this class makes (both {@link AttributeMod} abilities and
 * {@link CurseDef} drawbacks routed through {@link CurseService}) is tagged with the id
 * {@code kindreds:node/<nodeId>/<attribute path>} (see {@link #attributeModifierId}). That scheme
 * is what lets {@link #removeAll} reverse a node's effects knowing only its id - it doesn't need
 * the original {@link AbilityDef} list, it just re-derives the same ids and asks every attribute
 * instance to drop a modifier with that id (a no-op if it never had one).
 *
 * <h2>Status effects</h2>
 * {@link StatusEffectDef} abilities are <b>not</b> reversible by {@link #removeAll} the same way:
 * a {@link StatusEffectInstance} carries no id field to tag it with a node, and re-deriving "which
 * effects came from which node" would require either extending the wire format or a side ledger.
 * That bookkeeping isn't needed by anything in Phase 1 (nodes are never revoked once earned; a
 * future respec system - Task 13 - would need to add it). For P1, a node's status effect is
 * applied once with {@link StatusEffectInstance#INFINITE} duration when
 * {@code durationTicks == -1} (documented as "reapplied indefinitely while owned") and otherwise
 * left as a fixed-duration buff; nothing currently re-applies or removes it after that.
 *
 * <h2>Vision / active abilities</h2>
 * {@link VisionUnlock} and {@link ActiveAbilityDef} have no attribute/effect side effects of their
 * own - {@link #apply} is a safe no-op for them. Availability is instead read directly from
 * {@link com.kindreds.playerdata.KindredData#unlockedNodes()} by the vision framework (Task 10)
 * and the active-ability service (Task 9) when they land.
 */
public final class AbilityApplier {
    private AbilityApplier() {
    }

    /** Applies {@code def}'s effect to {@code p}. Exhaustive over every {@link AbilityDef} subtype. */
    public static void apply(ServerPlayerEntity p, AbilityDef def, String nodeId) {
        switch (def) {
            case AttributeMod mod -> applyAttributeMod(p, mod, nodeId);
            case StatusEffectDef status -> applyStatusEffect(p, status);
            case CurseDef curse -> CurseService.apply(p, curse, nodeId);
            case VisionUnlock vision -> {
                // No side effect here; the vision framework reads unlockedNodes()/visionId directly.
            }
            case ActiveAbilityDef active -> {
                // No side effect here; the active-ability service reads unlockedNodes() directly.
            }
        }
    }

    /**
     * Reverses every effect of {@code nodeId} that {@link #apply} can self-describe purely from
     * the node id - in practice, every attribute modifier (both direct {@link AttributeMod}
     * abilities and {@link CurseDef} drawbacks). See the class javadoc for why status effects
     * aren't covered.
     */
    public static void removeAll(ServerPlayerEntity p, String nodeId) {
        for (EntityAttribute attribute : Registries.ATTRIBUTE) {
            RegistryEntry<EntityAttribute> entry = Registries.ATTRIBUTE.getEntry(attribute);
            String attrPath = attributePath(entry);
            if (attrPath == null) {
                continue;
            }
            EntityAttributeInstance instance = p.getAttributeInstance(entry);
            if (instance == null) {
                continue;
            }
            instance.removeModifier(attributeModifierId(nodeId, attrPath));
        }
    }

    // --- AttributeMod ---------------------------------------------------------------------------

    private static void applyAttributeMod(ServerPlayerEntity p, AttributeMod mod, String nodeId) {
        Registries.ATTRIBUTE.getEntry(mod.attribute()).ifPresentOrElse(attribute -> {
            EntityAttributeInstance instance = p.getAttributeInstance(attribute);
            if (instance == null) {
                Kindreds.LOGGER.warn("[Kindreds] node {} targets attribute {} but the player has no such instance",
                        nodeId, mod.attribute());
                return;
            }
            Identifier id = attributeModifierId(nodeId, mod.attribute().getPath());
            instance.addPersistentModifier(new EntityAttributeModifier(id, mod.amount(), operationFrom(mod.operation())));
        }, () -> Kindreds.LOGGER.warn("[Kindreds] node {} references unknown attribute id '{}'", nodeId, mod.attribute()));
    }

    private static EntityAttributeModifier.Operation operationFrom(String operation) {
        return switch (operation) {
            case "add_value" -> EntityAttributeModifier.Operation.ADD_VALUE;
            case "add_multiplied_base" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> throw new IllegalArgumentException("Unknown attribute modifier operation: " + operation);
        };
    }

    // --- StatusEffectDef -------------------------------------------------------------------------

    private static void applyStatusEffect(ServerPlayerEntity p, StatusEffectDef def) {
        Registries.STATUS_EFFECT.getEntry(def.effect()).ifPresentOrElse(effect -> {
            int duration = def.durationTicks() < 0 ? StatusEffectInstance.INFINITE : def.durationTicks();
            // ambient=false (not a beacon-style effect), showParticles=false (avoid permanent
            // particle clutter on a long-lived racial buff), showIcon=true (still visible in HUD).
            p.addStatusEffect(new StatusEffectInstance(effect, duration, def.amplifier(), false, false, true));
        }, () -> Kindreds.LOGGER.warn("[Kindreds] unknown status effect id '{}'", def.effect()));
    }

    // --- Shared attribute-id scheme (also used by CurseService) ---------------------------------

    /** {@code kindreds:node/<nodeId>/<attrPath>} - the id every persistent attribute modifier this
     * class (or {@link CurseService}) installs is tagged with, so it can be found again later by
     * {@link #removeAll} knowing only the node id. */
    static Identifier attributeModifierId(String nodeId, String attrPath) {
        return Identifier.of(Kindreds.MOD_ID, "node/" + nodeId + "/" + attrPath);
    }

    /** The attribute registry path for {@code attribute} (e.g. {@code "generic.max_health"}), or
     * {@code null} if it has no registry key (shouldn't happen for a live registry entry). */
    static String attributePath(RegistryEntry<EntityAttribute> attribute) {
        return attribute.getKey().map(key -> key.getValue().getPath()).orElse(null);
    }
}
