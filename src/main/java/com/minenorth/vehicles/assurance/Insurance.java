package com.minenorth.vehicles.assurance;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.fourriere.ImpoundConfig;
import com.minenorth.vehicles.fourriere.ImpoundData;
import com.minenorth.vehicles.garage.Garage;
import com.minenorth.vehicles.garage.GarageData;
import com.minenorth.vehicles.handle.EconomyBridge;
import com.minenorth.vehicles.handle.Money;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.handle.Payment;
import com.minenorth.vehicles.miscs.Gui;
import com.minenorth.vehicles.miscs.Msg;
import com.minenorth.vehicles.shop.Catalog;
import fr.minenorth.api.PayResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.UUID;

/**
 * Assurance véhicule. Le joueur paie l'assurance d'un véhicule de son garage ; le drapeau « assuré » suit le véhicule
 * (NBT). Si MTS le détruit, il est remis dans le garage de son propriétaire et n'est plus assuré.
 * Il revient dans l'état où il était à sa sortie du garage (copie gardée sur l'entité, voir {@link MtsBridge#ORIGIN}).
 * Prix : un pourcentage du prix catalogue du véhicule.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class Insurance {
    private Insurance() {}

    /** Prix de l'assurance : pourcentage du prix catalogue, avec un minimum ; prix fixe si le véhicule n'est pas au catalogue. */
    public static int price(String itemId) {
        Catalog.Vehicle v = Catalog.byItem(itemId);
        if (v == null || v.price <= 0) return InsuranceConfig.UNKNOWN_PRICE.get();
        long p = Math.round(v.price * InsuranceConfig.PERCENT.get() / 100.0);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(InsuranceConfig.MIN_PRICE.get(), p));
    }

    // ------------------------------------------------------------------ menus

    public static void openMenu(ServerPlayer p) {
        List<GarageData.Stored> list = GarageData.get(p.getServer()).of(p.getUUID());
        if (list.isEmpty()) {
            Msg.send(p, "&cTon garage est vide : range d'abord ton véhicule pour l'assurer.");
            return;
        }
        int n = Math.min(54, list.size());
        ItemStack[] items = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            GarageData.Stored s = list.get(i);
            boolean insured = MtsBridge.isInsured(s.nbt);
            items[i] = Gui.item(Garage.iconOf(s.itemId), "&e&l" + s.label,
                    insured ? "&bDéjà assuré" : "&7Assurance : &c" + price(s.itemId) + "€ &8(" + InsuranceConfig.PERCENT.get() + " % du prix)",
                    insured ? "&8S'il est détruit, il reviendra dans ton garage." : "&7Clique pour assurer ce véhicule");
        }
        Gui.open(p, "&bAssurance véhicule", (n + 8) / 9, items, slot -> {
            if (slot < n) Gui.later(p, () -> {
                if (MtsBridge.isInsured(list.get(slot).nbt)) Msg.send(p, "&eCe véhicule est déjà assuré.");
                else openPayment(p, slot);
            });
        });
    }

    private static void openPayment(ServerPlayer p, int index) {
        List<GarageData.Stored> list = GarageData.get(p.getServer()).of(p.getUUID());
        if (index < 0 || index >= list.size()) return;
        GarageData.Stored s = list.get(index);
        int price = price(s.itemId);
        int cash = Money.count(p, VehicleConfig.currency());
        PayResult card = Payment.cardStatus(p, price);

        ItemStack[] items = new ItemStack[9];
        items[4] = Gui.item(Garage.iconOf(s.itemId), "&e&l" + s.label, "&7Assurance : &c" + price + "€",
                "&8Détruit : il revient au garage dans son état actuel,", "&8puis il n'est plus assuré.");
        Item cashIcon = VehicleConfig.item("minenorth_eurobank:bill_50e");
        items[2] = Gui.item(cashIcon == Items.AIR ? Items.GOLD_INGOT : cashIcon, "&a&lPayer en espèces",
                "&7Espèces sur toi : &f" + cash + "€", cash >= price ? "&aClique pour payer" : "&cEspèces insuffisantes");
        Item cardIcon = VehicleConfig.item("minenorth_eurobank:bank_card");
        String cardLine = card == PayResult.OK ? "&aClique pour payer"
                : "&c" + (card == PayResult.INSUFFICIENT_FUNDS ? "Solde insuffisant" : card.message());
        items[6] = Gui.item(cardIcon == Items.AIR ? Items.PAPER : cardIcon, "&b&lPayer par carte",
                "&7Solde du compte : &f" + EconomyBridge.balance(p) + "€", cardLine);
        items[8] = Gui.item(Items.ARROW, "&c« Retour");
        Gui.open(p, "&bAssurer ce véhicule", 1, items, slot -> {
            if (slot == 2) Gui.later(p, () -> { p.closeContainer(); buy(p, index, Payment.Method.CASH); });
            else if (slot == 6) Gui.later(p, () -> { p.closeContainer(); buy(p, index, Payment.Method.CARD); });
            else if (slot == 8) Gui.later(p, () -> openMenu(p));
        });
    }

    private static void buy(ServerPlayer p, int index, Payment.Method method) {
        List<GarageData.Stored> list = GarageData.get(p.getServer()).of(p.getUUID());
        if (index < 0 || index >= list.size()) return;
        GarageData.Stored s = list.get(index);
        if (MtsBridge.isInsured(s.nbt)) { Msg.send(p, "&eCe véhicule est déjà assuré."); return; }
        int price = price(s.itemId);
        List<VehicleConfig.Denom> den = VehicleConfig.currency();
        String err = Payment.precheck(p, price, method, den, "pour assurer ce véhicule");
        if (err != null) { Msg.send(p, "&c" + err); return; }
        Payment.Result paid = Payment.pay(p, price, method, den, "garage:assurance");
        if (!paid.ok()) { Msg.send(p, "&c" + paid.message()); return; }
        MtsBridge.setInsured(s.nbt, true);
        GarageData.get(p.getServer()).setDirty();
        Msg.send(p, "&a" + s.label + " est assuré pour " + price + "€. S'il est détruit, il reviendra dans ton garage.");
    }

    // ------------------------------------------------------------------ indemnisation

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent e) {
        Entity v = e.getEntity();
        if (e.getLevel().isClientSide() || !(e.getLevel() instanceof ServerLevel level) || !MtsBridge.isVehicle(v)) return;
        if (!MtsBridge.isInsured(v) || v.getPersistentData().getBoolean(MtsBridge.HANDLED)) return;
        // Retrait par MTS (explosion, choc, dégâts) : pas un déchargement de chunk ni un changement de dimension.
        Entity.RemovalReason why = v.getRemovalReason();
        if (why != Entity.RemovalReason.KILLED && why != Entity.RemovalReason.DISCARDED) return;
        UUID owner = MtsBridge.ownerOf(v);
        if (owner == null) return;
        MinecraftServer server = level.getServer();

        // État de sortie du garage ; à défaut (ne devrait pas arriver), l'état actuel remis à neuf.
        CompoundTag snap = MtsBridge.origin(v);
        if (snap == null) {
            snap = MtsBridge.snapshot(v);
            MtsBridge.repair(snap);
        }
        MtsBridge.setInsured(snap, false);
        GarageData.Stored s = new GarageData.Stored();
        s.nbt = snap;
        s.itemId = MtsBridge.itemId(snap);
        s.label = Garage.labelOf(s.itemId, snap);

        GarageData garage = GarageData.get(server);
        List<GarageData.Stored> list = garage.of(owner);
        boolean toGarage = list.size() < VehicleConfig.GARAGE_MAX.get();
        if (toGarage) {
            list.add(s);
            garage.setDirty();
        } else {
            ImpoundData imp = ImpoundData.get(server);
            imp.of(owner).add(s);
            imp.setDirty();
        }
        ServerPlayer o = server.getPlayerList().getPlayer(owner);
        if (o != null) Msg.send(o, "&b[Assurance] &aTon véhicule &e" + s.label + "&a a été détruit : l'assurance le remet "
                + (toGarage ? "dans ton garage" : "à la fourrière (ton garage est plein)") + ", dans l'état où tu l'avais sorti. Il n'est plus assuré.");
    }
}
