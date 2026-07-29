package com.kindreds.client.screen;

import com.kindreds.inscription.InscriptionIndex;
import com.kindreds.network.RequestInscriptionsC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inscription table, written out - what each enchantment costs, and what it is cut from.
 *
 * <p>Middle-earth's own table tells you nothing until you already know the answer. It shows the
 * words a stone grants, but not which combination means anything, so finding an enchantment means
 * trying combinations against a stone that survives three uses. There are around 160 recipes; the
 * arithmetic on that is not a puzzle, it is a wiki tab open on a second monitor. This is the page
 * that makes the second monitor unnecessary.
 *
 * <h2>Grouped by the stone you have to bring</h2>
 * The first version was one flat list sorted by category - the order the data happened to be in,
 * not the order the question gets asked. A player walks to the table holding one catalyst, and what
 * they want is everything that catalyst can cut. So the page is grouped by stone, each group headed
 * by the stone itself as a drawn item, and the inscriptions needing no stone at all come first,
 * because those are the ones anybody can make.
 *
 * <h2>The detail lives in the hover</h2>
 * A row carries the name, how far it goes, what it costs and what it must be cut with - what the
 * eye scans for. What the enchantment actually does, the price of every level, the words to lay
 * down, and what it can never share an item with all appear on hover. Putting that in the row made
 * a wall of text; leaving it out made a page that answered nothing.
 *
 * <p>Rows are the server's, not this mod's - see {@code InscriptionIndex}. What is shown is whatever
 * the connected server actually runs, so a datapack that adds or reprices an inscription shows up
 * here without anybody editing a table.
 */
public class InscriptionsScreen extends Screen {

    /** Set by the network handler when the server answers; null while the request is in flight. */
    private static volatile List<InscriptionIndex.Entry> rows;

    private static final int ROW_H = 12;
    private static final int HEADER_H = 18;

    /**
     * Solid, not translucent.
     *
     * <p>The first version let the world through, and a page of a hundred and fifty rows of small
     * text over moving grass and a hotbar is unreadable - the eye keeps finding the world instead
     * of the row. A reference page is read, not glanced at, so it gets an opaque ground.
     */
    private static final int PARCHMENT = 0xFF191410;
    private static final int PANEL_INNER = 0xFF241D14;
    private static final int PARCHMENT_EDGE = 0xFF4A3B28;
    private static final int BAND = 0x18FFFFFF;

    /**
     * How the page is grouped.
     *
     * <p>Both orders answer a real question and neither answers the other. Standing at the table
     * holding a ruby, you want everything ruby can cut; deciding what to put on a new sword, you
     * want everything a sword can take. So it is a button, kept between openings.
     */
    private enum Grouping { STONE, CATEGORY }

    private static Grouping grouping = Grouping.STONE;

    private final Screen parent;
    private int scroll;
    private int maxScroll;
    private InscriptionIndex.Entry hovered;

    public InscriptionsScreen(Screen parent) {
        super(Text.translatable("kindreds.inscriptions.title"));
        this.parent = parent;
    }

    /** Called by the client network handler when {@code SyncInscriptionsS2C} arrives. */
    public static void accept(List<InscriptionIndex.Entry> received) {
        rows = received;
    }

    /** Forgets the table, so leaving a world cannot leave another server's recipes on screen. */
    public static void forget() {
        rows = null;
    }

    public static void open(MinecraftClient client) {
        rows = null;
        ClientPlayNetworking.send(new RequestInscriptionsC2S(true));
        client.setScreen(new InscriptionsScreen(client.currentScreen));
    }

