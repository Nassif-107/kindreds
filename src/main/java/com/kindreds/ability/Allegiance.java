package com.kindreds.ability;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.BiPredicate;

/**
 * Who counts as a friend, and who counts as a foe.
 *
 * <p>Every ability in the mod used to answer this with {@code instanceof Monster}, which made the
 * whole arsenal inert in player-versus-player: a goblin bomb at another player's feet did nothing at
 * all, while healing songs cheerfully buffed the person trying to kill you. One place now decides it
 * for everything - AoE damage, auras, aim assist, prey-sense and the dread aura all ask here.
 *
 * <h2>The rule</h2>
 * <ul>
 *   <li>A {@link Monster} is always a foe.</li>
 *   <li>A player is a foe only if the server allows PvP <b>and</b> they are not an ally.</li>
 *   <li>A tamed creature inherits its owner's standing, so you never bomb an ally's wolf.</li>
 *   <li>Anything else (cows, villagers, armour stands) is neither, and abilities leave it alone.</li>
 * </ul>
 *
 * <h2>Allies</h2>
 * Yourself, and anyone on your scoreboard team. Teams are the one grouping vanilla already gives
 * every server, so this works out of the box with {@code /team} and with any team-managing mod.
 *
 * <p>The Fellowship system will add a second source of allegiance - a bond between kindreds that is
 * not a scoreboard team. Rather than have it edit twenty call sites later, it registers itself here
 * through {@link #addAllyRule}: any rule that answers "yes" makes two players allies, and the rest of
 * the mod needs no further change.
 */
public final class Allegiance {
    private Allegiance() {
    }

    /**
     * Extra ally rules, consulted after teams. The Fellowship system will register one of these
     * ({@code Allegiance.addAllyRule(FellowshipService::bonded)}) and every ability in the mod will
     * respect fellowship bonds from that moment, with no other edit anywhere.
     */
    private static final java.util.List<BiPredicate<ServerPlayerEntity, ServerPlayerEntity>> ALLY_RULES =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Registers an additional way for two players to count as allies. */
    public static void addAllyRule(BiPredicate<ServerPlayerEntity, ServerPlayerEntity> rule) {
        if (rule != null) {
            ALLY_RULES.add(rule);
        }
    }

    /** Whether the server permits players to harm one another at all. */
    public static boolean pvpEnabled(ServerPlayerEntity player) {
        return player.getServer() != null && player.getServer().isPvpEnabled();
    }

    /** Yourself, your team, and anyone a registered rule (Fellowship) calls a friend. */
    public static boolean isAlly(ServerPlayerEntity self, PlayerEntity other) {
        if (other == null) {
            return false;
        }
        if (self == other) {
            return true;
        }
        if (self.isTeammate(other)) {
            return true;
        }
        if (other instanceof ServerPlayerEntity sp) {
            for (BiPredicate<ServerPlayerEntity, ServerPlayerEntity> rule : ALLY_RULES) {
                if (rule.test(self, sp) || rule.test(sp, self)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code target} is a legitimate thing for {@code self}'s abilities to hurt.
     *
     * <p>This is the predicate every offensive ability filters on. It deliberately says no to
     * neutral creatures: a Dwarf's shockwave should not launch the cows.
     */
    public static boolean isFoe(ServerPlayerEntity self, Entity target) {
        if (target == self || !(target instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (living instanceof PlayerEntity player) {
            return pvpEnabled(self) && !isAlly(self, player);
        }
        if (living instanceof TameableEntity tame && tame.getOwner() instanceof PlayerEntity owner) {
            // an ally's hound is not a target; a rival's is
            return pvpEnabled(self) && !isAlly(self, owner);
        }
        return living instanceof Monster;
    }

    /**
     * Whether {@code other} should receive a friendly effect - a healing song, a war-horn, an aura.
     *
     * <p>Excludes the caster, who is always handled separately (auras self-apply so they work alone).
     */
    public static boolean isFriend(ServerPlayerEntity self, PlayerEntity other) {
        return other != self && other.isAlive() && isAlly(self, other);
    }
}
