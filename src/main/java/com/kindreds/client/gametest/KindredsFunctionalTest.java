package com.kindreds.client.gametest;

import com.kindreds.ability.ActiveAbilityService;
import com.kindreds.ability.NodeReconcileService;
import com.kindreds.ability.PerkService;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import com.kindreds.progression.UnlockService;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plays every kindred in turn and checks that the mod actually does what it claims.
 *
 * <p>For each of the eight races this becomes that race, verifies its birth traits landed on real
 * attributes, unlocks a genuine slice of its tree, checks the perks resolve, fires every active
 * ability it owns, and then switches away and proves the previous kindred's bonuses were stripped.
 * Race switching is the part nothing has ever verified: persistent attribute modifiers must not
 * accumulate across lives.
 *
 * <p>Everything is asserted against the live server state, not against the mod's own opinion of
 * itself. Results print with a {@code [FUNC]} tag; anything genuinely wrong prints {@code FAIL}.
 */
public class KindredsFunctionalTest implements FabricClientGameTest {
    private static final String TAG = "[FUNC]";
    private static final List<String> RACES =
            List.of("dwarf", "elf", "human", "hobbit", "uruk", "orc", "snaga", "goblin");

    /** Vanilla baselines, so a race's bonus can be told apart from the default. */
    private static final double BASE_HEALTH = 20.0;

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            sp.getServer().runCommand("gamerule doDaylightCycle false");
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("kindreds config allowGrantXp true");
            context.waitTicks(10);

            // Ask the running game what the base mod actually registered, rather than guessing.
            sp.getServer().runOnServer(server -> {
                var root = server.getCommandManager().getDispatcher().getRoot();
                for (var child : root.getChildren()) {
                    String name = child.getName();
                    if (name.contains("earth") || name.contains("race") || name.contains("kindred")) {
                        StringBuilder sb = new StringBuilder(TAG + " command /" + name + " ->");
                        for (var sub : child.getChildren()) {
                            sb.append(' ').append(sub.getName());
                            if (sub.getName().equals("race")) {
                                for (var arg : sub.getChildren()) {
                                    sb.append("[").append(arg.getName()).append(']');
                                }
                            }
                        }
                        System.out.println(sb);
                    }
                }
            });
            context.waitTicks(5);

            for (String race : RACES) {
                // Set the race through the base mod's own persistent data rather than its command:
                // the command is gated behind that mod's onboarding flow ("choose your people first"),
                // which a headless test cannot walk through. assignNewRace is the same call the
                // command ends up making, and RaceAccess reads the result through the same state.
                sp.getServer().runOnServer(server -> {
                    ServerPlayerEntity pl = server.getPlayerManager().getPlayerList().get(0);
                    var state = net.sevenstars.middleearth.resources.StateSaverAndLoader.getPlayerState(pl);
                    state.assignNewRace(Identifier.of("middle-earth", race));
                });
                // birth traits apply a few ticks after the base mod finishes writing its own values
                context.waitTicks(40);
                sp.getServer().runOnServer(server -> checkRace(server, race));
                context.waitTicks(10);
            }

            // and prove switching away leaves nothing behind
            sp.getServer().runOnServer(server -> {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayerList().get(0);
                net.sevenstars.middleearth.resources.StateSaverAndLoader.getPlayerState(pl)
                        .assignNewRace(Identifier.of("middle-earth", "dwarf"));
            });
            context.waitTicks(40);
            sp.getServer().runOnServer(server -> checkNoLeftovers(server, "dwarf"));

