package com.minenorth.vehicles.depannage;

import com.minenorth.vehicles.VehiclesMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Items du mod garage et leur onglet créatif « Garage ». */
public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, VehiclesMod.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, VehiclesMod.MODID);

    public static final RegistryObject<Item> UNSTUCK_KIT = ITEMS.register("kit_depannage", UnstuckItem::new);
    public static final RegistryObject<Item> ZONE_WAND = ITEMS.register("baton_zone", ZoneWandItem::new);
    public static final RegistryObject<Item> GARAGE_TABLET = ITEMS.register("tablette_garage", () -> new MenuItem(MenuItem.Kind.GARAGE));
    public static final RegistryObject<Item> SHOP_TABLET = ITEMS.register("tablette_concessionnaire", () -> new MenuItem(MenuItem.Kind.SHOP));

    public static final RegistryObject<CreativeModeTab> GARAGE_TAB = TABS.register("garage", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + VehiclesMod.MODID + ".garage"))
            .icon(() -> new ItemStack(UNSTUCK_KIT.get()))
            .displayItems((params, out) -> {
                out.accept(GARAGE_TABLET.get());
                out.accept(SHOP_TABLET.get());
                out.accept(ZONE_WAND.get());
                out.accept(UNSTUCK_KIT.get());
            })
            .build());

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        TABS.register(modBus);
    }
}
