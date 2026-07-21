package com.kindreds.threat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * How dangerous a mob is on its own terms, and which family it belongs to.
 *
 * <p>Danger is deliberately crude - health times damage - because it is only ever used as a
 * <em>ratio</em> against what a player of a given threat should be handling. It never needs to be
 * accurate, only ordered: a cave troll must outrank a chicken.
 */
public final class MobDanger {
    private MobDanger() {
    }

    /** Danger of a mob at its base, unscaled values. */
    public static double of(LivingEntity entity) {
        double health = entity.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
        double damage = entity.getAttributes().hasAttribute(EntityAttributes.ATTACK_DAMAGE)
                ? entity.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE) : 1.0;
        return Math.max(1.0, health * Math.max(1.0, damage));
    }

    /** What a player at {@code threat} should be able to handle - the yardstick danger is judged by. */
    public static double expectedAt(float threat) {
        // a zombie is 20 x 3 = 60; a cave troll is far above. Linear from a zombie to roughly ten of
        // them across the whole threat range, which is enough to order "trivial" against "serious".
        return 60.0 + 540.0 * (Math.max(0f, Math.min(100f, threat)) / 100f);
    }

    /**
     * The family a mob belongs to, for per-family competence. Resolved from the entity id rather than
     * from tags in phase 1: the tag files land with the replacement ladder in phase 3, and a string
     * here keeps this phase from depending on data that does not exist yet.
     */
    public static String family(LivingEntity entity) {
        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        return family(id.getPath());
    }

    /**
     * The id-path string-matching core of {@link #family(LivingEntity)}, split out so the classifier
     * can be proved by unit test with no Minecraft on the classpath - an entity's registry id is not
     * obtainable without a bootstrapped game, so the public overload above is a thin resolve-then-
     * delegate wrapper and this is where the actual logic (and its test coverage) lives.
     */
    static String family(String path) {
        if (path.contains("troll") || path.equals("giant")) {
            return "trolls";
        }
        if (path.contains("spider") || path.contains("shelob")) {
            return "spiders";
        }
        if (path.contains("warg") || path.equals("wolf")) {
            return "wargs";
        }
        if (path.contains("orc") || path.contains("uruk") || path.contains("goblin")
                || path.contains("snaga") || path.equals("npc")) {
            return "orc_kin";
        }
        if (path.contains("zombie") || path.contains("skeleton") || path.equals("husk")
                || path.equals("drowned") || path.contains("wither")) {
            return "undead";
        }
        return "other";
    }

    /** Whether this mod scales, and takes evidence from, this entity at all. */
    public static boolean isInScope(Entity entity) {
        return entity instanceof Monster;
    }
}
