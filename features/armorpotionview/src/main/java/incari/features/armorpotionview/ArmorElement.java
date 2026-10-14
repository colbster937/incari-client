package incari.features.armorpotionview;

import incari.features.armorpotionview.ArmorPotionView.Durability;
import incari.features.armorpotionview.ArmorPotionView.Layout;
import incari.framework.game.EquipmentSlot;
import incari.framework.game.ItemView;
import incari.framework.game.PlayerView;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.HudElement;
import incari.framework.hud.Tone;
import org.jspecify.annotations.Nullable;

/**
 * The armor the player wears, helmet first, then what they hold: each item's icon with the game's
 * durability bar, and its durability written beside it in the module's format.
 */
final class ArmorElement extends HudElement {

    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final int ICON = 16;
    /** Between an icon and its text. */
    private static final int TEXT_GAP = 2;
    /** Between rows (vertical) or cells (horizontal). */
    private static final int ROW_GAP = 1;

    private static final int CELL_GAP = 4;

    private final ArmorPotionView module;

    // This draw's rows, refilled without allocating each time the HUD asks.
    private final ItemView[] items = new ItemView[ARMOR.length + 1];
    private final @Nullable String[] texts = new String[ARMOR.length + 1];

    // Each row's text, made again only when its numbers change.
    private final int[] cachedDamage = new int[ARMOR.length + 1];
    private final int[] cachedMax = new int[ARMOR.length + 1];
    private final @Nullable Durability[] cachedFormat = new Durability[ARMOR.length + 1];
    private final @Nullable String[] cachedText = new String[ARMOR.length + 1];

    ArmorElement(ArmorPotionView module) {
        super("armor", "Armor", Anchor.BOTTOM_RIGHT, 2, 2);
        this.module = module;
    }

    @Override
    public boolean isVisible() {
        return module.showArmor.get() && collect(false) > 0;
    }

    @Override
    public boolean isPlaceable() {
        return module.showArmor.get();
    }

    @Override
    public int width(HudCanvas canvas) {
        int count = collect(canvas.editing());
        if (module.layout.get() == Layout.HORIZONTAL) {
            int width = 0;
            for (int i = 0; i < count; i++) {
                width += (i > 0 ? CELL_GAP : 0) + cellWidth(canvas, i);
            }
            return width;
        }
        int width = 0;
        for (int i = 0; i < count; i++) {
            width = Math.max(width, cellWidth(canvas, i));
        }
        return width;
    }

    @Override
    public int height(HudCanvas canvas) {
        int count = collect(canvas.editing());
        if (count == 0) {
            return 0;
        }
        return module.layout.get() == Layout.HORIZONTAL ? ICON : count * (ICON + ROW_GAP) - ROW_GAP;
    }

    @Override
    public void draw(HudCanvas canvas) {
        int count = collect(canvas.editing());
        boolean horizontal = module.layout.get() == Layout.HORIZONTAL;
        int textY = (ICON - canvas.lineHeight()) / 2 + 1;
        int x = 0;
        int y = 0;
        for (int i = 0; i < count; i++) {
            ItemView item = items[i];
            canvas.item(item, x, y, true);
            String text = texts[i];
            if (text != null) {
                canvas.text(text, x + ICON + TEXT_GAP, y + textY, tone(item));
            }
            if (horizontal) {
                x += cellWidth(canvas, i) + CELL_GAP;
            } else {
                y += ICON + ROW_GAP;
            }
        }
    }

    private int cellWidth(HudCanvas canvas, int row) {
        String text = texts[row];
        return text == null ? ICON : ICON + TEXT_GAP + canvas.textWidth(text);
    }

    private Tone tone(ItemView item) {
        if (!module.wearColors.get() || item.maxDamage() <= 0) {
            return Tone.TEXT;
        }
        // Thresholds in whole numbers: remaining / max below a half, below a fifth.
        long remaining = item.remaining();
        long max = item.maxDamage();
        if (remaining * 5 < max) {
            return Tone.NEGATIVE;
        }
        return remaining * 2 < max ? Tone.WARNING : Tone.POSITIVE;
    }

    /**
     * Fills this draw's rows from the player (the sample in the editor when the real player has
     * nothing to show) and returns how many.
     */
    private int collect(boolean editing) {
        PlayerView player = module.player();
        int rows = player == null ? 0 : fill(player);
        return rows == 0 && editing ? fill(module.samplePlayer()) : rows;
    }

    private int fill(PlayerView player) {
        int count = 0;
        for (EquipmentSlot slot : ARMOR) {
            ItemView item = player.item(slot);
            if (!item.isEmpty()) {
                count = add(count, item);
            }
        }
        if (module.showHeld.get()) {
            ItemView held = player.item(EquipmentSlot.MAINHAND);
            if (!held.isEmpty()) {
                count = add(count, held);
            }
        }
        return count;
    }

    private int add(int row, ItemView item) {
        items[row] = item;
        // The bar alone: the icon carries it, nothing is written beside it.
        texts[row] = item.isDamageable() && module.durability.get() != Durability.BAR ? text(row, item) : null;
        return row + 1;
    }

    /** {@code item}'s durability in the module's format, made again only when it changed. */
    private String text(int row, ItemView item) {
        int damage = item.damage();
        int max = item.maxDamage();
        Durability format = module.durability.get();
        String cached = cachedText[row];
        if (cached != null && cachedDamage[row] == damage && cachedMax[row] == max && cachedFormat[row] == format) {
            return cached;
        }
        String text = format(format, max - damage, max);
        cachedDamage[row] = damage;
        cachedMax[row] = max;
        cachedFormat[row] = format;
        cachedText[row] = text;
        return text;
    }

    /** "86%", "312", "312/363", or nothing for the bar alone. */
    static String format(Durability format, int remaining, int max) {
        return switch (format) {
            case PERCENT -> percent(remaining, max) + "%";
            case REMAINING -> Integer.toString(remaining);
            case REMAINING_OF_MAX -> remaining + "/" + max;
            case BAR -> "";
        };
    }

    /** Whole percent left, rounded down, but 1 while anything's left: 0% is broken. */
    static int percent(int remaining, int max) {
        if (max <= 0 || remaining <= 0) {
            return 0;
        }
        return Math.max(1, (int) ((long) remaining * 100 / max));
    }
}
