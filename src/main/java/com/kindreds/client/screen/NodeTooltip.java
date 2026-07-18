package com.kindreds.client.screen;

import com.kindreds.data.Discipline;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.Theme;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.data.ability.AttributeMod;
import com.kindreds.data.ability.ContextualBoon;
import com.kindreds.data.ability.CurseDef;
import com.kindreds.data.ability.PerkDef;
import com.kindreds.data.ability.StatusEffectDef;
import com.kindreds.data.ability.VisionUnlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.registry.Registry;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the hover card for a single {@link SkillNode}: a lore-flavored ability card - name,
 * short flavor line, mechanical effect(s), cost, prereqs, and (if present) a deed/curse tradeoff in
 * a warning tint. Drawn last by {@link SkillTreeScreen} so it sits on top of the canvas/panel.
 *
 * <h2>Lore text</h2>
 * {@link SkillNode} carries no dedicated flavor-text field (Task 3's schema is mechanics-only), so
 * flavor lines are read from the lang file at {@code kindreds.node.<id>.flavor} (data-driven - a
 * later authoring pass can add real per-node lore for every race without touching this class) and
 * fall back to a generic, still-thematic line templated from the node's first ability when no
 * translation exists.
 */
public final class NodeTooltip {
    private NodeTooltip() {
    }

    private static final int MAX_WIDTH = 220;
    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 10;

    public static void render(DrawContext ctx, MinecraftClient client, SkillNode node, TreeRenderer.NodeState state,
                               SkillTree tree, Theme theme, int mouseX, int mouseY, int screenW, int screenH) {
        TextRenderer tr = client.textRenderer;
        List<OrderedText> lines = buildLines(client, node, state, tree);

        int width = MAX_WIDTH;
        for (OrderedText line : lines) {
            width = Math.max(width, tr.getWidth(line) + PADDING * 2);
        }
        width = Math.min(width, MAX_WIDTH + PADDING * 2);
        int height = lines.size() * LINE_HEIGHT + PADDING * 2;

        int x = mouseX + 12;
        int y = mouseY - 8;
        if (x + width > screenW) {
            x = mouseX - width - 12;
        }
        if (y + height > screenH) {
            y = screenH - height - 4;
        }
        if (y < 0) {
            y = 4;
        }

        int accent = ThemeAssets.accent(theme);
        ctx.fill(x, y, x + width, y + height, 0xF0101014);
        ctx.drawBorder(x, y, width, height, accent);

        int textY = y + PADDING;
        for (OrderedText line : lines) {
            ctx.drawText(tr, line, x + PADDING, textY, 0xFFFFFFFF, false);
            textY += LINE_HEIGHT;
        }
    }

    private static List<OrderedText> buildLines(MinecraftClient client, SkillNode node, TreeRenderer.NodeState state,
                                                 SkillTree tree) {
        TextRenderer tr = client.textRenderer;
        List<OrderedText> lines = new ArrayList<>();

        addWrapped(lines, tr, Text.literal(displayName(node.id())).formatted(Formatting.BOLD));
        addWrapped(lines, tr, Text.literal(flavor(node)).formatted(Formatting.GRAY));

        for (AbilityDef ability : node.abilities()) {
            addWrapped(lines, tr, Text.literal(describe(ability)));
        }

        SkillNode.Cost cost = node.cost();
        addWrapped(lines, tr, Text.literal("Cost: " + cost.points() + " " + disciplineName(client, cost.disciplineId()) + " pt(s)")
                .formatted(Formatting.AQUA));

        if (!node.prereqs().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String prereqId : node.prereqs()) {
                names.add(tree.node(prereqId).map(n -> displayName(n.id())).orElse(displayName(prereqId)));
            }
            addWrapped(lines, tr, Text.literal("Requires: " + String.join(", ", names))
                    .formatted(Formatting.DARK_GRAY));
        }

        // "Sealed" only applies while the capstone isn't yet OWNED - once unlocked it's fully lit and
        // shown as an earned deed instead (see TreeRenderer.drawNode for the matching seal-ring gate).
        node.deedAdvancement().ifPresent(deed -> {
            if (state != TreeRenderer.NodeState.OWNED) {
                addWrapped(lines, tr, Text.literal("Sealed - Deed: " + titleCase(deed.getPath()))
                        .withColor(ThemeAssets.WARNING_COLOR));
            } else {
                addWrapped(lines, tr, Text.literal("Deed earned: " + titleCase(deed.getPath()))
                        .formatted(Formatting.GREEN));
            }
        });

        return lines;
    }

    private static void addWrapped(List<OrderedText> out, TextRenderer tr, Text text) {
        out.addAll(tr.wrapLines(text, MAX_WIDTH));
    }

    // --- Text derivation --------------------------------------------------------------------------

    /** {@code kindreds.node.<id>.name}, falling back to a title-cased form of the id's last segment
     * (after any {@code race.} namespace prefix authored in the id, e.g. {@code "elf.keen_sight"}
     * -&gt; "Keen Sight"). */
    static String displayName(String nodeId) {
        String key = "kindreds.node." + nodeId + ".name";
        if (I18n.hasTranslation(key)) {
            return I18n.translate(key);
        }
        String path = nodeId.contains(".") ? nodeId.substring(nodeId.lastIndexOf('.') + 1) : nodeId;
        return titleCase(path);
    }

    /** {@code kindreds.node.<id>.flavor}, falling back to a generic (still in-voice) line derived
     * from the node's first ability so every node reads as *something* even before real lore text is
     * authored for it. */
    static String flavor(SkillNode node) {
        String key = "kindreds.node." + node.id() + ".flavor";
        if (I18n.hasTranslation(key)) {
            return I18n.translate(key);
        }
        if (node.abilities().isEmpty()) {
            return "An old skill, half-remembered.";
        }
        return switch (node.abilities().get(0)) {
            case VisionUnlock v -> "A gift of sharper senses, passed down through the blood.";
            case AttributeMod a -> "Strength earned, not given.";
            case StatusEffectDef s -> "A quiet blessing that never quite fades.";
            case ActiveAbilityDef act -> "A trick worth calling on, when the moment demands it.";
            case CurseDef c -> "Power with a price attached.";
            case ContextualBoon c -> "Power that wakes with the place and the hour.";
            case PerkDef p -> "A deed of the blood - called upon in the doing, not the having.";
        };
    }

    /** One line describing the mechanical effect of a single ability - curses render in the warning
     * tint via a Minecraft color code prefix so {@code addWrapped}'s wrapping still applies. */
    static String describe(AbilityDef ability) {
        return switch (ability) {
            case AttributeMod a -> String.format(Locale.ROOT, "%s%.1f %s",
                    a.amount() >= 0 ? "+" : "", a.amount(), titleCase(a.attribute().getPath()));
            case StatusEffectDef s -> "Grants " + titleCase(s.effect().getPath()) + " " + (s.amplifier() + 1)
                    + (s.durationTicks() < 0 ? " (while owned)" : "");
            case VisionUnlock v -> "Unlocks the " + titleCase(v.visionId()) + " vision (range " + v.radius() + ")";
            case ActiveAbilityDef act -> "Active: " + titleCase(act.abilityId().getPath())
                    + " (cooldown " + (act.cooldownTicks() / 20) + "s)";
            case CurseDef c -> "§6Curse: " + titleCase(c.curseId()) + " (severity " + c.severity() + ")";
            case ContextualBoon c -> "§aIn " + titleCase(c.when()) + ": " + describe(c.effect());
            case PerkDef p -> describePerk(p);
        };
    }

    /** Human-readable one-liner for a {@link PerkDef}, keyed on its perk id. Known perks get bespoke,
     * lore-flavored text; anything else falls back to a generic (but still informative) line listing
     * the perk name and its authored params, so a newly-added perk always reads as <i>something</i>
     * before this switch is taught its wording. */
    static String describePerk(PerkDef p) {
        String foe = titleCase(p.foe().orElse("any"));
        return switch (p.perk()) {
            case "bane" -> String.format(Locale.ROOT, "§cBane: +%d%% damage vs %s",
                    Math.round(p.param("bonus", 0f) * 100), foe);
            case "arrow_slaying" -> String.format(Locale.ROOT, "§cBlack Arrow: +%d%% bow damage vs %s",
                    Math.round(p.param("bonus", 0f) * 100), foe);
            case "mining_fortune" -> String.format(Locale.ROOT, "§eDelver's Fortune: %d%% chance of +%d ore drop",
                    Math.round(p.param("chance", 0f) * 100), Math.round(p.param("amount", 1f)));
            case "heal_on_kill" -> String.format(Locale.ROOT, "§aBloodlust: restore %.1f health on a kill",
                    p.param("health", 0f));
            case "strike_effect" -> "§cCruel blade: inflicts an affliction on a struck foe"
                    + (p.effect().isPresent() ? " (" + titleCase(p.effect().get().effect().getPath()) + ")" : "");
            case "ally_aura" -> String.format(Locale.ROOT, "§bLeadership: allies within %d blocks are heartened",
                    Math.round(p.param("radius", 8f)));
            case "light_ward" -> "§eStar-glass: light wards off the creatures of the dark";
            default -> {
                StringBuilder sb = new StringBuilder("Perk: ").append(titleCase(p.perk()));
                if (!p.params().isEmpty()) {
                    sb.append(" (");
                    boolean first = true;
                    for (var e : p.params().entrySet()) {
                        if (!first) {
                            sb.append(", ");
                        }
                        sb.append(e.getKey()).append('=').append(e.getValue());
                        first = false;
                    }
                    sb.append(')');
                }
                yield sb.toString();
            }
        };
    }

    private static String disciplineName(MinecraftClient client, Identifier disciplineId) {
        if (client.world == null) {
            return titleCase(disciplineId.getPath());
        }
        try {
            Registry<Discipline> disciplines = client.world.getRegistryManager().getOrThrow(KindredsRegistries.DISCIPLINE);
            Discipline discipline = disciplines.get(disciplineId);
            if (discipline != null) {
                return discipline.name();
            }
        } catch (RuntimeException ignored) {
            // Registry not ready yet - fall through to the id-derived name.
        }
        return titleCase(disciplineId.getPath());
    }

    private static String titleCase(String path) {
        String[] words = path.replace('_', ' ').replace('/', ' ').replace('.', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
