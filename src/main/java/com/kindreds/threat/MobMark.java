package com.kindreds.threat;

import com.kindreds.Kindreds;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;

/**
 * Everything phase 2 needs to remember about one mob, persisted on the entity itself so it survives
 * chunk unload: how it entered the world (the {@code SpawnReason} name, captured by
 * {@code MobEntityInitializeMixin} at the only moment it exists), whether it has already been scaled
 * (the idempotence guard - {@code ENTITY_LOAD} fires again on every reload), its elite identity, and
 * whether it is itself an escort (escorts never escort).
 *
 * <p>Immutable record with with-ers rather than a mutable bean: attachment reads hand out the stored
 * instance, and a mutable one shared between the netty thread and the server thread would be the
 * same CME hazard {@code ThreatState.copy()} exists to prevent.
 */
public record MobMark(String spawnReason, boolean scaled, String eliteAbility, String eliteName,
                      boolean escort) {

    /** The inert mark: no spawn reason recorded, unscaled, no elite identity, not an escort - what
     * {@link #of} hands back for any entity with nothing attached. */
    public static final MobMark DEFAULT = new MobMark("", false, "", "", false);

    /** Every field {@code optionalFieldOf}, defaulted to {@link #DEFAULT}'s own values, so a mob
     * saved before this record gained a field - or before phase 2 existed at all - decodes cleanly
     * instead of failing to load. */
    public static final Codec<MobMark> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("spawn_reason", "").forGetter(MobMark::spawnReason),
            Codec.BOOL.optionalFieldOf("scaled", false).forGetter(MobMark::scaled),
            Codec.STRING.optionalFieldOf("elite_ability", "").forGetter(MobMark::eliteAbility),
            Codec.STRING.optionalFieldOf("elite_name", "").forGetter(MobMark::eliteName),
            Codec.BOOL.optionalFieldOf("escort", false).forGetter(MobMark::escort)
    ).apply(i, MobMark::new));

    public static final AttachmentType<MobMark> KEY =
            AttachmentRegistry.createPersistent(Identifier.of(Kindreds.MOD_ID, "mob_mark"), CODEC);

    /** The stored mark, or {@link #DEFAULT} - never null, never creates storage for unmarked mobs. */
    public static MobMark of(Entity entity) {
        MobMark mark = entity.getAttached(KEY);
        return mark == null ? DEFAULT : mark;
    }

    /** Stores {@code mark} on {@code entity}, replacing whatever was attached before. */
    public static void set(Entity entity, MobMark mark) {
        entity.setAttached(KEY, mark);
    }

    /** Whether this mob was promoted - {@link #eliteAbility} is the tell, since an unpromoted mob
     * never has one set. */
    public boolean elite() {
        return !eliteAbility.isEmpty();
    }

    /** A copy with {@link #spawnReason} replaced - everything else carried over unchanged. */
    public MobMark withSpawnReason(String reason) { return new MobMark(reason, scaled, eliteAbility, eliteName, escort); }
    /** A copy with {@link #scaled} replaced - everything else carried over unchanged. */
    public MobMark withScaled(boolean s) { return new MobMark(spawnReason, s, eliteAbility, eliteName, escort); }
    /** A copy with {@link #eliteAbility} and {@link #eliteName} replaced together - the two always
     * change as a pair, so there is no single-field with-er for either. */
    public MobMark withElite(String ability, String name) { return new MobMark(spawnReason, scaled, ability, name, escort); }
    /** A copy with {@link #escort} replaced - everything else carried over unchanged. */
    public MobMark withEscort(boolean e) { return new MobMark(spawnReason, scaled, eliteAbility, eliteName, e); }
}
