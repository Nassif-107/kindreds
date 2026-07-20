package com.kindreds.ability;

import com.kindreds.data.ability.ActiveAbilityDef;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>thematic</b> half of an active ability: beyond the self-buff {@link ActiveAbilityDef#effects()}
 * (applied by {@link AbilityApplier#applyActiveEffect}), a handful of actives DO something in the world
 * - fire a volley of arrows, burst light that burns the undead, loose a shockwave. Handlers are keyed
 * by the ability id's <b>path</b> ({@code kindreds:galadhrim_volley} -&gt; {@code galadhrim_volley}) so
 * the tree/data just names the ability and this class gives it teeth. An ability with no handler here
 * simply grants its effects (a perfectly good self-buff active); adding a new world-effect is one entry
 * in {@link #HANDLERS}.
 */
public final class ActiveAbilityHandlers {
    private ActiveAbilityHandlers() {
    }

    @FunctionalInterface
    private interface Handler {
        void run(ServerPlayerEntity player, ServerWorld world);
    }

    private static final Map<String, Handler> HANDLERS = new HashMap<>();

    static {
        HANDLERS.put("galadhrim_volley", (p, w) -> volley(p, w, 5, 6.0f));
        HANDLERS.put("hail_of_arrows", (p, w) -> volley(p, w, 4, 5.0f)); // Gondor's massed volley (below the Elf's)
        HANDLERS.put("arrow_of_the_eldar", (p, w) -> piercingShot(p, w, 14.0f));
        HANDLERS.put("star_glass", (p, w) -> phialBurst(p, w, 8.0));
        HANDLERS.put("light_of_the_phial", (p, w) -> phialBurst(p, w, 10.0));
        HANDLERS.put("durins_wrath", (p, w) -> shockwave(p, w, 5.0, 8.0f));
        HANDLERS.put("savage_swing", (p, w) -> shockwave(p, w, 4.0, 7.0f)); // Uruk scimitar sweep (AoE cleave)
        HANDLERS.put("skulk", (p, w) -> skulk(p, w, 12.0));
        HANDLERS.put("vanish", (p, w) -> skulk(p, w, 12.0));           // Hobbit "art of disappearing"
        HANDLERS.put("throw_stone", (p, w) -> throwStone(p, w, 1, 6.0f));
        HANDLERS.put("sling_stones", (p, w) -> throwStone(p, w, 3, 5.0f));
        HANDLERS.put("good_cheer", (p, w) -> healingSong(p, w, 10.0));  // a hobbit feast heartens the company
        HANDLERS.put("goblin_bomb", (p, w) -> goblinBomb(p, w, 3.5, 8.0f));
        HANDLERS.put("cluster_bomb", (p, w) -> goblinBomb(p, w, 5.0, 11.0f));
        HANDLERS.put("blood_frenzy", (p, w) -> dreadNova(p, w, 6.0));
        HANDLERS.put("song_of_luthien", (p, w) -> enchantSong(p, w, 9.0));
        HANDLERS.put("song_of_healing", (p, w) -> healingSong(p, w, 10.0));
        HANDLERS.put("masters_forge", (p, w) -> mastersForge(p, w));
        HANDLERS.put("durins_ward", (p, w) -> runeWard(p, w, 6.0));
        HANDLERS.put("anduril", (p, w) -> anduril(p, w, 8.0));
        HANDLERS.put("hands_of_the_king", (p, w) -> handsOfTheKing(p, w, 10.0));
        HANDLERS.put("war_horn", (p, w) -> warHorn(p, w, 12.0));
        HANDLERS.put("ride_of_the_rohirrim", (p, w) -> rideOfTheRohirrim(p, w, 8.0));
        HANDLERS.put("call_of_the_wild", (p, w) -> summonWolves(p, w, 2, false));
        HANDLERS.put("summon_the_pack", (p, w) -> summonWolves(p, w, 4, false));
        HANDLERS.put("huan_the_hound", (p, w) -> summonWolves(p, w, 1, true));
        HANDLERS.put("call_of_wargs", (p, w) -> summonWolves(p, w, 2, false)); // Uruk warg-pack (wolves as wargs)
        HANDLERS.put("warg_pack", (p, w) -> summonWolves(p, w, 4, false));
    }

    /** Runs the world-effect for {@code def}, if it has one. Called by {@link ActiveAbilityService}
     * right after the self-buff effects + cast feedback. */
    public static void run(ServerPlayerEntity player, ActiveAbilityDef def, ServerWorld world) {
        Handler handler = HANDLERS.get(def.abilityId().getPath());
        if (handler != null) {
            handler.run(player, world);
        }
    }

    // --- Handlers -------------------------------------------------------------------------------

    /** A fanned volley of {@code count} arrows loosed from the player's aim. */
    private static void volley(ServerPlayerEntity p, ServerWorld world, int count, float damage) {
        float yaw = p.getYaw();
        float pitch = p.getPitch();
        for (int i = 0; i < count; i++) {
            float spread = (i - (count - 1) / 2f) * 8f; // degrees, symmetric fan
            ArrowEntity arrow = new ArrowEntity(world, p, new ItemStack(Items.ARROW), null);
            arrow.setVelocity(p, pitch, yaw + spread, 0f, 2.6f, 1.0f);
            arrow.setDamage(damage);
            arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED; // don't litter arrows
            world.spawnEntity(arrow);
        }
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0f, 1.1f);
    }

    /** One heavy, piercing arrow - the Eldar's slaying shot. */
    private static void piercingShot(ServerPlayerEntity p, ServerWorld world, float damage) {
        ArrowEntity arrow = new ArrowEntity(world, p, new ItemStack(Items.ARROW), null);
        arrow.setVelocity(p, p.getPitch(), p.getYaw(), 0f, 3.5f, 0.0f);
        arrow.setDamage(damage);
        arrow.setCritical(true);
        arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
        world.spawnEntity(arrow);
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0f, 0.7f);
    }

    /** A burst of hallowed light: undead nearby take fire and glow, and the light shines out. */
    private static void phialBurst(ServerPlayerEntity p, ServerWorld world, double radius) {
        Box box = p.getBoundingBox().expand(radius);
        List<LivingEntity> nearby = world.getEntitiesByClass(LivingEntity.class, box, e -> e != p && e.isAlive());
        for (LivingEntity e : nearby) {
            if (e.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) {
                e.setOnFireForTicks(100);
                e.damage(world, world.getDamageSources().magic(), 4.0f);
            }
        }
        world.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getBodyY(1.0), p.getZ(), 80,
                radius / 2.5, 1.0, radius / 2.5, 0.08);
        world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.9f, 1.6f);
    }

    /** A ground-shaking shockwave: nearby hostiles are hurled back and hurt. */
    private static void shockwave(ServerPlayerEntity p, ServerWorld world, double radius, float damage) {
        Box box = p.getBoundingBox().expand(radius);
        Vec3d center = p.getPos();
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                x -> x != p && x.isAlive() && x instanceof Monster)) {
            Vec3d push = e.getPos().subtract(center).normalize().multiply(1.3);
            e.addVelocity(push.x, 0.45, push.z);
            e.velocityModified = true;
            e.damage(world, p.getDamageSources().playerAttack(p), damage);
        }
        world.spawnParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 0.4, p.getZ(), 6,
                radius / 2.5, 0.2, radius / 2.5, 0.0);
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.9f, 0.7f);
    }

    /** The enchantress's song (Lúthien): nearby foes are bound in slumberous weakness - slowed,
     * weakened, and reeling. The song that unmade the tower of Sauron. */
    private static void enchantSong(ServerPlayerEntity p, ServerWorld world, double radius) {
        Box box = p.getBoundingBox().expand(radius);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                x -> x != p && x.isAlive() && x instanceof Monster)) {
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 160, 2));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 160, 1));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0));
        }
        world.spawnParticles(ParticleTypes.NOTE, p.getX(), p.getBodyY(1.1), p.getZ(), 30, radius / 3, 0.6, radius / 3, 1.0);
        world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.7f);
    }

    /** A song of healing (the House of Elrond): the singer and nearby allied players are mended and
     * shielded. */
    private static void healingSong(ServerPlayerEntity p, ServerWorld world, double radius) {
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 160, 1));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 400, 1));
        Box box = p.getBoundingBox().expand(radius);
        for (ServerPlayerEntity ally : world.getEntitiesByClass(ServerPlayerEntity.class, box,
                a -> a != p && a.isAlive())) {
            ally.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 160, 1));
            ally.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 400, 1));
        }
        world.spawnParticles(ParticleTypes.NOTE, p.getX(), p.getBodyY(1.1), p.getZ(), 24, radius / 3, 0.6, radius / 3, 1.0);
        world.spawnParticles(ParticleTypes.HEART, p.getX(), p.getBodyY(1.0), p.getZ(), 8, 0.5, 0.5, 0.5, 0.1);
        world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.5f);
    }

    /** Call of the wild (the Elf-friend of beasts): a pack of wolves answers, tamed to your side and
     * set upon the nearest foe. Calling again dismisses the old pack, so they never accumulate. Huan
     * is a single mighty hound, hallowed and strong. */
    private static void summonWolves(ServerPlayerEntity p, ServerWorld world, int count, boolean huan) {
        // Dismiss the caster's existing summoned beasts so a new call replaces the old pack.
        Box near = p.getBoundingBox().expand(48.0);
        for (WolfEntity old : world.getEntitiesByClass(WolfEntity.class, near,
                w -> w.getCommandTags().contains(SUMMON_TAG) && p.equals(w.getOwner()))) {
            old.discard();
        }
        LivingEntity foe = world.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(16.0),
                        e -> e != p && e.isAlive() && e instanceof Monster).stream()
                .min((a, b) -> Double.compare(a.squaredDistanceTo(p), b.squaredDistanceTo(p)))
                .orElse(null);
        for (int i = 0; i < count; i++) {
            WolfEntity wolf = EntityType.WOLF.create(world, SpawnReason.MOB_SUMMONED);
            if (wolf == null) {
                continue;
            }
            double ang = (Math.PI * 2 * i) / Math.max(1, count);
            wolf.refreshPositionAndAngles(p.getX() + Math.cos(ang) * 1.5, p.getY(), p.getZ() + Math.sin(ang) * 1.5,
                    p.getYaw(), 0f);
            wolf.setOwner(p);
            wolf.setTamed(true, true);
            wolf.addCommandTag(SUMMON_TAG);
            if (foe != null) {
                wolf.setTarget(foe);
            }
            if (huan) {
                wolf.setCustomName(net.minecraft.text.Text.literal("Huan"));
                wolf.setCustomNameVisible(true);
                wolf.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 2400, 2, false, false, true));
                wolf.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 2400, 1, false, false, true));
                wolf.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 2400, 1, false, false, true));
            }
            world.spawnEntity(wolf);
        }
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getBodyY(0.8), p.getZ(), 20, 1.2, 0.4, 1.2, 0.1);
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_FOX_AGGRO, SoundCategory.PLAYERS, 1.0f, huan ? 0.7f : 1.0f);
    }

    private static final String SUMMON_TAG = "kindreds_summon";

    /** A rune-ward of Durin (graven wards, as on the West-gate): a glowing rune-circle flares out,
     * hurling back the servants of the dark and sapping their strength. The caster's own protection
     * comes from the ability's effects; this is the ward that turns the foe. */
    private static void runeWard(ServerPlayerEntity p, ServerWorld world, double radius) {
        Box box = p.getBoundingBox().expand(radius);
        Vec3d centre = p.getPos();
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                x -> x != p && x.isAlive() && x instanceof Monster)) {
            Vec3d push = e.getPos().subtract(centre).normalize().multiply(1.1);
            e.addVelocity(push.x, 0.35, push.z);
            e.velocityModified = true;
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 140, 1));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 140, 0));
        }
        // A ring of glowing runes at the caster's feet.
        for (int i = 0; i < 36; i++) {
            double a = (Math.PI * 2 * i) / 36;
            world.spawnParticles(ParticleTypes.ENCHANT, p.getX() + Math.cos(a) * radius * 0.8, p.getBodyY(0.1),
                    p.getZ() + Math.sin(a) * radius * 0.8, 2, 0.0, 0.3, 0.0, 0.02);
        }
        world.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getBodyY(1.0), p.getZ(), 24, 0.4, 0.6, 0.4, 0.04);
        world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 1.0f, 0.8f);
    }

    /** The Ride of the Rohirrim: the rider and nearby allies surge forward (Speed, and for the caster
     * Strength and Resistance), and if you charge mounted, the foes in your path are trampled - hurled
     * back and hurt. "Ride now, ride now! Ride to Gondor!" */
    private static void rideOfTheRohirrim(ServerPlayerEntity p, ServerWorld world, double radius) {
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 300, 1, false, false, true));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 300, 0, false, false, true));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 300, 0, false, false, true));
        Box box = p.getBoundingBox().expand(radius);
        for (ServerPlayerEntity ally : world.getEntitiesByClass(ServerPlayerEntity.class, box,
                a -> a != p && a.isAlive())) {
            ally.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 300, 1, false, false, true));
        }
        if (p.hasVehicle()) {
            Vec3d centre = p.getPos();
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                    x -> x != p && x.isAlive() && x instanceof Monster)) {
                Vec3d push = e.getPos().subtract(centre).normalize().multiply(1.2);
                e.addVelocity(push.x, 0.4, push.z);
                e.velocityModified = true;
                e.damage(world, p.getDamageSources().playerAttack(p), 6.0f);
            }
        }
        world.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.2, p.getZ(), 30, radius / 3, 0.2, radius / 3, 0.05);
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 1.0f, 1.5f);
    }

    /** The Hands of the King are the hands of a healer (athelas/kingsfoil): the caster and nearby allies
     * are healed and their hurts and afflictions cured. */
    private static void handsOfTheKing(ServerPlayerEntity p, ServerWorld world, double radius) {
        healAndCure(p);
        Box box = p.getBoundingBox().expand(radius);
        for (ServerPlayerEntity ally : world.getEntitiesByClass(ServerPlayerEntity.class, box,
                a -> a != p && a.isAlive())) {
            healAndCure(ally);
        }
        world.spawnParticles(ParticleTypes.HEART, p.getX(), p.getBodyY(1.0), p.getZ(), 12, 0.6, 0.6, 0.6, 0.1);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getBodyY(1.0), p.getZ(), 20, 0.6, 0.8, 0.6, 0.1);
        world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.6f);
    }

    /** Heal a chunk of health, grant a short Regeneration, and strip every harmful effect (the healing
     * of the King). */
    private static void healAndCure(net.minecraft.entity.LivingEntity e) {
        e.heal(8.0f);
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 120, 1, false, false, true));
        java.util.List<net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect>> bad =
                new java.util.ArrayList<>();
        for (StatusEffectInstance inst : e.getStatusEffects()) {
            if (inst.getEffectType().value().getCategory() == net.minecraft.entity.effect.StatusEffectCategory.HARMFUL) {
                bad.add(inst.getEffectType());
            }
        }
        bad.forEach(e::removeStatusEffect);
    }

    /** A great war-horn (the horns of the Mark): allies are heartened (Strength, Resistance, Speed) and
     * the servants of the Enemy are dismayed (Weakness, Slowness). */
    private static void warHorn(ServerPlayerEntity p, ServerWorld world, double radius) {
        Box box = p.getBoundingBox().expand(radius);
        rally(p);
        for (ServerPlayerEntity ally : world.getEntitiesByClass(ServerPlayerEntity.class, box,
                a -> a != p && a.isAlive())) {
            rally(ally);
        }
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                x -> x != p && x.isAlive() && x instanceof Monster)) {
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 160, 0));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 160, 0));
        }
        world.spawnParticles(ParticleTypes.NOTE, p.getX(), p.getBodyY(1.4), p.getZ(), 24, 1.0, 0.4, 1.0, 1.0);
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 1.0f, 1.3f);
    }

    private static void rally(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 300, 0, false, false, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 300, 0, false, false, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 300, 0, false, false, true));
    }

    /** Andúril, the Flame of the West, reforged and drawn: its light throws fear into the servants of
     * the Enemy (Weakness + Slowness), and burns the undead as it burned before the Paths of the Dead.
     * The bearer's own war-fury comes from the ability's effects. */
    private static void anduril(ServerPlayerEntity p, ServerWorld world, double radius) {
        Box box = p.getBoundingBox().expand(radius);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                x -> x != p && x.isAlive() && x instanceof Monster)) {
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 160, 1));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 160, 0));
            if (e.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) {
                e.setOnFireForTicks(120);
                e.damage(world, world.getDamageSources().magic(), 5.0f);
            }
        }
        world.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getBodyY(1.1), p.getZ(), 40, 0.5, 0.7, 0.5, 0.04);
        world.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getBodyY(1.1), p.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
        world.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    /** The master smith's touch (Aulë's craft): every worn or held item is made whole again. */
    private static void mastersForge(ServerPlayerEntity p, ServerWorld world) {
        DwarfSmithing.repairAll(p);
        world.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getBodyY(1.0), p.getZ(), 30, 0.5, 0.7, 0.5, 0.02);
        world.spawnParticles(ParticleTypes.CRIT, p.getX(), p.getBodyY(1.0), p.getZ(), 20, 0.5, 0.7, 0.5, 0.05);
        world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    /** A goblin bomb ("wheels and engines and explosions always delighted them"): a device is lobbed at
     * the nearest foe (or just ahead) and bursts - a real-looking blast that hurts and hurls back
     * everything nearby, but breaks no blocks and spares the goblin who threw it. */
    private static void goblinBomb(ServerPlayerEntity p, ServerWorld world, double radius, float damage) {
        Vec3d ahead = p.getPos().add(p.getRotationVector().multiply(4.0)).add(0, 0.5, 0);
        LivingEntity foe = world.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(16.0),
                        e -> e != p && e.isAlive() && e instanceof Monster).stream()
                .min((a, b) -> Double.compare(a.squaredDistanceTo(p), b.squaredDistanceTo(p))).orElse(null);
        Vec3d c = foe != null ? foe.getPos().add(0, 0.5, 0) : ahead;
        Box box = new Box(c.subtract(radius, radius, radius), c.add(radius, radius, radius));
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                x -> x != p && x.isAlive() && x instanceof Monster)) {
            Vec3d push = e.getPos().subtract(c).normalize().multiply(1.2);
            e.addVelocity(push.x, 0.4, push.z);
            e.velocityModified = true;
            e.damage(world, p.getDamageSources().playerAttack(p), damage);
        }
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 10, radius / 3, radius / 3, radius / 3, 0.0);
        world.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_GENERIC_EXPLODE.value(),
                SoundCategory.PLAYERS, 1.2f, 1.1f);
    }

    /** Thrown stones - the hobbits' one deadly art ("they could throw stones with deadly accuracy").
     * A hard, fast stone (or a sling-fed flurry) loosed where the hobbit is looking. */
    private static void throwStone(ServerPlayerEntity p, ServerWorld world, int count, float damage) {
        float yaw = p.getYaw();
        float pitch = p.getPitch();
        for (int i = 0; i < count; i++) {
            float spread = count > 1 ? (i - (count - 1) / 2f) * 5f : 0f;
            ArrowEntity stone = new ArrowEntity(world, p, new ItemStack(Items.ARROW), null);
            stone.setVelocity(p, pitch, yaw + spread, 0f, 3.0f, 0.5f);
            stone.setDamage(damage);
            stone.setCritical(true); // the hard "thunk" of a well-thrown stone
            stone.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
            world.spawnEntity(stone);
        }
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 1.0f, 0.8f);
    }

    /** Skulk: the slave-orc melts into the shadows - it goes unseen (Invisibility) and quick (Speed),
     * and the foes hunting it lose the scent (their aim breaks off). The coward's escape. */
    private static void skulk(ServerPlayerEntity p, ServerWorld world, double radius) {
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 200, 0, false, false, true));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 200, 1, false, false, true));
        Box box = p.getBoundingBox().expand(radius);
        for (net.minecraft.entity.mob.MobEntity m : world.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class,
                box, e -> e.getTarget() == p)) {
            m.setTarget(null);
        }
        world.spawnParticles(ParticleTypes.SMOKE, p.getX(), p.getBodyY(0.8), p.getZ(), 40, 0.4, 0.6, 0.4, 0.02);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getBodyY(0.8), p.getZ(), 12, 0.3, 0.4, 0.3, 0.01);
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_FOX_AMBIENT, SoundCategory.PLAYERS, 0.7f, 0.6f);
    }

    /** A wave of dread: nearby hostiles are weakened and slowed as the frenzy takes the caster. */
    private static void dreadNova(ServerPlayerEntity p, ServerWorld world, double radius) {
        Box box = p.getBoundingBox().expand(radius);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                x -> x != p && x.isAlive() && x instanceof Monster)) {
            e.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.WEAKNESS, 120, 0));
            e.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.SLOWNESS, 120, 0));
        }
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, p.getX(), p.getBodyY(1.0), p.getZ(), 20,
                radius / 3, 0.6, radius / 3, 0.02);
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.5f, 1.4f);
    }
}
