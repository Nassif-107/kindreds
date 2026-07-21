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
            case DEEDS -> client.setScreen(new KindredDeedsScreen(ClientKindredData.INSTANCE, hub));
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
        list.add(new Entry("D", "deeds", this::openDeeds, false));
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

        ctx.fill(0, 0, this.width, this.height, 0xC0140F0A);

        // A compass rose, not a list. Four points in fixed places become muscle memory - you stop
        // reading and simply move to where Skills is - and the layout stays symmetrical whether or
        // not the Rules point is there for you, instead of reflowing the moment it appears.
        // Sized so the rose actually fits the windows people play in: at GUI scale 2 a 854x480
        // window is only 427x240 in these units, which the first threshold shut out entirely and
        // dropped everyone into the plain grid.
        // For a rose that does not collide with itself the arm must clear half a plate: the north
        // plate's bottom edge sits at cy-arm, the east and west tops at cy-plate/2. With a shorter
        // arm they overlap and eat each other's labels. Everything else follows from that: the whole
        // figure spans 3*plate + 2*armPad, so the plate is sized from the room actually available
        // once the heading and footer have taken theirs.
        int room = Math.max(120, Math.min(this.width - 40, this.height - 78));
        int plate = Math.max(40, Math.min(68, (room - 16) / 3));
        int arm = plate / 2 + 5;
        boolean rose = this.width >= 260 && this.height >= 170;

        int cx = this.width / 2;
        int cy = Math.max(plate + arm + 40, this.height / 2 - 4);

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.hub.title").formatted(Formatting.GOLD),
                cx, cy - plate - arm - 30, 0xFFD8B45F);
        Text sub = race == null
                ? Text.translatable("kindreds.hub.noRace").formatted(Formatting.GRAY)
                : Text.translatableWithFallback("kindreds.race." + race.getPath(), race.getPath())
                        .copy().formatted(Formatting.ITALIC, Formatting.GRAY);
        ctx.drawCenteredTextWithShadow(this.textRenderer, sub, cx, cy - plate - arm - 18, 0xFF9A8F76);

        hits.clear();
        int[][] slots = rose ? rosePoints(cx, cy, plate, arm, entries.size())
                : gridPoints(cx, cy, plate, arm, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            int[] r = {slots[i][0], slots[i][1], plate, plate};
            hits.add(r);
        }

        // the ornament at the centre of the rose
        if (rose) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("\u2726").formatted(Formatting.GOLD),
                    cx, cy - 4, 0x88D8B45F);
        }

        Entry hovered = null;
        for (int i = 0; i < hits.size() && i < entries.size(); i++) {
            boolean hover = within(hits.get(i), mouseX, mouseY);
            if (hover) {
                hovered = entries.get(i);
            }
            point(ctx, hits.get(i), entries.get(i), i + 1, hover);
        }

        // One word on each plate keeps the rose clean; the sentence appears only when the mouse asks
        // for it, so a new player still gets told what a page is without it living on screen forever.
        int footY = (rose ? cy + plate + arm + 14 : cy + plate + arm + 44);
        footY = Math.min(footY, this.height - 22);
        if (hovered != null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("kindreds.hub." + hovered.key() + ".short").formatted(Formatting.GRAY),
                    cx, footY, 0xFF9A8F76);
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.hub.hint", entries.size()).formatted(Formatting.DARK_GRAY),
                cx, footY + 12, 0xFF6E6250);
    }

    /**
     * {@code count} points spaced evenly around the rose, the first due north.
     *
     * <p>At four this is still a diamond, and Traits still sits due north with Skills to its east -
     * the two positions worth protecting. Abilities moves from west to south, which is the price of
     * a fifth page: spacing by angle is what lets one join without the figure going lopsided, and no
     * arrangement of five keeps all four of the old places.
     */
    private static int[][] rosePoints(int cx, int cy, int plate, int arm, int count) {
        int half = plate / 2;
        // Far enough out that neighbouring plates cannot touch, whatever the count: the chord between
        // two adjacent points must clear a plate's width. At four to six this is inside the distance
        // the cardinals already used, so nothing moves; beyond that the rose widens rather than
        // letting the plates grind into each other.
        double clearance = count < 2 ? 0 : plate / (2 * Math.sin(Math.PI / count)) + 4;
        double radius = Math.max(arm + half, clearance);
        int[][] out = new int[Math.max(1, count)][2];
        for (int i = 0; i < out.length; i++) {
            double angle = -Math.PI / 2 + i * (2 * Math.PI / out.length);   // -90 degrees is north
            out[i][0] = (int) Math.round(cx + Math.cos(angle) * radius) - half;
            out[i][1] = (int) Math.round(cy + Math.sin(angle) * radius) - half;
        }
        return out;
    }

    /**
     * Fallback for a window too small for a rose: the same plates in rows of two, centred. A short
     * last row is centred rather than left-aligned, so three or five plates still look composed.
     */
    private static int[][] gridPoints(int cx, int cy, int plate, int gap, int count) {
        int cols = Math.min(2, Math.max(1, count));
        int rows = (count + cols - 1) / cols;
        int totalH = rows * plate + (rows - 1) * gap;
        int y0 = cy - totalH / 2;
        int[][] out = new int[Math.max(0, count)][2];
        for (int i = 0; i < count; i++) {
            int row = i / cols;
            int inRow = Math.min(cols, count - row * cols);   // the last row may be short
            int rowW = inRow * plate + (inRow - 1) * gap;
            out[i][0] = cx - rowW / 2 + (i % cols) * (plate + gap);
            out[i][1] = y0 + row * (plate + gap);
        }
        return out;
    }

    /** One point of the rose: a bordered plate, a large initial, and a single word beneath it. */
    private void point(DrawContext ctx, int[] r, Entry e, int number, boolean hover) {
        ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0xE0332612 : 0xB01A1510);
        ctx.drawBorder(r[0], r[1], r[2], r[3], hover ? 0xFFD8B45F : 0xFF3A3020);
        ctx.drawBorder(r[0] + 3, r[1] + 3, r[2] - 6, r[3] - 6,
                hover ? 0x66FFE9A8 : 0x22D8B45F);

        int cx = r[0] + r[2] / 2;
        // the initial, drawn large by scaling the matrix - the page has no bigger font to reach for
        var m = ctx.getMatrices();
        m.pushMatrix();
        float scale = 2.0f;
        m.translate(cx, r[1] + r[3] * 0.30f);
        m.scale(scale, scale);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(e.initial()),
                0, -this.textRenderer.fontHeight / 2, hover ? 0xFFFFE9A8 : 0xFFD8B45F);
        m.popMatrix();

        // One word per plate is the design, but a translation is not obliged to honour it - clipped
        // to the plate so a long word is cut rather than printed out over the rose.
        Text name = Text.translatable("kindreds.hub." + e.key())
                .copy().formatted(hover ? Formatting.WHITE : Formatting.GRAY);
        ctx.enableScissor(r[0] + 2, r[1], r[0] + r[2] - 2, r[1] + r[3]);
        ctx.drawCenteredTextWithShadow(this.textRenderer, name, cx, r[1] + r[3] - 20,
                hover ? 0xFFFFFFFF : 0xFFBBB0A0);
        ctx.disableScissor();

        String num = String.valueOf(number);
        ctx.drawText(this.textRenderer, Text.literal(num),
                r[0] + r[2] - this.textRenderer.getWidth(num) - 5, r[1] + 5,
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

    private void openDeeds() {
        com.kindreds.client.ClientUiState.remember(com.kindreds.client.ClientUiState.Page.DEEDS);
        MinecraftClient.getInstance().setScreen(new KindredDeedsScreen(ClientKindredData.INSTANCE, this));
    }

    private void openRules() {
        MinecraftClient.getInstance().setScreen(new KindredsSettingsScreen(this));
    }

    private static boolean within(int[] r, double x, double y) {
        return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
    }
}
