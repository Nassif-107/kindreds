package com.kindreds.client.screen;

import com.kindreds.inscription.InscriptionCategory;
import com.kindreds.inscription.InscriptionIndex;
import com.kindreds.network.RequestInscriptionsC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The inscription table, written out - what each enchantment costs, and what it is cut from.
 *
 * <p>Middle-earth's own table tells you nothing until you already know the answer. It shows the
 * words a stone grants, but not which combination means anything, so finding an enchantment means
 * trying combinations against a stone that survives three uses. There are around 160 recipes; the
 * arithmetic on that is not a puzzle, it is a wiki tab open on a second monitor. This is the page
 * that makes the second monitor unnecessary.
 *
 * <p>Rows are the server's, not this mod's - see {@code InscriptionIndex}. What is shown is whatever
 * the connected server actually runs, so a datapack that adds or reprices an inscription shows up
 * here without anybody editing a table.
 */
public class InscriptionsScreen extends Screen {

    /** Set by the network handler when the server answers; null while the request is in flight. */
    private static volatile List<InscriptionIndex.Entry> rows;

    private final Screen parent;
    private int scroll;
    private int maxScroll;

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

        int panelW = Math.min(420, this.width - 24);
        int x = (this.width - panelW) / 2;
        int top = 10;
        int bottom = this.height - 10;

