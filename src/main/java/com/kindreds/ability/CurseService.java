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

/**
 * Applies the drawback a {@link CurseDef} names.
 *
 * <p>As authored in Task 3, {@link CurseDef} only carries a {@code curseId} (naming which curse
 * implementation to run) and a {@code severity} (scaling its magnitude) - there is no separate
 * "buff" payload and no situational {@code when} field. This is a P1 simplification versus the
 * originally-envisioned "buff now + contextual drawback later" design: every curse here
 * <b>is</b> the drawback, and it applies unconditionally at unlock time (equivalent to "when"
 * always being empty). A future task that wants a real buff+situational-drawback split would
 * extend {@link CurseDef} with those fields and branch on them here; this class is where that
 * branch would go.
 *
 * <p>Each known {@code curseId} is implemented as a persistent attribute modifier, reusing
 * {@link AbilityApplier}'s node-tagged id scheme ({@code kindreds:node/<nodeId>/<attrPath>}) so
 * it is automatically found and removed by {@link AbilityApplier#removeAll} without any curse-
 * specific bookkeeping.
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

    /** Applies {@code curse}'s drawback to {@code player}, tagged with {@code nodeId}. Unknown
     * curse ids are logged and otherwise ignored (fail safe, not fail loud - a bad datapack entry
     * shouldn't break the rest of the unlock). */
    public static void apply(ServerPlayerEntity player, CurseDef curse, String nodeId) {
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
}
