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
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int panelW = Math.min(440, this.width - 24);
        int x = (this.width - panelW) / 2;
        int top = 34;
        int bottom = this.height - 26;

        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFD9C89A);

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
        for (Object item : flow) {
            if (item instanceof String stone) {
                if (y + HEADER_H > top && y < bottom) {
                    drawStoneHeader(ctx, x, y, panelW, stone, countUnder(flow, stone));
                }
                y += HEADER_H;
                continue;
            }
            InscriptionIndex.Entry row = (InscriptionIndex.Entry) item;
            if (y + ROW_H > top && y < bottom) {
                boolean under = mouseX >= x && mouseX <= x + panelW
                    && mouseY >= Math.max(y, top) && mouseY < Math.min(y + ROW_H, bottom);
                if (under) {
                    hovered = row;
                }
                drawRow(ctx, x, y, panelW, row, under);
            }
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

    /** Headers and rows in reading order, stone by stone. */
    private List<Object> layout() {
        Map<String, List<InscriptionIndex.Entry>> byStone = new LinkedHashMap<>();
        for (InscriptionIndex.Entry row : rows) {
            byStone.computeIfAbsent(row.stone(), key -> new ArrayList<>()).add(row);
        }
        List<Object> flow = new ArrayList<>();
        for (Map.Entry<String, List<InscriptionIndex.Entry>> group : byStone.entrySet()) {
            flow.add(group.getKey());
            flow.addAll(group.getValue());
        }
        return flow;
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

    /** The stone itself, drawn, with what it is and how much it can cut. */
    private void drawStoneHeader(DrawContext ctx, int x, int y, int panelW, String stone, int count) {
        ctx.fill(x, y + 1, x + panelW, y + HEADER_H - 2, 0x33000000);
        ItemStack icon = stoneItem(stone);
        if (!icon.isEmpty()) {
            ctx.drawItem(icon, x + 3, y + 1);
        }
        ctx.drawText(this.textRenderer, Text.translatable("kindreds.inscriptions.stone." + stone),
            x + 23, y + 5, stoneColour(stone), false);
        Text tally = Text.translatable("kindreds.inscriptions.count", count);
        ctx.drawText(this.textRenderer, tally,
            x + panelW - this.textRenderer.getWidth(tally) - 6, y + 5, 0xFF5A5040, false);
    }

    private void drawRow(DrawContext ctx, int x, int y, int panelW, InscriptionIndex.Entry row,
                         boolean under) {
        if (under) {
            ctx.fill(x, y, x + panelW, y + ROW_H, 0x40FFE9A8);
        }
        int[] at = columns(panelW);

        ctx.drawText(this.textRenderer, Text.translatable(row.enchantmentKey()),
            x + at[0], y + 2, 0xFFE8E0CC, false);
        ctx.drawText(this.textRenderer, Text.literal(roman(row.maxLevel())),
            x + at[1], y + 2, 0xFFAAA08C, false);

        String cost = row.minCost() == row.maxCost()
            ? Integer.toString(row.maxCost())
            : row.minCost() + "-" + row.maxCost();
        ctx.drawText(this.textRenderer, Text.literal(cost), x + at[2], y + 2, 0xFF7FD4D4, false);

        ctx.drawText(this.textRenderer,
            Text.translatable("kindreds.inscriptions.chisel." + row.chisel()),
            x + at[3], y + 2, chiselColour(row.chisel()), false);

        if (row.conflicts() != null && !row.conflicts().isEmpty()) {
            // A mark, not a list. The list is long and the hover has room for it; what the row has
            // to say is only "this one is a choice, not an addition".
            ctx.drawText(this.textRenderer, Text.literal("!"), x + panelW - 9, y + 2, 0xFFD05050, false);
        }
    }

    /** Everything the row could not hold, in the order somebody deciding would want it. */
    private List<Text> explain(InscriptionIndex.Entry row) {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.translatable(row.enchantmentKey()).formatted(Formatting.WHITE));

        // What it actually does. Ours to write: vanilla ships a name and explains nothing anywhere
        // in the game, which is the single most common reason to go and look something up.
        lines.add(Text.translatable(effectKey(row.enchantment())).formatted(Formatting.GRAY));
        lines.add(Text.empty());

        lines.add(Text.translatable("kindreds.inscriptions.tip.stone",
            Text.translatable("kindreds.inscriptions.stone." + row.stone()))
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
    private static ItemStack stoneItem(String stone) {
        return switch (stone) {
            case "any" -> new ItemStack(Items.LAPIS_LAZULI);
            case "emerald" -> new ItemStack(Items.EMERALD);
            case "impossible" -> new ItemStack(Items.BARRIER);
            default -> {
                Item item = Registries.ITEM.get(Identifier.of("middle-earth", stone));
                yield item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
            }
        };
    }

    private static int[] columns(int panelW) {
        return new int[]{6, panelW - 152, panelW - 110, panelW - 76};
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
            case "ruby" -> 0xFFD05A5A;
            case "sapphire" -> 0xFF5A7AD0;
            case "emerald" -> 0xFF5AC07A;
            case "adamant" -> 0xFFC0A8E0;
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

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