        ctx.fill(x - 8, top, x + panelW + 8, bottom, 0xE0120F0A);
        ctx.drawBorder(x - 8, top, panelW + 16, bottom - top, 0xFF4A3D28);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("kindreds.inscriptions.title").formatted(Formatting.GOLD),
            this.width / 2, top + 6, 0xFFD8B45F);

        List<InscriptionIndex.Entry> table = rows;
        if (table == null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.inscriptions.waiting").formatted(Formatting.GRAY),
                this.width / 2, top + 40, 0xFF8A7C60);
            return;
        }
        if (table.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.inscriptions.none").formatted(Formatting.GRAY),
                this.width / 2, top + 40, 0xFF8A7C60);
            return;
        }

        int headerBottom = top + 20;
        // The column heads sit outside the scrolled band so they stay readable while the body moves.
        drawColumns(ctx, x, headerBottom, panelW,
            Text.translatable("kindreds.inscriptions.col.enchantment").formatted(Formatting.DARK_GRAY),
            Text.translatable("kindreds.inscriptions.col.words").formatted(Formatting.DARK_GRAY),
            Text.translatable("kindreds.inscriptions.col.stone").formatted(Formatting.DARK_GRAY),
            Text.translatable("kindreds.inscriptions.col.chisel").formatted(Formatting.DARK_GRAY),
            Text.translatable("kindreds.inscriptions.col.cost").formatted(Formatting.DARK_GRAY));
        int bodyTop = headerBottom + 12;

        List<Object[]> lines = layout(table);
        int contentHeight = lines.size() * ROW_H;
        maxScroll = Math.max(0, contentHeight - (bottom - bodyTop - 6));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        ctx.enableScissor(x - 8, bodyTop, x + panelW + 8, bottom - 4);
        int y = bodyTop - scroll;
        for (Object[] line : lines) {
            if (y + ROW_H >= bodyTop && y <= bottom) {
                if (line[0] == null) {
                    // A section heading: the category, in the panel's own gold.
                    ctx.drawText(this.textRenderer,
                        Text.translatable((String) line[1]).formatted(Formatting.GOLD),
                        x, y + 2, 0xFFD8B45F, false);
                } else {
                    drawRow(ctx, x, y, panelW, (InscriptionIndex.Entry) line[0]);
                }
            }
            y += ROW_H;
        }
        ctx.disableScissor();

        if (maxScroll > 0) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.inscriptions.scroll").formatted(Formatting.DARK_GRAY),
                this.width / 2, bottom - 12, 0xFF6A5F4A);
        }
    }

    private static final int ROW_H = 11;

    /** Section headings interleaved with their rows, so one walk draws the whole page. */
    private List<Object[]> layout(List<InscriptionIndex.Entry> table) {
        List<Object[]> lines = new ArrayList<>();
        String section = null;
        for (InscriptionIndex.Entry row : table) {
            if (!row.category().equals(section)) {
                section = row.category();
                if (!lines.isEmpty()) {
                    lines.add(new Object[]{null, "kindreds.inscriptions.blank"});
                }
                lines.add(new Object[]{null, categoryKey(section)});
            }
            lines.add(new Object[]{row, null});
        }
        return lines;
    }

    private static String categoryKey(String category) {
        return switch (category) {
            case InscriptionCategory.WEAPON -> "kindreds.inscriptions.cat.weapon";
            case InscriptionCategory.ARCHERY -> "kindreds.inscriptions.cat.archery";
            case InscriptionCategory.ARMOUR -> "kindreds.inscriptions.cat.armour";
            case InscriptionCategory.TOOL -> "kindreds.inscriptions.cat.tool";
            default -> "kindreds.inscriptions.cat.other";
        };
    }

    /** The five column x-offsets, as fractions of the panel, so the table reflows with the window. */
    private void drawColumns(DrawContext ctx, int x, int y, int panelW, Text... cells) {
        int[] at = columns(panelW);
        for (int i = 0; i < cells.length && i < at.length; i++) {
            ctx.drawText(this.textRenderer, cells[i], x + at[i], y, 0xFF6A5F4A, false);
        }
    }

    private static int[] columns(int panelW) {
        return new int[]{
            0,
            (int) (panelW * 0.34),
            (int) (panelW * 0.64),
            (int) (panelW * 0.78),
            (int) (panelW * 0.90),
        };
    }

    private void drawRow(DrawContext ctx, int x, int y, int panelW, InscriptionIndex.Entry row) {
        int[] at = columns(panelW);

        // The enchantment's own vanilla name, with its level range when it has more than one.
        Text name = Text.translatable(row.enchantmentKey());
        String label = row.maxLevel() > 1
            ? name.getString() + " I-" + roman(row.maxLevel())
            : name.getString();
        ctx.drawText(this.textRenderer, Text.literal(trim(label, at[1] - at[0] - 4))
            .formatted(Formatting.WHITE), x, y + 1, 0xFFECE3CD, false);

        ctx.drawText(this.textRenderer,
            Text.literal(trim(String.join(" + ", row.words()), at[2] - at[1] - 4))
                .formatted(Formatting.GRAY), x + at[1], y + 1, 0xFFB6A888, false);

        // The stone, in its own colour, because that is how it is recognised in the world.
        ctx.drawText(this.textRenderer,
            Text.translatable("kindreds.inscriptions.stone." + row.stone())
                .withColor(stoneColour(row.stone())),
            x + at[2], y + 1, stoneColour(row.stone()), false);

        ctx.drawText(this.textRenderer,
            Text.translatable("kindreds.inscriptions.chisel." + row.chisel())
                .withColor(chiselColour(row.chisel())),
            x + at[3], y + 1, chiselColour(row.chisel()), false);

        String cost = row.minCost() == row.maxCost()
            ? String.valueOf(row.minCost())
            : row.minCost() + "-" + row.maxCost();
        ctx.drawText(this.textRenderer, Text.literal(cost).formatted(Formatting.AQUA),
            x + at[4], y + 1, 0xFF7FD0E0, false);
    }

    /** Roman numerals to V, which is every level any enchantment in the game reaches. */
    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }

    private static int stoneColour(String stone) {
        return switch (stone) {
            case "emerald" -> 0xFF5CD65C;
            case "ruby" -> 0xFFE05555;
            case "sapphire" -> 0xFF5C8CE0;
            case "adamant" -> 0xFFE0E0E0;
            case "impossible" -> 0xFFFF4040;
            default -> 0xFF9A8F76;
        };
    }

    private static int chiselColour(String chisel) {
        return switch (chisel) {
            case "iron" -> 0xFFB0B0B0;
            case "steel" -> 0xFF8FB8D8;
            case "mithril" -> 0xFF9FE8E8;
            default -> 0xFF9A8F76;
        };
    }

    /** Clips a cell to its column rather than letting it run into the next one. */
    private String trim(String text, int width) {
        if (this.textRenderer.getWidth(text) <= width) {
            return text;
        }
        String cut = this.textRenderer.trimToWidth(text, Math.max(0, width - 6));
        return cut + "…";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        scroll = Math.max(0, Math.min(scroll - (int) Math.round(vertical * 16), maxScroll));
        return true;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
            return;
        }
        super.close();
    }
}
