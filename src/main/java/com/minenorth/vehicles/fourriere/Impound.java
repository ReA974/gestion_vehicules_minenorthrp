package com.minenorth.vehicles.fourriere;

import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.garage.Garage;
import com.minenorth.vehicles.garage.GarageData;
import com.minenorth.vehicles.garage.Spawner;
import com.minenorth.vehicles.handle.Money;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.miscs.Gui;
import com.minenorth.vehicles.miscs.Msg;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.List;
import java.util.UUID;

public final class Impound {
    private Impound() {}

    public static String ownerName(MinecraftServer server, UUID id) {
        if (server.getProfileCache() == null) return id.toString().substring(0, 8);
        return server.getProfileCache().get(id).map(GameProfile::getName).orElse(id.toString().substring(0, 8));
    }

    // ------------------------------------------------------------------ police

    public static void openPoliceMenu(ServerPlayer p, Entity vehicle) {
        UUID owner = MtsBridge.ownerOf(vehicle);
        if (owner == null) {
            Msg.send(p, "&cCe véhicule n'a pas de propriétaire enregistré : impossible de le mettre en fourrière.");
            return;
        }
        CompoundTag snap = MtsBridge.snapshot(vehicle);
        String label = Garage.labelOf(MtsBridge.itemId(snap), snap);

        ItemStack[] items = new ItemStack[9];
        items[1] = Gui.item(Items.PAPER, "&ePropriétaire : &f" + ownerName(p.getServer(), owner), "&7Modèle : &f" + label);
        items[3] = Gui.item(Items.PAPER, "&e&lAppeler le mécano", "&7Le véhicule reste sur place, un mécano est prévenu.");
        items[5] = Gui.item(Items.PAPER, "&c&lForcer la mise en fourrière", "&7Retire le véhicule immédiatement et le met en fourrière.");
        Gui.open(p, "&cMise en fourrière", 1, items, slot -> {
            if (slot == 3) Gui.later(p, () -> {
                p.closeContainer();
                p.getServer().getPlayerList().broadcastSystemMessage(Gui.comp(ImpoundConfig.MECHANIC_MESSAGE.get()), false);
                Msg.send(p, "&aLe mécano a été prévenu.");
            });
            else if (slot == 5) Gui.later(p, () -> {
                p.closeContainer();
                if (!vehicle.isRemoved()) impound(p, vehicle);
            });
        });
    }

    public static boolean impound(ServerPlayer actor, Entity vehicle) {
        MinecraftServer server = actor.getServer();
        UUID owner = MtsBridge.ownerOf(vehicle);
        if (owner == null) {
            Msg.send(actor, "&cCe véhicule n'a pas de propriétaire enregistré.");
            return false;
        }
        ImpoundData d = ImpoundData.get(server);
        List<GarageData.Stored> list = d.of(owner);
        if (list.size() >= ImpoundConfig.MAX_PER_OWNER.get()) {
            Msg.send(actor, "&cLa fourrière de ce joueur est pleine.");
            return false;
        }
        CompoundTag snap = MtsBridge.snapshot(vehicle);
        GarageData.Stored s = new GarageData.Stored();
        s.nbt = snap;
        s.itemId = MtsBridge.itemId(snap);
        s.label = Garage.labelOf(s.itemId, snap);

        vehicle.discard();
        list.add(s);
        d.setDirty();
        Msg.send(actor, "&aVéhicule de " + ownerName(server, owner) + " mis en fourrière, intact.");
        ServerPlayer o = server.getPlayerList().getPlayer(owner);
        if (o != null) Msg.send(o, "&cTon véhicule (" + s.label + ") a été mis en fourrière. Rends-toi au PNJ de la fourrière.");
        return true;
    }

    // ------------------------------------------------------------------ récupération (PNJ)

    public static void openRecoverMenu(ServerPlayer p) {
        List<GarageData.Stored> list = ImpoundData.get(p.getServer()).of(p.getUUID());
        if (list.isEmpty()) {
            Msg.send(p, "&aTu n'as aucun véhicule en fourrière.");
            return;
        }
        int price = ImpoundConfig.PRICE.get();
        int n = Math.min(54, list.size());
        ItemStack[] items = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            GarageData.Stored s = list.get(i);
            items[i] = Gui.item(Garage.iconOf(s.itemId), "&e&l" + s.label,
                    "&c" + price + "€ pour récupérer", "&8Intact : pièces, carburant et coffres conservés");
        }
        Gui.open(p, "&cVéhicules en fourrière", (n + 8) / 9, items, slot -> {
            if (slot < n) Gui.later(p, () -> {
                p.closeContainer();
                recover(p, slot);
            });
        });
    }

    public static void recover(ServerPlayer p, int index) {
        MinecraftServer server = p.getServer();
        ImpoundData d = ImpoundData.get(server);
        if (!d.hasZone) {
            Msg.send(p, "&cAucune zone de pose n'a été configurée pour la fourrière. Contacte le staff.");
            return;
        }
        List<GarageData.Stored> list = d.of(p.getUUID());
        if (index < 0 || index >= list.size()) return;
        GarageData.Stored s = list.get(index);

        int price = ImpoundConfig.PRICE.get();
        List<VehicleConfig.Denom> den = VehicleConfig.currency();
        int total = Money.count(p, den);
        if (total < price) {
            Msg.send(p, "&cIl te manque " + (price - total) + "€ pour récupérer ce véhicule (" + price + "€, tu as " + total + "€).");
            return;
        }
        ResourceLocation rl = ResourceLocation.tryParse(d.zoneDim);
        ServerLevel level = rl == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
        if (level == null) {
            Msg.send(p, "&cDimension de la zone fourrière introuvable. Contacte le staff.");
            return;
        }
        Entity e = Spawner.spawn(level, s.nbt, d.zx, d.zy, d.zz, p.getUUID());
        if (e == null) {
            Msg.send(p, "&cImpossible de recréer ce véhicule (entité MTS introuvable ?). Il reste en fourrière, tu n'as pas été débité.");
            return;
        }
        Money.take(p, price, den);
        list.remove(index);
        d.setDirty();
        giveKey(p, s.label);
        Msg.send(p, "&aVéhicule récupéré pour " + price + "€, intact ! Il t'attend à la fourrière.");
    }

    private static void giveKey(ServerPlayer p, String label) {
        if (!VehicleConfig.GIVE_KEY.get()) return;
        Item k = VehicleConfig.item(VehicleConfig.KEY_ITEM.get());
        if (k == Items.AIR) return;
        ItemStack st = new ItemStack(k);
        st.setHoverName(Gui.comp("&e&l" + VehicleConfig.KEY_NAME_PREFIX.get() + " " + label));
        ItemHandlerHelper.giveItemToPlayer(p, st);
    }
}
