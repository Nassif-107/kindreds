package com.kindreds.client.loadout;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The ability-loadout assignment screen: one button per {@link ClientLoadout#SLOTS} slot that cycles
 * through the empty option plus every unlocked active ability, so the player fills their four slots
 * with the actives they've earned. Deliberately simple (cycle buttons, not drag-and-drop) so it's
 * robust. Renders its own flat background - not the vanilla blur, which crashes non-standard screens
 * under Sodium/Iris (same reason {@code KindredCodexScreen}/{@code SkillTreeScreen} do).
 */
public final class KindredLoadoutScreen extends Screen {

    /** {@code ""} (empty) followed by each unlocked active id - the options each slot cycles through. */
    private List<String> options = List.of("");

    public KindredLoadoutScreen() {
        super(Text.literal("Ability Loadout"));
    }

    @Override
    protected void init() {
        options = new ArrayList<>();
        options.add(""); // the "empty this slot" option
        options.addAll(ClientLoadout.unlockedAbilityIds());

        int cx = this.width / 2;
        int top = this.height / 2 - (ClientLoadout.SLOTS * 26) / 2;
        for (int i = 0; i < ClientLoadout.SLOTS; i++) {
            final int slot = i;
            addDrawableChild(ButtonWidget.builder(slotLabel(slot), button -> {
                cycleSlot(slot);
                button.setMessage(slotLabel(slot));
            }).dimensions(cx - 110, top + i * 26, 220, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(cx - 60, top + ClientLoadout.SLOTS * 26 + 14, 120, 20).build());
    }

    private Text slotLabel(int slot) {
        String id = ClientLoadout.slot(slot);
        Formatting colour = id.isEmpty() ? Formatting.DARK_GRAY : Formatting.GOLD;
        return Text.literal("Slot " + (slot + 1) + ":  " + ClientLoadout.displayName(id)).formatted(colour);
    }

    private void cycleSlot(int slot) {
        if (options.isEmpty()) {
            return;
        }
        int idx = options.indexOf(ClientLoadout.slot(slot));
        if (idx < 0) {
            idx = 0;
        }
        ClientLoadout.setSlot(slot, options.get((idx + 1) % options.size()));
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xC8101014);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = this.height / 2 - (ClientLoadout.SLOTS * 26) / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Ability Loadout").formatted(Formatting.GOLD, Formatting.BOLD), cx, top - 40, 0xFFFFD780);
        if (options.size() <= 1) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Unlock active abilities in your skill tree to fill these slots.")
                            .formatted(Formatting.GRAY), cx, top - 22, 0xFFAAAAAA);
        } else {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Click a slot to cycle through your unlocked actives.")
                            .formatted(Formatting.GRAY), cx, top - 22, 0xFFAAAAAA);
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("In-world: cycle-slot key selects, use-ability key fires the selected slot.")
                        .formatted(Formatting.DARK_GRAY), cx, top + ClientLoadout.SLOTS * 26 + 40, 0xFF888888);
    }
}
