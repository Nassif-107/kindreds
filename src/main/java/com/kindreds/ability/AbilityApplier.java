package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.data.ability.AttributeMod;
import com.kindreds.data.ability.ContextualBoon;
import com.kindreds.data.ability.CurseDef;
import com.kindreds.data.ability.PerkDef;
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
            case AttributeMod mod -> applyAttributeMod(p, mod, nodeId, true);
            case StatusEffectDef status -> applyStatusEffect(p, status, false);
            case CurseDef curse -> CurseService.apply(p, curse, nodeId);
            case VisionUnlock vision -> {
                // No side effect here; the vision framework reads unlockedNodes()/visionId directly.
            }
            case ActiveAbilityDef active -> {
                // No side effect here; the active-ability service reads unlockedNodes() directly.
            }
            case ContextualBoon boon -> {
                // No side effect here; CurseContextService applies/removes the inner effect by context.
            }
            case PerkDef perk -> {
                // No side effect here; PerkService reads owned perks live on the relevant game event.
            }
        }
    }

    /**
     * Applies a <b>context-driven</b> effect (a {@link ContextualBoon}, or a contextual {@link
     * CurseDef}'s inner effect) - identical to {@link #apply} except that a {@link StatusEffectDef}
     * is granted with its particle swirl <b>visible</b>. A racial trait that switches on with the
     * time of day or the depth of the stone (Starlit Grace, Dread of the Sun, Deep-Dark Unease,
     * Children of the Dark) should read as an event the player can see happening, not a silent icon
     * that is easy to miss - so {@link CurseContextService} routes its APPLY transitions here. Removal
     * is unchanged: {@link #removeNode} strips a status effect by type regardless of how it was shown.
     */
    public static void applyContextual(ServerPlayerEntity p, AbilityDef def, String key) {
        switch (def) {
            case StatusEffectDef status -> applyStatusEffect(p, status, true);
            // Temporary (not persistent): a context-driven attribute penalty/boon must NOT be saved to
            // the player's NBT - otherwise it survives relog, and on next login CurseContextService's
            // ACTIVE bookkeeping (which resets on relog) no longer knows it's applied: it would either
            // linger forever out of context, or throw on a duplicate-id re-apply. A temporary modifier
            // vanishes on relog, so the tick loop re-derives a clean, correct state every session.
            case AttributeMod mod -> applyAttributeMod(p, mod, key, false);
            default -> apply(p, def, key);
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
                case ContextualBoon boon -> {
                    // No side effect to reverse here; CurseContextService owns its lifecycle.
                }
                case PerkDef perk -> {
                    // No side effect to reverse; PerkService reads owned perks live on the game event.
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

    /**
     * Installs {@code mod}'s attribute modifier on {@code p}. {@code persistent} chooses the storage:
     * a permanent trait (birth attribute, unlocked node) uses a <b>persistent</b> modifier that
     * survives relog; a context-driven trait uses a <b>temporary</b> one that vanishes on relog (see
     * {@link #applyContextual}). Idempotent either way - the node-tagged id is removed first, so a
     * re-apply (e.g. a reconcile pass, or re-entering a context) can never throw on a duplicate id.
     */
    private static void applyAttributeMod(ServerPlayerEntity p, AttributeMod mod, String nodeId, boolean persistent) {
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
            instance.removeModifier(id); // idempotent guard: adding a duplicate id throws
            EntityAttributeModifier modifier = new EntityAttributeModifier(id, mod.amount(), operation);
            if (persistent) {
                instance.addPersistentModifier(modifier);
            } else {
                instance.addTemporaryModifier(modifier);
            }
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

    private static void applyStatusEffect(ServerPlayerEntity p, StatusEffectDef def, boolean showParticles) {
        Registries.STATUS_EFFECT.getEntry(def.effect()).ifPresentOrElse(effect -> {
            int duration = def.durationTicks() < 0 ? StatusEffectInstance.INFINITE : def.durationTicks();
            // ambient=false (not a beacon-style effect); showParticles is caller's choice - false for a
            // permanent racial buff (avoid endless clutter), true for a context-driven trait that should
            // visibly announce itself; showIcon=true always (still listed in the HUD).
            p.addStatusEffect(new StatusEffectInstance(effect, duration, def.amplifier(), false, showParticles, true));
        }, () -> Kindreds.LOGGER.warn("[Kindreds] unknown status effect id '{}'", def.effect()));
    }

    // --- Dynamic (runtime-computed) modifiers --------------------------------------------------

    /**
     * Sets a <b>temporary</b>, node-tagged attribute modifier to a runtime-computed {@code amount},
     * replacing any prior value under the same {@code key} (remove-then-add, so it never throws on a
     * duplicate id). Temporary means it isn't saved and vanishes on relog - correct for a value that a
     * tick handler recomputes continuously (e.g. an orc's War-pack bonus scaling with nearby allies).
     * An {@code amount} of 0 just clears it. Distinct from {@link #applyContextual}'s data-driven
     * {@link AttributeMod}: here the caller supplies the number, not a JSON record.
     */
    public static void setDynamicModifier(ServerPlayerEntity p, Identifier attributeId, String key,
                                          double amount, EntityAttributeModifier.Operation operation) {
        Registries.ATTRIBUTE.getEntry(attributeId).ifPresent(attribute -> {
            EntityAttributeInstance instance = p.getAttributeInstance(attribute);
            if (instance == null) {
                return;
            }
            Identifier id = attributeModifierId(key, attributeId.getPath());
            instance.removeModifier(id);
            if (amount != 0.0) {
                instance.addTemporaryModifier(new EntityAttributeModifier(id, amount, operation));
            }
        });
    }

    /** Removes a modifier previously installed by {@link #setDynamicModifier} under {@code key}. */
    public static void clearDynamicModifier(ServerPlayerEntity p, Identifier attributeId, String key) {
        Registries.ATTRIBUTE.getEntry(attributeId).ifPresent(attribute -> {
            EntityAttributeInstance instance = p.getAttributeInstance(attribute);
            if (instance != null) {
                instance.removeModifier(attributeModifierId(key, attributeId.getPath()));
            }
        });
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
            // showParticles=true: an active ability is a deliberate, cooldown-gated cast - the player
            // pressed a key and must SEE the buff land, unlike a silent always-on passive.
            applyStatusEffect(p, effect, true);
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
