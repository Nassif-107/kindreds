package com.kindreds.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import com.kindreds.client.screen.TreeRenderer;
import com.kindreds.progression.ProgressionService;
import com.kindreds.data.KindredsRegistries;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side <b>progression feedback</b>: the part that tells the player something happened.
 *
 * <p>Progress used to be invisible - xp accrued silently and you only learned you had points by
 * opening the tree on a hunch. This watches the server-synced mirror each tick and:
 * <ul>
 *   <li>toasts a <b>discipline level-up</b> (with the point it just granted),</li>
 *   <li>exposes {@link #unspentTotal()} so the HUD can show a "spend me" pip,</li>
 *   <li>shows a one-time <b>welcome</b> naming your race and the keys that matter.</li>
 * </ul>
 * All of it derives from data the client already has, so nothing new goes over the wire.
 */
public final class ClientProgress {
    private ClientProgress() {
    }

    private static final Gson GSON = new Gson();
    private static final Type SET_TYPE = new TypeToken<Set<String>>() { }.getType();

    private static SkillTree tree;
    private static Identifier resolvedRace;
    private static Set<Identifier> disciplines = Set.of();

    /** discipline -> last seen level, so we only toast on an actual increase. */
    private static final Map<Identifier, Integer> lastLevel = new HashMap<>();
    private static boolean primed; // first sync just records the baseline (no toast spam on login)
    private static Set<String> welcomedRaces;

    // --- tree resolution (mirrors SkillTreeScreen's, cached per race) --------------------------

    public static SkillTree tree() {
        KindredData data = ClientKindredData.INSTANCE;
        Identifier race = data.race();
        if (race == null) {
            return null;
        }
        if (tree != null && race.equals(resolvedRace)) {
            return tree;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return null;
        }
        resolvedRace = race;
        tree = null;
        resetAnnouncements();
        try {
            Registry<SkillTree> trees = client.world.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
            for (SkillTree candidate : trees) {
                if (candidate.race().equals(race)) {
                    tree = candidate;
                    break;
                }
            }
            if (tree != null) {
                Set<Identifier> ds = new LinkedHashSet<>();
                for (SkillNode n : tree.nodes()) {
                    ds.add(n.cost().disciplineId());
                }
                disciplines = ds;
            }
        } catch (RuntimeException ignored) {
            // registries not synced yet - retried next call
        }
        return tree;
    }

    /**
     * Total unspent discipline points across the player's tree - <b>cached</b>.
     *
     * <p>The HUD pip asks for this every frame, and the underlying {@code pointsSpent} walks every
     * node in the tree per discipline (~400 node visits for a 5-discipline race). Recomputing that at
     * 60fps was pure waste, so the value is refreshed once per {@link #tick} instead and simply read
     * back here.
     */
    public static int unspentTotal() {
        return cachedUnspent;
    }

    private static int cachedUnspent;
    /** Nodes the player could unlock right now, and the ones they have already been told about. */
    private static int cachedReady;
    private static final Set<String> announced = new HashSet<>();
    private static boolean readyPrimed;

    /** How many skills are unlockable right now - the number worth acting on. */
    public static int readyTotal() {
        return cachedReady;
    }
    private static boolean cachedAtCap;
    private static int cachedSpent;
    private static int cachedCap;

    /** True when the player has unspent points but has hit the tree-wide cap - i.e. the points can
     * never be spent without a respec. The HUD says "capped" instead of nagging. */
    public static boolean atCap() {
        return cachedAtCap;
    }

    /** Points committed so far, and the ceiling ({@code 0} = uncapped) - for the tree's readout. */
    public static int spent() {
        return cachedSpent;
    }

    public static int cap() {
        return cachedCap;
    }

    private static void recomputeUnspent(SkillTree t, KindredData data) {
        int sum = 0;
        for (Identifier d : disciplines) {
            sum += Math.max(0, ProgressionService.pointsAvailable(data, t, d));
        }
        cachedUnspent = sum;
        cachedReady = 0;
        cachedCap = com.kindreds.progression.UnlockService.effectiveCap(t, data);
        cachedSpent = com.kindreds.progression.UnlockService.totalPointsSpent(data, t);
        // "At cap" means the cheapest thing left is already unaffordable, not merely that spent==cap:
        // a 1-point node may still fit under the ceiling.
        cachedAtCap = cachedCap > 0 && cachedSpent + cheapestUnowned(t, data) > cachedCap;
    }

    /**
     * Notices when a skill becomes unlockable and says so, once.
     *
     * <p>The points pip only ever said "you have points", which is not the same as "there is
     * something you can take" - a player with points but every prerequisite unmet had no way to know
     * except by opening the tree and hunting. This watches the set of unlockable nodes and speaks the
     * moment it grows.
     *
     * <p>The first pass after joining only records the baseline, or logging in would announce a
     * whole tree at once. A node already announced is never announced again, so respeccing back and
     * forth cannot spam.
     */
    private static void announceNewlyReady(MinecraftClient client, SkillTree tree, KindredData data) {
        java.util.List<SkillNode> fresh = new java.util.ArrayList<>();
        for (SkillNode node : tree.nodes()) {
            if (TreeRenderer.stateOf(node, data, tree) != TreeRenderer.NodeState.AVAILABLE) {
                continue;
            }
            cachedReady++;
            if (announced.add(node.id()) && readyPrimed) {
                fresh.add(node);
            }
        }
        if (fresh.isEmpty()) {
            readyPrimed = true;
            return;
        }
        readyPrimed = true;

        SkillNode first = fresh.get(0);
        Text name = Text.translatableWithFallback("kindreds.node." + first.id() + ".name",
                first.id().substring(first.id().lastIndexOf('.') + 1));
        Text title = Text.translatable("kindreds.toast.ready", fresh.size()).formatted(Formatting.GREEN);
        Text body = fresh.size() == 1
                ? Text.translatable("kindreds.toast.ready.one", name, disciplineName(first.cost().disciplineId()))
                : Text.translatable("kindreds.toast.ready.many", name, fresh.size() - 1);
        SystemToast.add(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION, title, body);
        client.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.4f);
    }

    /** Forgets what has been announced - used when the tree changes out from under the player. */
    public static void resetAnnouncements() {
        announced.clear();
        readyPrimed = false;
    }

    /** Cost of the cheapest node the player does not yet own ({@link Integer#MAX_VALUE} if none). */
    private static int cheapestUnowned(SkillTree t, KindredData data) {
        int best = Integer.MAX_VALUE;
        for (com.kindreds.data.SkillNode n : t.nodes()) {
            if (!data.hasNode(n.id())) {
                best = Math.min(best, n.cost().points());
            }
        }
        return best;
    }

    // --- per-tick watch -------------------------------------------------------------------------

    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }
        SkillTree t = tree();
        if (t == null) {
            return;
        }
        KindredData data = ClientKindredData.INSTANCE;

        // Level-up toasts. The first pass after joining only primes the baseline.
        for (Identifier d : disciplines) {
            int level = ProgressionService.pointsForLevel(data.xpIn(d));
            Integer prev = lastLevel.put(d, level);
            if (primed && prev != null && level > prev) {
                SystemToast.add(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                        Text.translatable("kindreds.toast.levelup", disciplineName(d), level)
                                .formatted(Formatting.GOLD),
                        Text.translatable("kindreds.toast.levelup.body", level - prev));
            }
        }
        primed = true;
        recomputeUnspent(t, data);
        announceNewlyReady(client, t, data);
        maybeWelcome(client, data);
    }

    /** A localized discipline name, falling back to a tidied id path if a pack adds a new one. */
    public static Text disciplineName(Identifier d) {
        String path = d.getPath();
        String fallback = Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
        return Text.translatableWithFallback("kindreds.discipline." + path, fallback);
    }

    // --- one-time welcome -----------------------------------------------------------------------

    private static void maybeWelcome(MinecraftClient client, KindredData data) {
        Identifier race = data.race();
        if (race == null) {
            return;
        }
        if (welcomedRaces == null) {
            welcomedRaces = readWelcomed();
        }
        if (!welcomedRaces.add(race.getPath())) {
            return; // already greeted for this race
        }
        writeWelcomed();
        Text raceName = Text.translatableWithFallback("kindreds.race." + race.getPath(), race.getPath());
        SystemToast.add(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                Text.translatable("kindreds.toast.welcome", raceName).formatted(Formatting.GOLD),
                Text.translatable("kindreds.toast.welcome.body",
                        com.kindreds.KindredsClient.openTreeKeyName()));
        client.player.sendMessage(Text.translatable("kindreds.welcome.chat", raceName,
                com.kindreds.KindredsClient.openTreeKeyName(),
                com.kindreds.KindredsClient.cycleAbilityKeyName(),
                com.kindreds.KindredsClient.useAbilityKeyName(),
                com.kindreds.KindredsClient.openLoadoutKeyName()).formatted(Formatting.GRAY), false);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("kindreds-client.json");
    }

    private static Set<String> readWelcomed() {
        try {
            Path p = file();
            if (Files.exists(p)) {
                Set<String> s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), SET_TYPE);
                if (s != null) {
                    return new HashSet<>(s);
                }
            }
        } catch (Exception ignored) {
            // unreadable/corrupt - treat as "never greeted", which only costs one extra toast
        }
        return new HashSet<>();
    }

    private static void writeWelcomed() {
        try {
            Files.writeString(file(), GSON.toJson(welcomedRaces, SET_TYPE), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // non-fatal: the greeting simply shows again next launch
        }
    }
}
