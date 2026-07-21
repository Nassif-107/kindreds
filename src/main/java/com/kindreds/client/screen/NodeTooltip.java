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
        addWrapped(lines, tr, Text.literal(I18n.translate("kindreds.tooltip.cost",
                cost.points(), disciplineName(client, cost.disciplineId()))).formatted(Formatting.AQUA));

        if (!node.prereqs().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String prereqId : node.prereqs()) {
                names.add(tree.node(prereqId).map(n -> displayName(n.id())).orElse(displayName(prereqId)));
            }
            addWrapped(lines, tr, Text.literal(I18n.translate("kindreds.tooltip.requires", String.join(", ", names)))
                    .formatted(Formatting.DARK_GRAY));
        }

        // "Sealed" only applies while the capstone isn't yet OWNED - once unlocked it's fully lit and
        // shown as an earned deed instead (see TreeRenderer.drawNode for the matching seal-ring gate).
        node.deedAdvancement().ifPresent(deed -> {
            if (state != TreeRenderer.NodeState.OWNED) {
                addWrapped(lines, tr, Text.literal(I18n.translate("kindreds.tooltip.sealed_deed", titleCase(deed.getPath())))
                        .withColor(ThemeAssets.WARNING_COLOR));
            } else {
                addWrapped(lines, tr, Text.literal(I18n.translate("kindreds.tooltip.deed_earned", titleCase(deed.getPath())))
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
            return I18n.translate("kindreds.kind.none");
        }
        return switch (node.abilities().get(0)) {
            case VisionUnlock v -> I18n.translate("kindreds.kind.vision");
            case AttributeMod a -> I18n.translate("kindreds.kind.attribute");
            case StatusEffectDef s -> I18n.translate("kindreds.kind.status");
            case ActiveAbilityDef act -> I18n.translate("kindreds.kind.active");
            case CurseDef c -> I18n.translate("kindreds.kind.curse");
            case ContextualBoon c -> I18n.translate("kindreds.kind.contextual");
            case PerkDef p -> I18n.translate("kindreds.kind.perk");
        };
    }

    /** One line describing the mechanical effect of a single ability - curses render in the warning
     * tint via a Minecraft color code prefix so {@code addWrapped}'s wrapping still applies. */
    static String describe(AbilityDef ability) {
        return switch (ability) {
            case AttributeMod a -> String.format(Locale.ROOT, "%s%.1f %s",
                    a.amount() >= 0 ? "+" : "", a.amount(), attrName(a.attribute()));
            case StatusEffectDef s -> I18n.translate("kindreds.effect.grants", effectName(s.effect()), s.amplifier() + 1)
                    + (s.durationTicks() < 0 ? " " + I18n.translate("kindreds.effect.while_owned") : "");
            case VisionUnlock v -> I18n.translate("kindreds.effect.vision", visionName(v.visionId()), v.radius());
            case ActiveAbilityDef act -> I18n.translate("kindreds.effect.active",
                    abilityName(act.abilityId()), act.cooldownTicks() / 20);
            case CurseDef c -> "§6" + I18n.translate("kindreds.effect.curse", titleCase(c.curseId()), c.severity());
            case ContextualBoon c -> "§a" + I18n.translate("kindreds.effect.in_context", contextName(c.when()), describe(c.effect()));
            case PerkDef p -> describePerk(p);
        };
    }

    /** A localized attribute name via Minecraft's own {@code attribute.name.<path>} key (already
     * translated for every language), falling back to a title-cased path for non-vanilla attributes. */
    private static String attrName(Identifier attribute) {
        String key = "attribute.name." + attribute.getPath();
        return I18n.hasTranslation(key) ? I18n.translate(key) : titleCase(attribute.getPath());
    }

    /** A localized status-effect name via vanilla's {@code effect.<ns>.<path>} key. */
    private static String effectName(Identifier effect) {
        String key = "effect." + effect.getNamespace() + "." + effect.getPath();
        return I18n.hasTranslation(key) ? I18n.translate(key) : titleCase(effect.getPath());
    }

    /** The ability's own localized name ({@code kindreds.ability.<path>}) rather than a
     * title-cased id - the keys already exist in every language the mod ships. */
    private static String abilityName(Identifier abilityId) {
        String key = "kindreds.ability." + abilityId.getPath();
        return I18n.hasTranslation(key) ? I18n.translate(key) : titleCase(abilityId.getPath());
    }

    private static String visionName(String id) {
        String key = "kindreds.vision." + id;
        return I18n.hasTranslation(key) ? I18n.translate(key) : titleCase(id);
    }

    private static String contextName(String when) {
        String key = "kindreds.context." + when;
        return I18n.hasTranslation(key) ? I18n.translate(key) : titleCase(when);
    }

    private static String foeName(String foe) {
        String key = "kindreds.foe." + foe;
        return I18n.hasTranslation(key) ? I18n.translate(key) : titleCase(foe);
    }

    /** Human-readable one-liner for a {@link PerkDef}, keyed on its perk id. Known perks get bespoke,
     * lore-flavored text; anything else falls back to a generic (but still informative) line listing
     * the perk name and its authored params, so a newly-added perk always reads as <i>something</i>
     * before this switch is taught its wording. */
    static String describePerk(PerkDef p) {
        String foe = foeName(p.foe().orElse("any"));
        return switch (p.perk()) {
            case "bane" -> "§c" + I18n.translate("kindreds.perk.bane", Math.round(p.param("bonus", 0f) * 100), foe);
            case "arrow_slaying" -> "§c" + I18n.translate("kindreds.perk.arrow_slaying",
                    Math.round(p.param("bonus", 0f) * 100), foe);
            case "mining_fortune" -> "§e" + I18n.translate("kindreds.perk.mining_fortune",
                    Math.round(p.param("chance", 0f) * 100), Math.round(p.param("amount", 1f)));
            case "heal_on_kill" -> "§a" + I18n.translate("kindreds.perk.heal_on_kill",
                    String.format(Locale.ROOT, "%.1f", p.param("health", 0f)));
            case "strike_effect" -> "§c" + I18n.translate("kindreds.perk.strike_effect")
                    + (p.effect().isPresent() ? " (" + effectName(p.effect().get().effect()) + ")" : "");
            case "ally_aura" -> "§b" + I18n.translate("kindreds.perk.ally_aura", Math.round(p.param("radius", 8f)));
            case "war_pack" -> "§c" + I18n.translate("kindreds.perk.war_pack",
                    Math.round(p.param("per_ally", 0.05f) * 100), Math.round(p.param("max", 6f)));
            case "evasion" -> "§b" + I18n.translate("kindreds.perk.evasion",
                    Math.round(p.param("chance", 0f) * 100), Math.round(p.param("reduction", 1f) * 100));
            case "lifesteal" -> "§a" + I18n.translate("kindreds.perk.lifesteal", Math.round(p.param("percent", 0.1f) * 100));
            case "thorns" -> "§c" + I18n.translate("kindreds.perk.thorns", Math.round(p.param("percent", 0.25f) * 100));
            case "true_flight" -> "§b" + I18n.translate("kindreds.perk.true_flight");
            case "arrow_damage" -> "§c" + I18n.translate("kindreds.perk.arrow_damage", Math.round(p.param("bonus", 0.15f) * 100));
            case "long_shot" -> "§c" + I18n.translate("kindreds.perk.long_shot",
                    Math.round(p.param("per_block", 0.02f) * 100), Math.round(p.param("max", 1f) * 100));
            case "arrow_crit" -> "§e" + I18n.translate("kindreds.perk.arrow_crit");
            case "arrow_pierce" -> "§e" + I18n.translate("kindreds.perk.arrow_pierce", Math.round(p.param("targets", 1f)));
            case "arrow_velocity" -> "§b" + I18n.translate("kindreds.perk.arrow_velocity", Math.round(p.param("bonus", 0.3f) * 100));
            case "multishot" -> "§e" + I18n.translate("kindreds.perk.multishot", Math.round(p.param("arrows", 1f)));
            case "arrow_effect" -> "§c" + I18n.translate("kindreds.perk.arrow_effect")
                    + (p.effect().isPresent() ? " (" + effectName(p.effect().get().effect()) + ")" : "");
            case "swift_draw" -> "§b" + I18n.translate("kindreds.perk.swift_draw");
            case "ambush" -> "§c" + I18n.translate("kindreds.perk.ambush", Math.round(p.param("bonus", 0.5f) * 100));
            case "camouflage" -> "§b" + I18n.translate("kindreds.perk.camouflage");
            case "foresight" -> "§d" + I18n.translate("kindreds.perk.foresight", Math.round(p.param("reduction", 0.1f) * 100));
            case "light_ward" -> "§e" + I18n.translate("kindreds.perk.light_ward");
            case "mend_gear" -> "§6" + I18n.translate("kindreds.perk.mend_gear");
            case "auto_smelt" -> "§6" + I18n.translate("kindreds.perk.auto_smelt");
            case "ore_magnet" -> "§6" + I18n.translate("kindreds.perk.ore_magnet");
            case "prospector_xp" -> "§6" + I18n.translate("kindreds.perk.prospector_xp", Math.round(p.param("xp", 3f)));
            case "vein_miner" -> "§6" + I18n.translate("kindreds.perk.vein_miner", Math.round(p.param("max", 16f)));
            case "miners_rhythm" -> "§6" + I18n.translate("kindreds.perk.miners_rhythm", Math.round(p.param("amplifier", 0f)) + 1);
            case "pounce" -> "§c" + I18n.translate("kindreds.perk.pounce", Math.round(p.param("bonus", 0.5f) * 100));
            case "prey_sense" -> "§2" + I18n.translate("kindreds.perk.prey_sense", Math.round(p.param("radius", 16f)));
            case "dread_aura" -> "§5" + I18n.translate("kindreds.perk.dread_aura", Math.round(p.param("radius", 8f)));
            case "unyielding" -> "§4" + I18n.translate("kindreds.perk.unyielding");
            case "beast_calm" -> "§a" + I18n.translate("kindreds.perk.beast_calm");
            case "pack_bond" -> "§a" + I18n.translate("kindreds.perk.pack_bond", Math.round(p.param("amplifier", 0f)) + 1);
            case "elven_steed" -> "§a" + I18n.translate("kindreds.perk.elven_steed", Math.round(p.param("speed", 0f)) + 1);
            case "war_steed" -> "§a" + I18n.translate("kindreds.perk.war_steed", Math.round(p.param("speed", 0f)) + 1);
            default -> {
        StringBuilder sb = new StringBuilder(I18n.translate("kindreds.perk.generic"))
                        .append(' ').append(titleCase(p.perk()));
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
