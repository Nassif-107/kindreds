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

import java.util.List;

/**
 * Applies (and reverses) the live gameplay side-effects a {@link AbilityDef} grants once its
 * owning skill node is unlocked (or strips them again on respec/relock). MC-bound - touches real
 * {@link ServerPlayerEntity} state, so per Task 6's brief this is compile-verified only (no unit
 * test); {@code UnlockService} carries the tested pure rules.
 *
 * <h2>Never throws on bad data</h2>
 * {@link #apply} is called from the network handler ({@code RequestUnlockC2S}) after the node has
 * already been marked unlocked; a thrown exception there would leave the server's
 * {@code KindredData} mutated without ever reaching the client re-sync, desyncing the two. Every
 * untrusted-JSON parse inside this class (attribute ids, status effect ids, modifier operation
 * strings) therefore logs a warning and skips just that modifier rather than throwing - consistent
 * across all of {@link #applyAttributeMod}, {@link #operationFrom}, and {@link #applyStatusEffect}.
 *
 * <h2>Attribute modifiers</h2>
 * Every persistent attribute change this class makes (both {@link AttributeMod} abilities and
 * {@link CurseDef} drawbacks routed through {@link CurseService}) is tagged with the id
 * {@code kindreds:node/<nodeId>/<attribute path>} (see {@link #attributeModifierId}). {@link
 * #removeNode} re-derives that same id per ability to remove exactly the modifier it (or {@link
 * CurseService}) added - it doesn't need to scan the whole attribute registry, since it's always
 * given the node's actual ability list.
 *
 * <h2>Status effects</h2>
 * {@link StatusEffectDef} abilities carry no id field to tag with a node - a {@link
 * StatusEffectInstance} has no per-application id, so {@link #removeNode} removes them by effect
 * *type* instead ({@link ServerPlayerEntity#removeStatusEffect(net.minecraft.registry.entry.RegistryEntry)}),
 * which is why {@link #removeNode} (given the node's actual {@link AbilityDef} list) is the one
 * true reversal API, used by both a full respec ({@code RespecService#reverseAll}) and a single
 * contextual curse's context-ending transition ({@code CurseContextService}). For P1, a node's
 * status effect is applied once with {@link StatusEffectInstance#INFINITE} duration when {@code
 * durationTicks == -1} (documented as "reapplied indefinitely while owned") and otherwise left as
 * a fixed-duration buff.
 *
 * <h2>Vision / active abilities</h2>
 * {@link VisionUnlock} and {@link ActiveAbilityDef} have no attribute/effect side effects of their
 * own - {@link #apply} (and {@link #removeNode}) are safe no-ops for them. Availability is instead
 * read directly from {@link com.kindreds.playerdata.KindredData#unlockedNodes()} by the vision
 * framework (Task 10) and the active-ability service (Task 9) when they land.
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
     * Fully reverses {@code nodeId}'s effects on {@code p}, dispatching per-ability on the sealed
     * {@link AbilityDef} subtype (mirroring {@link #apply}'s dispatch):
     * <ul>
     *   <li>{@link AttributeMod} - removes the node-tagged {@link EntityAttributeModifier} id
     *       for that attribute.</li>
     *   <li>{@link CurseDef} - delegates to {@link CurseService#remove}, which removes the
     *       node-tagged modifier for whichever attribute that curse id targets.</li>
     *   <li>{@link StatusEffectDef} - removes the effect by <b>type</b> via {@link
     *       ServerPlayerEntity#removeStatusEffect(RegistryEntry)} (vanilla status effects have no
     *       per-application id to tag with a node, but removal is keyed on the effect type, so no
     *       tagging is needed here).</li>
     *   <li>{@link VisionUnlock} / {@link ActiveAbilityDef} - no-op (no side effects to reverse;
     *       see the class javadoc).</li>
     * </ul>
     * Needs the node's actual ability list (not just its id), unlike a blind attribute-registry
     * sweep would - callers: {@code RespecService#reverseAll} (full respec) and {@code
     * CurseContextService} (a single contextual curse's inner effect, on a node-unowned or
     * context-ending transition).
     */
    public static void removeNode(ServerPlayerEntity p, List<AbilityDef> abilities, String nodeId) {
        for (AbilityDef ability : abilities) {
            switch (ability) {
                case AttributeMod mod -> removeAttributeMod(p, mod, nodeId);
                case CurseDef curse -> CurseService.remove(p, curse, nodeId);
                case StatusEffectDef status -> Registries.STATUS_EFFECT.getEntry(status.effect())
                        .ifPresentOrElse(p::removeStatusEffect,
                                () -> Kindreds.LOGGER.warn(
                                        "[Kindreds] node {} references unknown status effect id '{}' during removal",
                                        nodeId, status.effect()));
                case VisionUnlock vision -> {
                    // No side effect to reverse; see the class javadoc.
                }
                case ActiveAbilityDef active -> {
                    // No side effect to reverse; see the class javadoc.
                }
            }
        }
    }

    private static void removeAttributeMod(ServerPlayerEntity p, AttributeMod mod, String nodeId) {
        Registries.ATTRIBUTE.getEntry(mod.attribute()).ifPresent(attribute -> {
            EntityAttributeInstance instance = p.getAttributeInstance(attribute);
            if (instance == null) {
                return;
            }
            instance.removeModifier(attributeModifierId(nodeId, mod.attribute().getPath()));
        });
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
            EntityAttributeModifier.Operation operation = operationFrom(mod.operation(), nodeId);
            if (operation == null) {
                return;
            }
            Identifier id = attributeModifierId(nodeId, mod.attribute().getPath());
            instance.addPersistentModifier(new EntityAttributeModifier(id, mod.amount(), operation));
        }, () -> Kindreds.LOGGER.warn("[Kindreds] node {} references unknown attribute id '{}'", nodeId, mod.attribute()));
    }

    /** Maps a JSON-authored operation string to its {@link EntityAttributeModifier.Operation}, or
     * {@code null} (after logging a warning) if it's not one of the recognized names - consistent
     * with the unknown-attribute/unknown-effect paths, this must never throw: a single typo'd
     * operation in a tree JSON shouldn't blow up the whole unlock. */
    private static EntityAttributeModifier.Operation operationFrom(String operation, String nodeId) {
        return switch (operation) {
            case "add_value" -> EntityAttributeModifier.Operation.ADD_VALUE;
            case "add_multiplied_base" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> {
                Kindreds.LOGGER.warn("[Kindreds] node {} references unknown attribute modifier operation '{}'",
                        nodeId, operation);
                yield null;
            }
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

    // --- ActiveAbilityDef (Task 9 / firmed up in Task 12 Stage A) -----------------------------

    /**
     * Applies {@link ActiveAbilityDef#effects()} to the caster, called by
     * {@code ActiveAbilityService#activate} once it has resolved an unlocked, off-cooldown active
     * ability. Each authored {@link StatusEffectDef} is applied the same way {@link
     * #applyStatusEffect} applies a passive node's status effect (same infinite-duration-if-{@code
     * -1} convention) - a real, per-race differentiated payload as of Task 12, replacing the
     * earlier P1 placeholder (a hardcoded Speed II every active ability shared regardless of
     * {@code abilityId}). An ability authored with no {@code effects} (the pre-Task-12 shape, or a
     * deliberately effect-less trigger) is a no-op here rather than falling back to any default
     * buff.
     */
    public static void applyActiveEffect(ServerPlayerEntity p, ActiveAbilityDef def) {
        for (StatusEffectDef effect : def.effects()) {
            applyStatusEffect(p, effect);
        }
    }

    // --- Shared attribute-id scheme (also used by CurseService) ---------------------------------

    /** {@code kindreds:node/<nodeId>/<attrPath>} - the id every persistent attribute modifier this
     * class (or {@link CurseService}) installs is tagged with, so {@link #removeAttributeMod}/
     * {@link CurseService#remove} can find and remove exactly that modifier again later, knowing
     * only the node id and the attribute. */
    static Identifier attributeModifierId(String nodeId, String attrPath) {
        return Identifier.of(Kindreds.MOD_ID, "node/" + nodeId + "/" + attrPath);
    }

    /** The attribute registry path for {@code attribute} (e.g. {@code "generic.max_health"}), or
     * {@code null} if it has no registry key (shouldn't happen for a live registry entry). */
    static String attributePath(RegistryEntry<EntityAttribute> attribute) {
        return attribute.getKey().map(key -> key.getValue().getPath()).orElse(null);
    }
}
