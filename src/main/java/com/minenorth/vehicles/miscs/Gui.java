package com.minenorth.vehicles.miscs;

import com.minenorth.vehicles.VehiclesMod;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import java.util.function.IntConsumer;
import static com.minenorth.vehicles.config.VehicleConfig.THEMED_GUI;

/** Coffre-GUI 100 % serveur (le client vanilla suffit) : tous les clics sont annulés, seul le slot cliqué est remonté. */
public final class Gui extends ChestMenu {
    private final int size;
    private final IntConsumer onClick;
    private static final ResourceLocation FONT = new ResourceLocation(VehiclesMod.MODID, "gui");

    private Gui(MenuType<?> type, int id, Inventory inv, Container c, int rows, IntConsumer onClick) {
        super(type, id, inv, c, rows);
        this.size = rows * 9;
        this.onClick = onClick;
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot >= 0 && slot < size && type != ClickType.QUICK_CRAFT) onClick.accept(slot);
        this.sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    // New gui
    public static Component themedTitle(String raw, int rows) {
        if (!THEMED_GUI.get()) return comp(raw);
        String plain = raw.replaceAll("(?i)[&\u00a7][0-9a-fk-or]", "");
        MutableComponent bg = Component.literal("\uF000" + (char) (0xE000 + rows) + "\uF001")
                .withStyle(s -> s.withFont(FONT).withColor(0xFFFFFF));
        MutableComponent txt = Component.literal(plain)
                .withStyle(s -> s.withFont(Style.DEFAULT_FONT).withColor(0x9AD8FF));
        return bg.append(txt);
    }


    // old gui chest
    public static Component comp(String s) {
        return Component.literal(s.replace('&', '\u00a7')).withStyle(st -> st.withItalic(false));
    }

    public static ItemStack item(Item item, String name, String... lore) {
        ItemStack st = new ItemStack(item);
        st.setHoverName(comp(name));
        if (lore.length > 0) {
            ListTag l = new ListTag();
            for (String line : lore) l.add(StringTag.valueOf(Component.Serializer.toJson(comp(line))));
            st.getOrCreateTagElement("display").put("Lore", l);
        }
        return st;
    }

    public static void open(ServerPlayer p, String title, int rows, ItemStack[] items, IntConsumer onClick) {
        final int r = Math.max(1, Math.min(6, rows));
        SimpleContainer c = new SimpleContainer(r * 9);
        for (int i = 0; i < items.length && i < r * 9; i++) if (items[i] != null) c.setItem(i, items[i]);
        MenuType<?> type = switch (r) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new Gui(type, id, inv, c, r, onClick), themedTitle(title, r)));
    }

    /** Exécute après le clic courant (évite d'ouvrir/fermer un menu pendant son propre traitement). */
    public static void later(ServerPlayer p, Runnable r) {
        p.getServer().execute(r);
    }
}
