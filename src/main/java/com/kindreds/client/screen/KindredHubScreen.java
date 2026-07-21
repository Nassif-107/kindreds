package com.kindreds.client.screen;

import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * The page {@code K} opens: a choice between what you <b>are</b> and what you have <b>learned</b>.
 *
 * <p>Trait information used to live only inside the Codex item, which meant a player had to be
 * holding a book to read what their own blood does, while the skill tree sat behind a key. Both now
 * open from the same place.
 *
 * <p>Drawn as a leaf of the same book the rest of the mod is written in - ruled vellum, an
 * illuminated initial per choice, the kindred's own colour - rather than the star-map look of the
 * tree canvas. This is a title page, not a chart.
 */
public class KindredHubScreen extends Screen {
    /** Screen-space rect per rendered entry, rebuilt each frame alongside the rows it describes. */
    private final java.util.List<int[]> hits = new java.util.ArrayList<>();

    public KindredHubScreen() {
        super(Text.translatable("kindreds.hub.title"));
    }

    /**
     * Opens whichever page was last read - the hub itself the first time. Returning to the same
     * page is the common case by a wide margin, and making the player re-pick it every time is a
     * toll on the thing they do most.
     */
    public static void open(MinecraftClient client) {
        KindredHubScreen hub = new KindredHubScreen();
        switch (com.kindreds.client.ClientUiState.lastPage()) {
            case TRAITS -> client.setScreen(new KindredCodexScreen(ClientKindredData.INSTANCE, hub));
            case SKILLS -> client.setScreen(new SkillTreeScreen(ClientKindredData.INSTANCE, hub));
            case ABILITIES -> client.setScreen(
                    new com.kindreds.client.loadout.KindredLoadoutScreen(hub));
            default -> client.setScreen(hub);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** One choice on the page. {@code op} entries are only offered to an operator. */
    private record Entry(String initial, String key, Runnable action, boolean op) {
    }

    private java.util.List<Entry> entries() {
        java.util.List<Entry> list = new java.util.ArrayList<>();
        list.add(new Entry("T", "traits", this::openTraits, false));
        list.add(new Entry("S", "skills", this::openSkills, false));
        list.add(new Entry("A", "abilities", this::openAbilities, false));
        // Server rules are offered to an operator only. Not for secrecy - the server re-checks every
        // change regardless - but because a page nobody else can act on is a locked door on a menu.
        if (isOperator()) {
            list.add(new Entry("R", "rules", this::openRules, true));
        }
        return list;
    }

    private boolean isOperator() {
        return this.client != null && this.client.player != null
                && this.client.player.hasPermissionLevel(
                        com.kindreds.network.SetDifficultyC2S.OPERATOR_LEVEL);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        KindredData data = ClientKindredData.INSTANCE;
        Identifier race = data == null ? null : data.race();
        java.util.List<Entry> entries = entries();

        // Sleeker than the first cut: low, wide rows instead of tall text panels, so four choices fit
        // without shouting and the eye runs straight down the initials. Two columns when there is
        // width for them, one when there is not, so the page reads the same at any GUI scale.
        int cols = this.width >= 560 ? 2 : 1;
        int rowW = cols == 2 ? Math.min(240, (this.width - 70) / 2) : Math.min(300, this.width - 44);
        int rowH = 34;
        int gapX = 14;
        int gapY = 8;
        int rows = (entries.size() + cols - 1) / cols;
        int totalW = cols == 2 ? rowW * 2 + gapX : rowW;
        int totalH = rows * rowH + (rows - 1) * gapY;
        int x0 = (this.width - totalW) / 2;
        int y0 = Math.max(46, (this.height - totalH) / 2);

        ctx.fill(0, 0, this.width, this.height, 0xC0140F0A);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.hub.title").formatted(Formatting.GOLD),
                this.width / 2, y0 - 30, 0xFFD8B45F);
        Text sub = race == null
                ? Text.translatable("kindreds.hub.noRace").formatted(Formatting.GRAY)
                : Text.translatableWithFallback("kindreds.race." + race.getPath(), race.getPath())
                        .copy().formatted(Formatting.ITALIC, Formatting.GRAY);
        ctx.drawCenteredTextWithShadow(this.textRenderer, sub, this.width / 2, y0 - 18, 0xFF9A8F76);
        ctx.fill(x0, y0 - 7, x0 + totalW, y0 - 6, 0x40D8B45F);

        hits.clear();
        for (int i = 0; i < entries.size(); i++) {
            int col = cols == 2 ? i % 2 : 0;
            int row = cols == 2 ? i / 2 : i;
            int[] r = {x0 + col * (rowW + gapX), y0 + row * (rowH + gapY), rowW, rowH};
            hits.add(r);
            row(ctx, r, entries.get(i), i + 1, mouseX, mouseY);
        }

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.hub.hint").formatted(Formatting.DARK_GRAY),
                this.width / 2, y0 + totalH + 10, 0xFF6E6250);
    }

