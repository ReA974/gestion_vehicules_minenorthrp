package com.minenorth.vehicles.pompe;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.handle.EconomyBridge;
import com.minenorth.vehicles.handle.Money;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.handle.Payment;
import com.minenorth.vehicles.miscs.Gui;
import com.minenorth.vehicles.miscs.Msg;
import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.List;
import java.util.Locale;

/**
 * Menu de la pompe MTS : faire le plein du véhicule (payé au litre) ou remplir la pompe avec l'essence du mod Récolte.
 * Tout passe par le réservoir natif de la pompe (EntityFluidTank) : une pompe remplie ici se vide comme n'importe quelle pompe MTS.
 */
public final class PompeMenu {
    private PompeMenu() {}

    /** Résultat du calcul d'un plein : soit error != null, soit les quantités. */
    private record Quote(Entity vehicle, Object pumpTank, Object vehicleTank, String fluid, boolean creative, double mb, int price, String error) {
        static Quote fail(String why) { return new Quote(null, null, null, "", false, 0, 0, why); }
    }

    private static double mbPerLiter() { return Math.max(1, VehicleConfig.PUMP_MB_PER_LITER.get()); }

    private static String fmt(double liters) { return String.format(Locale.ROOT, liters >= 100 ? "%.0f" : "%.1f", liters); }

    private static int priceFor(double liters) {
        double eur = liters * VehicleConfig.PUMP_PRICE_LITER.get();
        return eur <= 0 ? 0 : Math.max(1, (int) Math.ceil(eur - 1e-9));
    }

    public static void open(ServerPlayer p, BlockPos pos) {
        Gui.later(p, () -> main(p, pos));
    }

    // ------------------------------------------------------------------ véhicule

