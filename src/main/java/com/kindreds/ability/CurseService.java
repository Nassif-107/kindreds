package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.data.ability.CurseDef;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Applies the drawback a {@link CurseDef} names, for the <b>unconditional</b> case ({@code
 * when=""}) only - a nonblank {@code when} (contextual curse) is instead applied/removed
 * dynamically by {@code CurseContextService}'s server tick, and this class's {@link #apply}/
 * {@link #remove} are no-ops for it (see {@link CurseDef#when()}'s javadoc for the split).
 *
 * <h2>Two dispatch paths (Task 12 Stage A firmed up the schema)</h2>
 * <ul>
 *     <li><b>{@link CurseDef#effect()} present</b> (new content): the drawback is a plain
 *     {@code AbilityDef} (typically an attribute modifier or status effect) applied/reversed via
 *     {@link AbilityApplier#apply}/{@link AbilityApplier#removeNode} exactly like any other node
 *     ability - {@code curseId} is then just a descriptive label, not consulted for dispatch.</li>
 *     <li><b>{@link CurseDef#effect()} absent</b> (pre-Task-12 JSON, kept for backward
 *     compatibility): falls back to the original hardcoded {@code curseId} dispatch below
 *     ({@code frailty}/{@code clumsiness}/{@code sluggishness}).</li>
 * </ul>
 *
 * <p>Each known legacy {@code curseId} is implemented as a persistent attribute modifier, reusing
 * {@link AbilityApplier}'s node-tagged id scheme ({@code kindreds:node/<nodeId>/<attrPath>}) so
 * it is found and removed again by {@link AbilityApplier#removeNode} (via {@link #remove}) -
 * used both for a full per-ability respec and, for a contextual curse's {@code effect} payload,
 * on a single node's context-ending/unowned transition ({@code CurseContextService}) - without any
 * curse-specific bookkeeping beyond knowing which attribute each {@code curseId} targets.
 *
 * <p><b>Config gating:</b> the brief ties curses to the server's {@code enableCurses} config
 * flag. {@code KindredsConfig} (Task 2) is not yet loaded/held anywhere at runtime as of this
 * task (no task has wired a live instance into the running server) - that wiring is out of this
 * task's scope. Once it exists, the natural place to gate is the call site in
 * {@code RequestUnlockC2S} (skip invoking {@link #apply} for {@link CurseDef} abilities when the
 * config's {@code enableCurses} is false), not here.
 */
public final class CurseService {
    private CurseService() {
    }

    /** Applies {@code curse}'s drawback to {@code player}, tagged with {@code nodeId} - unless
     * {@code curse.when()} is nonblank, in which case this is a no-op (see the class javadoc):
     * that curse's lifecycle belongs to {@code CurseContextService} instead. Unknown legacy curse
     * ids are logged and otherwise ignored (fail safe, not fail loud - a bad datapack entry
     * shouldn't break the rest of the unlock). */
    public static void apply(ServerPlayerEntity player, CurseDef curse, String nodeId) {
        if (!curse.when().isEmpty()) {
            return;
        }
        if (curse.effect().isPresent()) {
            AbilityApplier.apply(player, curse.effect().get(), nodeId);
            return;
        }
        applyLegacy(player, curse, nodeId);
    }

    private static void applyLegacy(ServerPlayerEntity player, CurseDef curse, String nodeId) {
        switch (curse.curseId()) {
            case "frailty" -> attributeDrawback(player, EntityAttributes.MAX_HEALTH,
                    -2.0 * curse.severity(), EntityAttributeModifier.Operation.ADD_VALUE, nodeId, curse.curseId());
            case "clumsiness" -> attributeDrawback(player, EntityAttributes.ATTACK_DAMAGE,
                    -0.5 * curse.severity(), EntityAttributeModifier.Operation.ADD_VALUE, nodeId, curse.curseId());
            case "sluggishness" -> attributeDrawback(player, EntityAttributes.MOVEMENT_SPEED,
                    -0.02 * curse.severity(), EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, nodeId, curse.curseId());
            default -> Kindreds.LOGGER.warn("[Kindreds] node {} references unknown curse id '{}'", nodeId, curse.curseId());
        }
    }

    private static void attributeDrawback(ServerPlayerEntity player, RegistryEntry<EntityAttribute> attribute,
                                           double amount, EntityAttributeModifier.Operation op,
                                           String nodeId, String curseId) {
        EntityAttributeInstance instance = player.getAttributeInstance(attribute);
        if (instance == null) {
            Kindreds.LOGGER.warn("[Kindreds] curse '{}' on node {} targets an attribute the player has no instance for",
                    curseId, nodeId);
            return;
        }
        String attrPath = AbilityApplier.attributePath(attribute);
        if (attrPath == null) {
            return;
        }
        Identifier id = AbilityApplier.attributeModifierId(nodeId, attrPath);
        instance.addPersistentModifier(new EntityAttributeModifier(id, amount, op));
    }

    /** Reverses {@code curse}'s drawback on {@code player} - mirrors {@link #apply}'s dispatch
     * (effect-payload vs. legacy {@code curseId}) but removes rather than adds. Used by {@link
     * AbilityApplier#removeNode} for a full per-ability respec; a no-op when {@code curse.when()}
     * is nonblank (contextual curses aren't tracked as "always applied" here - see the class
     * javadoc), and unknown legacy curse ids are logged and otherwise ignored, same as
     * {@link #apply}. */
    public static void remove(ServerPlayerEntity player, CurseDef curse, String nodeId) {
        if (!curse.when().isEmpty()) {
            return;
        }
        if (curse.effect().isPresent()) {
            AbilityApplier.removeNode(player, List.of(curse.effect().get()), nodeId);
            return;
        }
        removeLegacy(player, curse, nodeId);
    }

    private static void removeLegacy(ServerPlayerEntity player, CurseDef curse, String nodeId) {
        switch (curse.curseId()) {
            case "frailty" -> attributeDrawbackRemoval(player, EntityAttributes.MAX_HEALTH, nodeId);
            case "clumsiness" -> attributeDrawbackRemoval(player, EntityAttributes.ATTACK_DAMAGE, nodeId);
            case "sluggishness" -> attributeDrawbackRemoval(player, EntityAttributes.MOVEMENT_SPEED, nodeId);
            default -> Kindreds.LOGGER.warn(
                    "[Kindreds] node {} references unknown curse id '{}' during removal", nodeId, curse.curseId());
        }
    }

    private static void attributeDrawbackRemoval(ServerPlayerEntity player, RegistryEntry<EntityAttribute> attribute,
                                                  String nodeId) {
        EntityAttributeInstance instance = player.getAttributeInstance(attribute);
        if (instance == null) {
            return;
        }
        String attrPath = AbilityApplier.attributePath(attribute);
        if (attrPath == null) {
            return;
        }
        instance.removeModifier(AbilityApplier.attributeModifierId(nodeId, attrPath));
    }
}