    /** A single row: a numbered initial, the name, and a quiet line of purpose beneath it. */
    private void row(DrawContext ctx, int[] r, Entry e, int number, int mouseX, int mouseY) {
        boolean hover = within(r, mouseX, mouseY);
        ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0xD82A2010 : 0xB01A1510);
        ctx.drawBorder(r[0], r[1], r[2], r[3], hover ? 0xFFD8B45F : 0xFF3A3020);
        if (hover) { // a gold rule down the leading edge, as the codex marks a chosen entry
            ctx.fill(r[0], r[1], r[0] + 2, r[1] + r[3], 0xFFD8B45F);
        }

        int capX = r[0] + 8;
        int capY = r[1] + (r[3] - 18) / 2;
        ctx.fill(capX, capY, capX + 18, capY + 18, hover ? 0x66D8B45F : 0x2AD8B45F);
        ctx.drawBorder(capX, capY, 18, 18, hover ? 0xFFD8B45F : 0xFF6A5A38);
        ctx.drawText(this.textRenderer, Text.literal(e.initial()), capX + 6, capY + 5, 0xFFFFE9A8, true);

        ctx.drawText(this.textRenderer,
                Text.translatable("kindreds.hub." + e.key()).formatted(Formatting.BOLD),
                capX + 26, r[1] + 7, hover ? 0xFFFFE9A8 : 0xFFE6DCC4, true);
        ctx.drawText(this.textRenderer,
                Text.translatable("kindreds.hub." + e.key() + ".short").formatted(Formatting.GRAY),
                capX + 26, r[1] + 19, 0xFF8A7F68, false);

        String num = String.valueOf(number);
        ctx.drawText(this.textRenderer, Text.literal(num),
                r[0] + r[2] - this.textRenderer.getWidth(num) - 7, r[1] + 13,
                hover ? 0xFFD8B45F : 0xFF5A5040, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        java.util.List<Entry> entries = entries();
        for (int i = 0; i < hits.size() && i < entries.size(); i++) {
            if (within(hits.get(i), mouseX, mouseY)) {
                entries.get(i).action().run();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Numbers pick a row - a menu you have to aim at is slower than the pages it fronts.
        java.util.List<Entry> entries = entries();
        int index = keyCode - 49; // GLFW '1'
        if (index >= 0 && index < entries.size()) {
            entries.get(index).action().run();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void openTraits() {
        com.kindreds.client.ClientUiState.remember(com.kindreds.client.ClientUiState.Page.TRAITS);
        MinecraftClient.getInstance().setScreen(new KindredCodexScreen(ClientKindredData.INSTANCE, this));
    }

    private void openSkills() {
        com.kindreds.client.ClientUiState.remember(com.kindreds.client.ClientUiState.Page.SKILLS);
        MinecraftClient.getInstance().setScreen(new SkillTreeScreen(ClientKindredData.INSTANCE, this));
    }

    private void openAbilities() {
        com.kindreds.client.ClientUiState.remember(com.kindreds.client.ClientUiState.Page.ABILITIES);
        MinecraftClient.getInstance().setScreen(
                new com.kindreds.client.loadout.KindredLoadoutScreen(this));
    }

    private void openRules() {
        MinecraftClient.getInstance().setScreen(new KindredsSettingsScreen(this));
    }

    private static boolean within(int[] r, double x, double y) {
        return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
    }
}