    @Override
    protected void init() {
        int panelW = Math.min(620, this.width - 48);
        int x = (this.width - panelW) / 2;
        addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget
            .builder(groupingLabel(), button -> {
                grouping = grouping == Grouping.STONE ? Grouping.CATEGORY : Grouping.STONE;
                scroll = 0;
                button.setMessage(groupingLabel());
            })
            .dimensions(x + panelW - 112, 16, 112, 18)
            .build());
    }

    private static net.minecraft.text.Text groupingLabel() {
        return Text.translatable(grouping == Grouping.STONE
            ? "kindreds.inscriptions.group.stone"
            : "kindreds.inscriptions.group.category");
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        // The whole window. A hundred and fifty rows is not a dialog over the world, it is a page
        // you read, and letting the world through at the edges was the thing that made it look
        // unfinished however good the rows were.
        ctx.fill(0, 0, this.width, this.height, PARCHMENT);

        int panelW = Math.min(620, this.width - 48);
        int x = (this.width - panelW) / 2;
        int top = 44;
        int bottom = this.height - 32;

        ctx.fill(x - 8, top - 26, x + panelW + 8, bottom + 8, PANEL_INNER);
        drawEdge(ctx, x - 8, top - 26, x + panelW + 8, bottom + 8);

        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, top - 20,
            0xFFD9C89A);
        drawColumnHeadings(ctx, x, top - 6, panelW);
        ctx.fill(x, top + 3, x + panelW, top + 4, 0x33FFE9A8);
        top += 8;

        if (rows == null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.inscriptions.loading").formatted(Formatting.GRAY),
                this.width / 2, this.height / 2, 0xFF8A7A5C);
            return;
        }
        if (rows.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.inscriptions.none").formatted(Formatting.GRAY),
                this.width / 2, this.height / 2, 0xFF8A7A5C);
            return;
        }

        List<Object> flow = layout();
        int contentH = 0;
        for (Object item : flow) {
            contentH += item instanceof String ? HEADER_H : ROW_H;
        }
        maxScroll = Math.max(0, contentH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        ctx.enableScissor(x, top, x + panelW, bottom);
        hovered = null;
        int y = top - scroll;
        int rowIndex = 0;
        for (Object item : flow) {
            if (item instanceof String stone) {
                if (y + HEADER_H > top && y < bottom) {
                    drawStoneHeader(ctx, x, y, panelW, stone, countUnder(flow, stone));
                }
                y += HEADER_H;
                rowIndex = 0;
                continue;
            }
            InscriptionIndex.Entry row = (InscriptionIndex.Entry) item;
            if (y + ROW_H > top && y < bottom) {
                boolean under = mouseX >= x && mouseX <= x + panelW
                    && mouseY >= Math.max(y, top) && mouseY < Math.min(y + ROW_H, bottom);
                if (under) {
                    hovered = row;
                }
                drawRow(ctx, x, y, panelW, row, rowIndex, under);
            }
            rowIndex++;
            y += ROW_H;
        }
        ctx.disableScissor();

        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("kindreds.inscriptions.hint", rows.size())
                .formatted(Formatting.DARK_GRAY),
            this.width / 2, this.height - 18, 0xFF6A5F4A);

        if (hovered != null) {
            ctx.drawTooltip(this.textRenderer, explain(hovered), mouseX, mouseY);
        }
    }

    private static void drawEdge(DrawContext ctx, int left, int top, int right, int bottom) {
        ctx.fill(left, top, right, top + 1, PARCHMENT_EDGE);
        ctx.fill(left, bottom - 1, right, bottom, PARCHMENT_EDGE);
        ctx.fill(left, top, left + 1, bottom, PARCHMENT_EDGE);
        ctx.fill(right - 1, top, right, bottom, PARCHMENT_EDGE);
    }

    /** What the columns are, said once, so the numbers are not a guess. */
    private void drawColumnHeadings(DrawContext ctx, int x, int y, int panelW) {
        int[] at = columns(panelW);
        ctx.drawText(this.textRenderer,
            Text.translatable("kindreds.inscriptions.col.enchantment"), x + at[0], y, 0xFF6A5F4A, false);
        ctx.drawText(this.textRenderer,
            Text.translatable("kindreds.inscriptions.col.level"), x + at[1], y, 0xFF6A5F4A, false);
        ctx.drawText(this.textRenderer,
            Text.translatable("kindreds.inscriptions.col.cost"), x + at[2], y, 0xFF6A5F4A, false);
        ctx.drawText(this.textRenderer,
            Text.translatable("kindreds.inscriptions.col.chisel"), x + at[3], y, 0xFF6A5F4A, false);
    }

    /** Headers and rows in reading order, grouped the way the button says. */
    private List<Object> layout() {
        Map<String, List<InscriptionIndex.Entry>> groups = new java.util.TreeMap<>();
        for (InscriptionIndex.Entry row : rows) {
            groups.computeIfAbsent(keyOf(row), key -> new ArrayList<>()).add(row);
        }
        List<Object> flow = new ArrayList<>();
        for (Map.Entry<String, List<InscriptionIndex.Entry>> group : groups.entrySet()) {
            // Within a group, the other axis orders it: browsing rubies you still want the weapons
            // together, and browsing weapons you still want a stone's worth in one place.
            group.getValue().sort((a, b) -> {
                int byOther = otherKeyOf(a).compareTo(otherKeyOf(b));
                return byOther != 0 ? byOther : a.enchantment().compareTo(b.enchantment());
            });
            flow.add(group.getKey());
            flow.addAll(group.getValue());
        }
        return flow;
    }

    private static String keyOf(InscriptionIndex.Entry row) {
        return grouping == Grouping.STONE ? stoneSortKey(row.stone()) : row.category();
    }

    private static String otherKeyOf(InscriptionIndex.Entry row) {
        return grouping == Grouping.STONE ? row.category() : stoneSortKey(row.stone());
    }

    /** Sorts the groups deliberately: no stone first, then the four catalysts. */
    private static String stoneSortKey(String stone) {
        return switch (stone) {
            case "any" -> "0_any";
            case "middle-earth:ruby" -> "1_middle-earth:ruby";
            case "middle-earth:sapphire" -> "2_middle-earth:sapphire";
            case "minecraft:emerald" -> "3_minecraft:emerald";
            case "middle-earth:adamant" -> "4_middle-earth:adamant";
            case "impossible" -> "9_impossible";
            default -> "5_" + stone;
        };
    }

    /** The bare value again, for a key that carries a sort prefix. */
    private static String unkey(String key) {
        int underscore = key.indexOf('_');
        return underscore > 0 && Character.isDigit(key.charAt(0)) ? key.substring(underscore + 1) : key;
    }

    private static int countUnder(List<Object> flow, String stone) {
        int seen = 0;
        boolean counting = false;
        for (Object item : flow) {
            if (item instanceof String header) {
                if (counting) {
                    break;
                }
                counting = header.equals(stone);
            } else if (counting) {
                seen++;
            }
        }
        return seen;
    }

    /** A group heading: the stone as a real item, or the category with something it goes on. */
    private void drawStoneHeader(DrawContext ctx, int x, int y, int panelW, String key, int count) {
        ctx.fill(x, y + 1, x + panelW, y + HEADER_H - 2, 0x44000000);
        ctx.fill(x, y + HEADER_H - 2, x + panelW, y + HEADER_H - 1, 0x33FFE9A8);

        String value = unkey(key);
        boolean byStone = grouping == Grouping.STONE;
        ItemStack icon = byStone ? stoneItem(value) : categoryItem(value);
        if (!icon.isEmpty()) {
            ctx.drawItem(icon, x + 3, y + 1);
        }
        net.minecraft.text.Text label = byStone ? stoneName(value)
            : Text.translatable("kindreds.inscriptions.cat." + unkey(value));
        int colour = byStone ? stoneColour(value) : 0xFFD9C89A;
        ctx.drawText(this.textRenderer, label, x + 23, y + 5, colour, false);
        Text tally = Text.translatable("kindreds.inscriptions.count", count);
        ctx.drawText(this.textRenderer, tally,
            x + panelW - this.textRenderer.getWidth(tally) - 6, y + 5, 0xFF5A5040, false);
    }

    private void drawRow(DrawContext ctx, int x, int y, int panelW, InscriptionIndex.Entry row,
                         int index, boolean under) {
        if (under) {
            ctx.fill(x, y, x + panelW, y + ROW_H, 0x50FFE9A8);
        } else if ((index & 1) == 1) {
            // Faint banding. The name is at the left and the chisel is far to the right; without it
            // the eye loses the line somewhere in the middle.
            ctx.fill(x, y, x + panelW, y + ROW_H, BAND);
        }
        int[] at = columns(panelW);

        ctx.drawText(this.textRenderer, Text.translatable(row.enchantmentKey()),
            x + at[0], y + 2, 0xFFE8E0CC, false);
        ctx.drawText(this.textRenderer, Text.literal(roman(row.maxLevel())),
            x + at[1], y + 2, 0xFFAAA08C, false);

        // The whole ladder, in the row: "3 / 5 / 7" beats "3-7", because the number a player wants
        // is the price of the level they are actually going for, and a range makes them guess.
        ctx.drawText(this.textRenderer, costLadder(row), x + at[2], y + 2, 0xFF7FD4D4, false);

        // Likewise the chisels, one per level, so it is plain that level I is iron even when the
        // last level wants mithril.
        ctx.drawText(this.textRenderer, chiselLadder(row), x + at[3], y + 2, 0xFFB8AC94, false);

        if (row.conflicts() != null && !row.conflicts().isEmpty()) {
            // Named, not marked. A bare "!" read as an error and explained nothing; the word says
            // what it means, and the hover lists what it clashes with.
            Text clash = Text.translatable("kindreds.inscriptions.exclusive");
            ctx.drawText(this.textRenderer, clash,
                x + panelW - this.textRenderer.getWidth(clash) - 4, y + 2, 0xFFC98A3A, false);
        }
    }

    /** {@code 3 / 5 / 7} - what each level costs, in order. */
    private static Text costLadder(InscriptionIndex.Entry row) {
        if (row.levels() == null || row.levels().isEmpty()) {
            return Text.literal(Integer.toString(row.maxCost()));
        }
        StringBuilder out = new StringBuilder();
        for (InscriptionIndex.Rung rung : row.levels()) {
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(rung.cost());
        }
        return Text.literal(out.toString());
    }

    /**
     * The chisels each level needs, collapsed when they are all the same.
     *
     * <p>{@code iron > mithril} says the ladder starts cheap and ends dear, which is the thing the
     * old single value hid; a row whose levels all want the same chisel just says it once.
     */
    private static Text chiselLadder(InscriptionIndex.Entry row) {
        if (row.levels() == null || row.levels().isEmpty()) {
            return Text.translatable("kindreds.inscriptions.chisel." + row.chisel());
        }
        String first = row.levels().get(0).chisel();
        String last = row.levels().get(row.levels().size() - 1).chisel();
        if (first.equals(last)) {
            return Text.translatable("kindreds.inscriptions.chisel." + first);
        }
        return Text.translatable("kindreds.inscriptions.chisel." + first)
            .append(Text.literal(" > "))
            .append(Text.translatable("kindreds.inscriptions.chisel." + last));
    }

    /** Everything the row could not hold, in the order somebody deciding would want it. */
    private List<Text> explain(InscriptionIndex.Entry row) {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.translatable(row.enchantmentKey()).formatted(Formatting.WHITE));

        // What it actually does. Ours to write: vanilla ships a name and explains nothing anywhere
        // in the game, which is the single most common reason to go and look something up.
        lines.add(Text.translatable(effectKey(row.enchantment())).formatted(Formatting.GRAY));
        lines.add(Text.empty());

        lines.add(Text.translatable("kindreds.inscriptions.tip.stone", stoneName(row.stone()))
            .formatted(Formatting.GOLD));
        lines.add(Text.translatable("kindreds.inscriptions.tip.words",
            String.join(", ", row.words())).formatted(Formatting.AQUA));

        if (row.levels() != null && !row.levels().isEmpty()) {
            lines.add(Text.empty());
            lines.add(Text.translatable("kindreds.inscriptions.tip.levels")
                .formatted(Formatting.YELLOW));
            for (InscriptionIndex.Rung rung : row.levels()) {
                lines.add(Text.translatable("kindreds.inscriptions.tip.level",
                        roman(rung.level()), rung.cost(),
                        Text.translatable("kindreds.inscriptions.chisel." + rung.chisel()))
                    .formatted(Formatting.DARK_GRAY));
            }
        }

        if (row.conflicts() != null && !row.conflicts().isEmpty()) {
            lines.add(Text.empty());
            lines.add(Text.translatable("kindreds.inscriptions.tip.conflicts")
                .formatted(Formatting.RED));
            for (String other : row.conflicts()) {
                lines.add(Text.literal("  ")
                    .append(Text.translatable(nameKeyOf(other))).formatted(Formatting.DARK_RED));
            }
        }
        return lines;
    }

    /** {@code minecraft:sharpness} → {@code kindreds.inscriptions.effect.minecraft.sharpness}. */
    private static String effectKey(String enchantmentId) {
        return "kindreds.inscriptions.effect." + enchantmentId.replace(':', '.');
    }

    /** {@code minecraft:sharpness} → vanilla's own {@code enchantment.minecraft.sharpness}. */
    private static String nameKeyOf(String enchantmentId) {
        int colon = enchantmentId.indexOf(':');
        return colon < 0 ? enchantmentId
            : "enchantment." + enchantmentId.substring(0, colon) + "."
              + enchantmentId.substring(colon + 1);
    }

    /**
     * The catalyst as an item, so a group is headed by the thing you go and fetch.
     *
     * <p>Lapis stands for the common set: it is the stone the table always takes, so an inscription
     * needing nothing else is one you can cut with lapis alone.
     */
    /** Something a player would put the inscription on, so a category reads at a glance. */
    private static ItemStack categoryItem(String category) {
        return switch (unkey(category)) {
            case "weapon" -> new ItemStack(Items.IRON_SWORD);
            case "archery" -> new ItemStack(Items.BOW);
            case "armour" -> new ItemStack(Items.IRON_CHESTPLATE);
            case "tool" -> new ItemStack(Items.IRON_PICKAXE);
            default -> new ItemStack(Items.BOOK);
        };
    }

    private static ItemStack stoneItem(String stone) {
        if ("any".equals(stone)) {
            // No catalyst at all. The table's word bank grants the common words under a null key -
            // there is nothing to put in the slot, so a book stands for "just the chisel", where a
            // lapis icon would have sent people digging for lapis they never needed.
            return new ItemStack(Items.WRITABLE_BOOK);
        }
        if ("impossible".equals(stone)) {
            return new ItemStack(Items.BARRIER);
        }
        // A full item id now, because the catalysts are not all in one namespace: ruby, sapphire
        // and adamant are Middle-earth's, emerald is vanilla's.
        Identifier id = Identifier.tryParse(stone);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Item item = Registries.ITEM.get(id);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    /**
     * The stone's own name, from whichever mod owns it.
     *
     * <p>Asking the item for its name rather than keeping our own list means a stone is always
     * called what the rest of the game calls it, in whatever language the player is using, and a
     * datapack that renames one is followed for free.
     */
    private Text stoneName(String stone) {
        if ("any".equals(stone) || "impossible".equals(stone)) {
            return Text.translatable("kindreds.inscriptions.stone." + stone);
        }
        ItemStack icon = stoneItem(stone);
        return icon.isEmpty() ? Text.literal(stone) : icon.getName().copy();
    }

    private static int[] columns(int panelW) {
        return new int[]{6, panelW - 250, panelW - 218, panelW - 120};
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(level);
        };
    }

    private static int stoneColour(String stone) {
        return switch (stone) {
            case "middle-earth:ruby" -> 0xFFD05A5A;
            case "middle-earth:sapphire" -> 0xFF5A7AD0;
            case "minecraft:emerald" -> 0xFF5AC07A;
            case "middle-earth:adamant" -> 0xFFC0A8E0;
            case "any" -> 0xFF9AA8C0;
            case "impossible" -> 0xFFFF4040;
            default -> 0xFFBFAE8C;
        };
    }

    private static int chiselColour(String chisel) {
        return switch (chisel) {
            case "iron" -> 0xFFB0B0B0;
            case "steel" -> 0xFFD8D8E0;
            case "mithril" -> 0xFF9FE8F0;
            default -> 0xFF8A8A8A;
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (vertical * 18)));
        return true;
    }

    /**
     * Back to wherever this was opened from.
     *
     * <p>Usually the hub, but from the table's own button it is the table - and returning to that
     * instance rather than to null is what keeps the container open, so a player who came to look
     * something up is still standing at the table with their chisel in the slot when they close the
     * page. Setting null instead would tell the server to close the container and drop them out of
     * the table entirely, which is exactly the round trip the button exists to remove.
     */
    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
