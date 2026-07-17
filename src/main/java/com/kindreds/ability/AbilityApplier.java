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
import net.minecraft.entity.effect.StatusEffects;
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
 * {@code kindreds:node/<nodeId>/<attribute path>} (see {@link #attributeModifierId}). That scheme
 * is what lets {@link #removeAll} reverse a node's effects knowing only its id - it doesn't need
 * the original {@link AbilityDef} list, it just re-derives the same ids and asks every attribute
 * instance to drop a modifier with that id (a no-op if it never had one).
 *
 * <h2>Status effects</h2>
 * {@link StatusEffectDef} abilities are <b>not</b> reversible by {@link #removeAll} the same way:
 * a {@link StatusEffectInstance} carries no id field to tag it with a node, and re-deriving "which
 * effects came from which node" from the node id alone would require either extending the wire
 * format or a side ledger. {@link #removeNode}, given the node's actual {@link AbilityDef} list,
 * does not have that problem - vanilla removes status effects by effect *type*
 * ({@link ServerPlayerEntity#removeStatusEffect(net.minecraft.registry.entry.RegistryEntry)}), so
 * no per-node id tagging is needed for it. For P1, a node's status effect is applied once with
 * {@link StatusEffectInstance#INFINITE} duration when {@code durationTicks == -1} (documented as
 * "reapplied indefinitely while owned") and otherwise left as a fixed-duration buff; nothing
 * currently calls {@link #removeNode} yet (nodes are never revoked in Phase 1) - it exists as the
 * reversal API a future respec system (Task 13) will call.
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
     * Reverses every effect of {@code nodeId} that can be self-described purely from the node id
     * - in practice, every attribute modifier (both direct {@link AttributeMod} abilities and
     * {@link CurseDef} drawbacks), by sweeping the whole attribute registry and asking every
     * instance to drop a modifier with the id {@code nodeId} would have used (a no-op if it never
     * had one). Does <b>not</b> reverse {@link StatusEffectDef} abilities - use {@link #removeNode}
     * (which is given the node's actual ability list) for a fully reversing respec.
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

    /**
     * Fully reverses {@code nodeId}'s effects on {@code p}, dispatching per-ability on the sealed
     * {@link AbilityDef} subtype (mirroring {@link #apply}'s dispatch) rather than {@link
     * #removeAll}'s blind attribute-registry sweep:
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
     * Unlike {@link #removeAll(ServerPlayerEntity, String)}, this needs the node's ability list
     * (not just its id) - nothing calls this yet in Phase 1 (nodes are never revoked), it exists as
     * the reversal API a future respec system (Task 13) will call.
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

    // --- ActiveAbilityDef (Task 9) ---------------------------------------------------------------

    /**
     * P1 placeholder effect for {@link ActiveAbilityDef} activation, called by
     * {@code ActiveAbilityService#activate} once it has resolved an unlocked, off-cooldown active
     * ability. {@link ActiveAbilityDef} only carries {@code abilityId} + {@code cooldownTicks} -
     * no effect payload of its own yet - so every active ability currently grants the same short
     * self-buff (Speed II for 5s) when triggered, regardless of {@code def.abilityId()}. A later
     * task that authors real, differentiated active abilities will need to extend
     * {@link ActiveAbilityDef}'s schema with an actual effect definition and branch on
     * {@code def.abilityId()} here (mirroring {@link CurseService}'s curse-id dispatch); until
     * then this keeps the activation framework - unlock gating, cooldown tracking, network
     * round-trip - real and end-to-end testable even though no two active abilities differ in
     * effect yet.
     */
    public static void applyActiveEffect(ServerPlayerEntity p, ActiveAbilityDef def) {
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 100, 1, false, true, true));
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