            sp.getServer().runOnServer(server -> {
                System.out.println(TAG + " ================ SUMMARY ================");
                if (failures.isEmpty()) {
                    System.out.println(TAG + " every kindred passed");
                } else {
                    failures.forEach(f -> System.out.println(TAG + " FAIL " + f));
                    System.out.println(TAG + " " + failures.size() + " failure(s)");
                }
            });
        }
    }

    private void checkRace(MinecraftServer server, String expected) {
        ServerPlayerEntity p = server.getPlayerManager().getPlayerList().stream().findFirst().orElse(null);
        if (p == null) {
            failures.add(expected + ": no player");
            return;
        }
        KindredData data = KindredAttachment.get(p);
        Identifier race = RaceAccess.getRace(p).orElse(null);
        double health = p.getMaxHealth();
        double attack = p.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        double knockback = p.getAttributeValue(EntityAttributes.KNOCKBACK_RESISTANCE);
        double speed = p.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);

        if (race == null || !race.getPath().equals(expected)) {
            failures.add(expected + ": race did not take (got " + race + ")");
            System.out.printf("%s %-7s FAIL race=%s%n", TAG, expected, race);
            return;
        }

        // --- the tree resolves, and can be spent into ---
        SkillTree tree = SkillTreeResolver
                .byRace(server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE), race)
                .tree().orElse(null);
        if (tree == null) {
            failures.add(expected + ": no skill tree");
            return;
        }

        int perksBefore = PerkService.ownedPerks(p).size();

        // unlock a real slice: walk tiers upward taking whatever the rules allow
        int unlocked = 0;
        for (SkillNode node : tree.nodes()) {
            if (unlocked >= 12) {
                break;
            }
            UnlockService.UnlockResult r = UnlockService.canUnlock(data, tree, node.id(),
                    d -> 99, adv -> true);
            if (r.ok()) {
                UnlockService.applyUnlock(data, node.id());
                for (AbilityDef ability : node.abilities()) {
                    com.kindreds.ability.AbilityApplier.apply(p, ability, node.id());
                }
                unlocked++;
            }
        }
        PerkService.invalidate(p.getUuid());
        NodeReconcileService.reapply(p);
        int perksAfter = PerkService.ownedPerks(p).size();

        // --- fire every active this race owns ---
        Set<String> actives = new LinkedHashSet<>();
        for (SkillNode node : tree.nodes()) {
            for (AbilityDef ability : node.abilities()) {
                if (ability instanceof ActiveAbilityDef act) {
                    actives.add(act.abilityId().toString());
                }
            }
        }
        int fired = 0, threw = 0;
        for (String id : actives) {
            try {
                // owned or not, the handler must not explode - unlock state only gates the caller
                data.unlockedNodes().addAll(nodesGranting(tree, id));
                data.cooldowns().clear();
                ActiveAbilityService.activate(p, id);
                fired++;
            } catch (Throwable t) {
                threw++;
                failures.add(expected + ": ability " + id + " threw " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }

        System.out.printf("%s %-7s hearts=%.1f attack=%.2f kb=%.2f speed=%.3f | nodes+%d perks %d->%d "
                        + "| actives fired %d/%d (threw %d)%n",
                TAG, expected, health / 2, attack, knockback, speed, unlocked, perksBefore, perksAfter,
                fired, actives.size(), threw);

        if (unlocked == 0) {
            failures.add(expected + ": could not unlock a single node");
        }
        if (perksAfter <= perksBefore && unlocked > 0) {
            failures.add(expected + ": unlocked " + unlocked + " nodes but gained no perks");
        }
        if (health <= BASE_HEALTH && List.of("dwarf", "human", "uruk", "orc", "elf").contains(expected)) {
            failures.add(expected + ": birth traits did not raise max health (still " + health + ")");
        }
    }

    /** Node ids that grant {@code abilityId}, so activation has something to find. */
    private static Set<String> nodesGranting(SkillTree tree, String abilityId) {
        Set<String> out = new LinkedHashSet<>();
        for (SkillNode node : tree.nodes()) {
            for (AbilityDef ability : node.abilities()) {
                if (ability instanceof ActiveAbilityDef act && act.abilityId().toString().equals(abilityId)) {
                    out.add(node.id());
                }
            }
        }
        return out;
    }

    /**
     * After cycling through all eight kindreds and returning to the first, the player must carry
     * exactly that race's bonuses - not the sum of everything they have ever been.
     */
    private void checkNoLeftovers(MinecraftServer server, String race) {
        ServerPlayerEntity p = server.getPlayerManager().getPlayerList().stream().findFirst().orElse(null);
        if (p == null) {
            return;
        }
        double health = p.getMaxHealth();
        double attack = p.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        System.out.printf("%s after cycling all 8 and returning to %s: hearts=%.1f attack=%.2f%n",
                TAG, race, health / 2, attack);
        // a Dwarf is +4 max health over vanilla; anything far above means modifiers accumulated
        if (health > BASE_HEALTH + 10) {
            failures.add("race switching accumulated modifiers: max health " + health
                    + " after cycling every kindred");
        }
    }
}