    /** Le véhicule motorisé dans lequel est le joueur, sinon le plus proche de la pompe. */
    private static Entity findVehicle(ServerPlayer p, BlockPos pos) {
        double r = VehicleConfig.PUMP_RADIUS.get();
        Vec3 c = Vec3.atCenterOf(pos);
        Entity ride = MtsBridge.vehicleOf(p);
        if (ride != null && MtsPump.vehicle(ride) != null && ride.position().distanceToSqr(c) <= (r + 2) * (r + 2)) return ride;
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : ((ServerLevel) p.level()).getEntities((Entity) null, new AABB(pos).inflate(r), MtsBridge::isVehicle)) {
            if (MtsPump.vehicle(e) == null) continue;
            double d = e.position().distanceToSqr(c);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static Quote quote(ServerPlayer p, BlockPos pos) {
        Object pump = MtsPump.pumpAt(p.level(), pos);
        if (pump == null) return Quote.fail("Cette pompe n'existe plus.");
        try {
            Object ptank = MtsPump.tank(pump);
            boolean creative = MtsPump.creative(pump);
            String fluid = MtsPump.fluid(ptank);
            double stock = MtsPump.level(ptank);
            if (fluid.isEmpty() && creative) fluid = VehicleConfig.PUMP_FLUID.get();
            if (fluid.isEmpty() || (!creative && stock <= 0)) return Quote.fail("La pompe est vide.");
            if (p.distanceToSqr(Vec3.atCenterOf(pos)) > 36) return Quote.fail("Tu es trop loin de la pompe.");
            Entity v = findVehicle(p, pos);
            if (v == null) return Quote.fail("Aucun véhicule à moins de " + VehicleConfig.PUMP_RADIUS.get().intValue() + " blocs de la pompe.");
            Object veh = MtsPump.vehicle(v);
            if (!MtsPump.accepts(veh, fluid)) return Quote.fail("Ce véhicule n'accepte pas ce carburant (" + fluid + ").");
            Object vtank = MtsPump.vehicleTank(veh);
            double can = MtsPump.fill(vtank, fluid, MtsPump.WILDCARD, MtsPump.max(vtank), false);
            double mb = creative ? can : Math.min(can, stock);
            if (mb < 1) {
                String inside = MtsPump.fluid(vtank);
                if (!inside.isEmpty() && !inside.equalsIgnoreCase(fluid) && MtsPump.level(vtank) > 0)
                    return Quote.fail("Le réservoir contient déjà un autre carburant (" + inside + ").");
                return Quote.fail("Le réservoir est déjà plein.");
            }
            return new Quote(v, ptank, vtank, fluid, creative, mb, priceFor(mb / mbPerLiter()), null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            VehiclesMod.LOGGER.warn("[Pompe] Calcul du plein impossible", e);
            return Quote.fail("Erreur avec cette pompe (voir la console).");
        }
    }

    // ------------------------------------------------------------------ menu principal

    private static void main(ServerPlayer p, BlockPos pos) {
        Object pump = MtsPump.pumpAt(p.level(), pos);
        if (pump == null) {
            Msg.send(p, "&cCette pompe n'existe plus.");
            return;
        }
        String fluid;
        double stock, max;
        boolean creative;
        try {
            Object t = MtsPump.tank(pump);
            fluid = MtsPump.fluid(t);
            stock = MtsPump.level(t);
            max = MtsPump.max(t);
            creative = MtsPump.creative(pump);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Msg.send(p, "&cErreur avec cette pompe (voir la console).");
            VehiclesMod.LOGGER.warn("[Pompe] Lecture de la pompe impossible", e);
            return;
        }
        double perL = mbPerLiter();
        ItemStack[] items = new ItemStack[27];
        items[4] = Gui.item(Items.BUCKET, "&6&lPompe à carburant",
                "&7Carburant : &f" + (fluid.isEmpty() ? "vide" : fluid),
                creative ? "&7Stock : &aillimité" : "&7Stock : &a" + fmt(stock / perL) + " L &7/ " + fmt(max / perL) + " L",
                "&7Prix : &a" + VehicleConfig.PUMP_PRICE_LITER.get() + "€ / L");
        Quote q = quote(p, pos);
        if (q.error() == null) {
            items[11] = Gui.item(Items.MINECART, "&a&lFaire le plein",
                    "&7Véhicule : &f" + q.vehicle().getDisplayName().getString(),
                    "&7Quantité : &f" + fmt(q.mb() / perL) + " L",
                    "&7Prix : &a" + q.price() + "€", "&8Clique pour choisir le paiement");
        } else {
            items[11] = Gui.item(Items.BARRIER, "&c&lFaire le plein", "&c" + q.error());
        }
        Item fuelItem = VehicleConfig.item(VehicleConfig.PUMP_ITEM.get());
        double perItemL = VehicleConfig.PUMP_LITERS_PER_ITEM.get();
        items[15] = Gui.item(Items.HOPPER, "&e&lRemplir la pompe",
                "&7Verse de l'essence raffinée (" + (fuelItem == Items.AIR ? "item non configuré" : fuelItem.getDescription().getString()) + ")",
                "&7Tu en as : &f" + count(p, fuelItem),
                "&71 item = &f" + fmt(perItemL) + " L &7| payé &a" + VehicleConfig.PUMP_ITEM_PRICE.get() + "€ &7par item",
                "&8Clique pour choisir la quantité");
        items[22] = Gui.item(Items.ARROW, "&c&lFermer");
        Gui.open(p, "&6Pompe à carburant", 3, items, slot -> {
            if (slot == 11 && q.error() == null) Gui.later(p, () -> choosePayment(p, pos));
            else if (slot == 11) Msg.send(p, "&c" + q.error());
            else if (slot == 15) Gui.later(p, () -> fillMenu(p, pos));
            else if (slot == 22) Gui.later(p, p::closeContainer);
        });
    }

    // ------------------------------------------------------------------ faire le plein : paiement

    private static void choosePayment(ServerPlayer p, BlockPos pos) {
        Quote q = quote(p, pos);
        if (q.error() != null) {
            Msg.send(p, "&c" + q.error());
            return;
        }
        List<VehicleConfig.Denom> den = VehicleConfig.currency();
        int cash = Money.count(p, den);
        PayResult card = Payment.cardStatus(p, q.price());
        ItemStack[] items = new ItemStack[9];
        items[4] = Gui.item(Items.MINECART, "&e&lPlein : " + fmt(q.mb() / mbPerLiter()) + " L", "&7Prix : &a" + q.price() + "€");
        Item cashIcon = VehicleConfig.item("minenorth_eurobank:bill_50e");
        items[2] = Gui.item(cashIcon == Items.AIR ? Items.GOLD_INGOT : cashIcon, "&a&lPayer en espèces",
                "&7Espèces sur toi : &f" + cash + "€", cash >= q.price() ? "&aClique pour payer" : "&cEspèces insuffisantes");
        Item cardIcon = VehicleConfig.item("minenorth_eurobank:bank_card");
        items[6] = Gui.item(cardIcon == Items.AIR ? Items.PAPER : cardIcon, "&b&lPayer par carte",
                "&7Solde du compte : &f" + EconomyBridge.balance(p) + "€",
                card == PayResult.OK ? "&aClique pour payer" : "&c" + (card == PayResult.INSUFFICIENT_FUNDS ? "Solde insuffisant" : card.message()));
        items[8] = Gui.item(Items.ARROW, "&c« Retour");
        Gui.open(p, "&0Paiement du plein", 1, items, slot -> {
            if (slot == 2) Gui.later(p, () -> buyFuel(p, pos, Payment.Method.CASH));
            else if (slot == 6) Gui.later(p, () -> buyFuel(p, pos, Payment.Method.CARD));
            else if (slot == 8) Gui.later(p, () -> main(p, pos));
        });
    }

    private static void buyFuel(ServerPlayer p, BlockPos pos, Payment.Method method) {
        p.closeContainer();
        Quote q = quote(p, pos);     // recalculé : la pompe ou le véhicule a pu changer depuis le menu
        if (q.error() != null) {
            Msg.send(p, "&c" + q.error());
            return;
        }
        List<VehicleConfig.Denom> den = VehicleConfig.currency();
        String err = Payment.precheck(p, q.price(), method, den, "pour faire le plein");
        if (err != null) {
            Msg.send(p, "&c" + err);
            return;
        }
        Payment.Result paid = Payment.pay(p, q.price(), method, den, "pompe:carburant");
        if (!paid.ok()) {
            Msg.send(p, "&c" + paid.message());
            return;
        }
        try {
            double got = MtsPump.transfer(q.pumpTank(), q.vehicleTank(), q.fluid(), q.mb(), q.creative());
            if (got <= 0) {
                Payment.refund(p, q.price(), method, den, "pompe:carburant");
                Msg.send(p, "&cLe véhicule n'a rien pris : paiement annulé.");
                return;
            }
            // la quantité réellement versée peut être un peu plus faible que prévu : on ne facture que le versé
            int due = priceFor(got / mbPerLiter());
            if (due < q.price()) Payment.refund(p, q.price() - due, method, den, "pompe:carburant");
            Msg.send(p, "&aPlein effectué : &f" + fmt(got / mbPerLiter()) + " L &apour &f" + Math.min(due, q.price()) + "€ &a("
                    + (method == Payment.Method.CARD ? "carte" : "espèces") + ").");
        } catch (ReflectiveOperationException | RuntimeException e) {
            VehiclesMod.LOGGER.warn("[Pompe] Transfert impossible", e);
            Payment.refund(p, q.price(), method, den, "pompe:carburant");
            Msg.send(p, "&cErreur avec la pompe : paiement annulé.");
        }
    }

    // ------------------------------------------------------------------ remplir la pompe

    private static int count(ServerPlayer p, Item item) {
        if (item == Items.AIR) return 0;
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(item)) n += s.getCount();
        for (ItemStack s : p.getInventory().offhand) if (s.is(item)) n += s.getCount();
        return n;
    }

    private static void take(ServerPlayer p, Item item, int n) {
        for (ItemStack s : p.getInventory().items) {
            if (n <= 0) break;
            if (!s.is(item)) continue;
            int t = Math.min(n, s.getCount());
            s.shrink(t);
            n -= t;
        }
        for (ItemStack s : p.getInventory().offhand) {
            if (n <= 0) break;
            if (!s.is(item)) continue;
            int t = Math.min(n, s.getCount());
            s.shrink(t);
            n -= t;
        }
        p.getInventory().setChanged();
    }

    private static void fillMenu(ServerPlayer p, BlockPos pos) {
        Item item = VehicleConfig.item(VehicleConfig.PUMP_ITEM.get());
        ItemStack[] items = new ItemStack[9];
        int have = count(p, item);
        double perItemL = VehicleConfig.PUMP_LITERS_PER_ITEM.get();
        double price = VehicleConfig.PUMP_ITEM_PRICE.get();
        items[2] = Gui.item(Items.HOPPER, "&e&lVerser 1", "&7= " + fmt(perItemL) + " L", "&7Paiement : &a" + price + "€");
        items[4] = Gui.item(Items.HOPPER, "&e&lVerser 10", "&7= " + fmt(perItemL * 10) + " L", "&7Paiement : &a" + price * 10 + "€");
        items[6] = Gui.item(Items.HOPPER, "&e&lTout verser (" + have + ")", "&7= " + fmt(perItemL * have) + " L (dans la limite de la pompe)",
                "&7Paiement : &a" + price * have + "€");
        items[8] = Gui.item(Items.ARROW, "&c« Retour");
        Gui.open(p, "&0Remplir la pompe", 1, items, slot -> {
            if (slot == 2) Gui.later(p, () -> deposit(p, pos, 1));
            else if (slot == 4) Gui.later(p, () -> deposit(p, pos, 10));
            else if (slot == 6) Gui.later(p, () -> deposit(p, pos, Integer.MAX_VALUE));
            else if (slot == 8) Gui.later(p, () -> main(p, pos));
        });
    }

    private static void deposit(ServerPlayer p, BlockPos pos, int requested) {
        p.closeContainer();
        Object pump = MtsPump.pumpAt(p.level(), pos);
        if (pump == null) {
            Msg.send(p, "&cCette pompe n'existe plus.");
            return;
        }
        Item item = VehicleConfig.item(VehicleConfig.PUMP_ITEM.get());
        if (item == Items.AIR) {
            Msg.send(p, "&cItem de carburant mal configuré (pompe.fillItem).");
            return;
        }
        try {
            if (MtsPump.creative(pump)) {
                Msg.send(p, "&cCette pompe est illimitée : inutile de la remplir.");
                return;
            }
            Object tank = MtsPump.tank(pump);
            String want = VehicleConfig.PUMP_FLUID.get();
            String cur = MtsPump.fluid(tank);
            double level = MtsPump.level(tank);
            if (!cur.isEmpty() && level > 0 && !cur.equalsIgnoreCase(want)) {
                Msg.send(p, "&cCette pompe contient déjà du " + cur + " : impossible d'y verser de l'" + want + ".");
                return;
            }
            String mod = level > 0 ? MtsPump.mod(tank) : "";
            double mbItem = VehicleConfig.PUMP_LITERS_PER_ITEM.get() * mbPerLiter();
            int have = count(p, item);
            if (have <= 0) {
                Msg.send(p, "&cTu n'as pas d'" + item.getDescription().getString() + ".");
                return;
            }
            int n = Math.min(requested, have);
            double sim = MtsPump.fill(tank, want, mod, n * mbItem, false);        // simulation : place réellement disponible
            n = Math.min(n, (int) Math.floor(sim / mbItem + 1e-9));
            if (n <= 0) {
                Msg.send(p, "&cLa pompe est pleine.");
                return;
            }
            long cents = Math.round(VehicleConfig.PUMP_ITEM_PRICE.get() * 100.0) * n;
            var bank = MineNorth.bank();
            if (cents > 0 && !bank.hasAccount(p.server, p.getUUID())) {
                Msg.send(p, "&cIl te faut un compte bancaire pour être payé.");
                return;
            }
            take(p, item, n);
            if (cents > 0 && !bank.refund(p.server, p.getUUID(), cents, "pompe:achat-essence")) {
                ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(item, n));
                Msg.send(p, "&cLe trésor ne peut pas te payer pour l'instant : rien n'a été versé.");
                return;
            }
            MtsPump.fill(tank, want, mod, n * mbItem, true);
            Msg.send(p, "&aTu as versé &f" + n + " &a(" + fmt(n * VehicleConfig.PUMP_LITERS_PER_ITEM.get()) + " L)"
                    + (cents > 0 ? " et reçu &f" + (cents / 100.0) + "€&a." : "."));
        } catch (ReflectiveOperationException | RuntimeException e) {
            VehiclesMod.LOGGER.warn("[Pompe] Remplissage impossible", e);
            Msg.send(p, "&cErreur avec la pompe (voir la console).");
        }
    }
}
