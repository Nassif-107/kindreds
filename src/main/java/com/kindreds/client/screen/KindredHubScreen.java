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
    private int[] traitsPlate = new int[4];
    private int[] skillsPlate = new int[4];

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
            default -> client.setScreen(hub);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        KindredData data = ClientKindredData.INSTANCE;
        Identifier race = data == null ? null : data.race();

        // Everything scales off the window, so the page reads the same at any GUI scale: two plates
        // side by side when there is width for them, stacked when there is not.
        boolean narrow = this.width < 420;
        int plateW = narrow ? Math.min(300, this.width - 40) : Math.min(210, (this.width - 60) / 2);
        int plateH = narrow ? 74 : Math.min(150, this.height - 130);
        int gap = narrow ? 10 : 18;
        int totalW = narrow ? plateW : plateW * 2 + gap;
        int x0 = (this.width - totalW) / 2;
        int y0 = Math.max(52, (this.height - (narrow ? plateH * 2 + gap : plateH)) / 2 + 8);

        // page
        ctx.fill(0, 0, this.width, this.height, 0xC0140F0A);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.hub.title").formatted(Formatting.GOLD),
                this.width / 2, y0 - 34, 0xFFD8B45F);
        Text sub = race == null
                ? Text.translatable("kindreds.hub.noRace").formatted(Formatting.GRAY)
                : Text.translatableWithFallback("kindreds.race." + race.getPath(), race.getPath())
                        .copy().formatted(Formatting.ITALIC, Formatting.GRAY);
        ctx.drawCenteredTextWithShadow(this.textRenderer, sub, this.width / 2, y0 - 20, 0xFF9A8F76);
        // a ruled line under the heading, as in the codex
        ctx.fill(x0, y0 - 8, x0 + totalW, y0 - 7, 0x40D8B45F);

        traitsPlate = new int[]{x0, y0, plateW, plateH};
        skillsPlate = narrow ? new int[]{x0, y0 + plateH + gap, plateW, plateH}
                : new int[]{x0 + plateW + gap, y0, plateW, plateH};

        plate(ctx, traitsPlate, "T", "kindreds.hub.traits", "kindreds.hub.traits.desc", mouseX, mouseY);
        plate(ctx, skillsPlate, "S", "kindreds.hub.skills", "kindreds.hub.skills.desc", mouseX, mouseY);

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.hub.hint").formatted(Formatting.DARK_GRAY),
                this.width / 2, y0 + (narrow ? plateH * 2 + gap : plateH) + 12, 0xFF6E6250);
    }

    /** One illuminated choice: a bordered leaf with a large initial, a name and a line of purpose. */
    private void plate(DrawContext ctx, int[] r, String initial, String titleKey, String descKey,
                       int mouseX, int mouseY) {
        boolean hover = within(r, mouseX, mouseY);
        ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0xE02A2010 : 0xC01A1510);
        ctx.drawBorder(r[0], r[1], r[2], r[3], hover ? 0xFFD8B45F : 0xFF4A3D28);
        ctx.drawBorder(r[0] + 3, r[1] + 3, r[2] - 6, r[3] - 6, 0x33D8B45F);

        // illuminated initial
        int capSize = Math.min(34, r[3] / 3);
        int capX = r[0] + 12;
        int capY = r[1] + 12;
        ctx.fill(capX, capY, capX + capSize, capY + capSize, hover ? 0x66D8B45F : 0x33D8B45F);
        ctx.drawBorder(capX, capY, capSize, capSize, 0xFFD8B45F);
        ctx.drawText(this.textRenderer, Text.literal(initial),
                capX + capSize / 2 - 2, capY + capSize / 2 - 4, 0xFFFFE9A8, true);

        ctx.drawText(this.textRenderer, Text.translatable(titleKey).formatted(Formatting.BOLD),
                capX + capSize + 10, capY + 4, hover ? 0xFFFFE9A8 : 0xFFE6DCC4, true);

        int textY = capY + capSize + 10;
        for (var line : this.textRenderer.wrapLines(
                Text.translatable(descKey).formatted(Formatting.GRAY), r[2] - 24)) {
            if (textY > r[1] + r[3] - 12) {
                break;
            }
            ctx.drawText(this.textRenderer, line, r[0] + 12, textY, 0xFF9A8F76, false);
            textY += 10;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (within(traitsPlate, mouseX, mouseY)) {
            openTraits();
            return true;
        }
        if (within(skillsPlate, mouseX, mouseY)) {
            openSkills();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 1/2 and T/S, because a hub you have to aim at with a mouse is slower than the tree it fronts
        if (keyCode == 49 || keyCode == 84) {
            openTraits();
            return true;
        }
        if (keyCode == 50 || keyCode == 83) {
            openSkills();
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

    private static boolean within(int[] r, double x, double y) {
        return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
    }
}
