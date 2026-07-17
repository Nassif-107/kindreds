package com.kindreds.client.screen;

import com.kindreds.data.Theme;
import net.minecraft.util.Identifier;

/**
 * Resolves a race's {@link Theme} into the concrete colors {@link SkillTreeScreen} /
 * {@link TreeRenderer} / {@link NodeTooltip} draw with, and supplies safe fallbacks when no theme
 * is available (registry not yet synced, or the tree/theme lookup failed) so the screen never has
 * a null-color crash - it just reads as a plain, slightly duller default instead of a themed one.
 *
 * <p>{@link Theme} (Task 3) only carries {@code primaryColor}/{@code secondaryColor}/
 * {@code backgroundTexture} - deliberately not the richer node-sprite/edge-style/font/sfx schema
 * sketched in the design doc (that's future-phase art pipeline work). Everything here is derived
 * from just those three fields: {@code primaryColor} is read as the race's <b>accent</b> (glow,
 * available/owned nodes, edges, borders), {@code secondaryColor} as the muted background/panel tone.
 */
public final class ThemeAssets {
    private ThemeAssets() {
    }

    /** Fallback accent (cool grey-blue) used when no theme could be resolved at all. */
    private static final int FALLBACK_ACCENT = 0xFF9FB4C8;
    private static final int FALLBACK_SECONDARY = 0xFF303840;

    /** The race's accent color (opaque ARGB), or a neutral fallback if {@code theme} is null. */
    public static int accent(Theme theme) {
        return theme != null ? opaque(theme.primaryColor()) : FALLBACK_ACCENT;
    }

    /** The race's secondary/muted tone (opaque ARGB), or a neutral fallback if {@code theme} is null. */
    public static int secondary(Theme theme) {
        return theme != null ? opaque(theme.secondaryColor()) : FALLBACK_SECONDARY;
    }

    /** Background texture id for {@code theme}, or {@code null} (caller falls back to a flat fill)
     * if there is no theme to draw. */
    public static Identifier background(Theme theme) {
        return theme != null ? theme.backgroundTexture() : null;
    }

    // --- Derived node-state colors ---------------------------------------------------------------

    /** Locked node: dim, desaturated-toward-black secondary tone - "etched, not lit". */
    public static int lockedColor(Theme theme) {
        return mix(secondary(theme), 0xFF000000, 0.55f);
    }

    /** Available node: the race's accent at full strength - "pulsing, ready". */
    public static int availableColor(Theme theme) {
        return accent(theme);
    }

    /** Sealed capstone (deed not yet proven): accent mixed toward a warm gold warning tint, so it
     * reads as "special", distinct from a plain available node. */
    public static int sealedColor(Theme theme) {
        return mix(accent(theme), 0xFFFFD27A, 0.45f);
    }

    /** Owned node: accent brightened toward white - "fully lit". */
    public static int ownedColor(Theme theme) {
        return mix(accent(theme), 0xFFFFFFFF, 0.35f);
    }

    /** Edge (prerequisite line) color: secondary tone lifted slightly toward the accent, so edges
     * read as connective tissue rather than pure background noise. */
    public static int edgeColor(Theme theme) {
        return mix(secondary(theme), accent(theme), 0.3f);
    }

    /** Warning/curse-tradeoff tint used in tooltips, independent of race (a curse should always
     * read as "danger", regardless of theme). */
    public static final int WARNING_COLOR = 0xFFE0A030;

    // --- Color math ---------------------------------------------------------------------------

    /** Forces full alpha (0xFF) on a packed 0xRRGGBB (or already-ARGB) color int. */
    public static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }

    /** Linear-interpolates two opaque ARGB colors channel-wise; {@code t=0} -> {@code a}, {@code t=1}
     * -> {@code b}. */
    public static int mix(int a, int b, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * clamped);
        int g = Math.round(ag + (bg - ag) * clamped);
        int bl = Math.round(ab + (bb - ab) * clamped);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Same color with a different alpha byte (0-255) - handy for translucent overlay fills. */
    public static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }
}
