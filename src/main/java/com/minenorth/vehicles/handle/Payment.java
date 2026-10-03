package com.minenorth.vehicles.handle;

import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth_eurobank.api.PayResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Point d'entrée unique pour payer un achat du garage : espèces (billets configurés) ou carte bancaire.
 * Utilisé par le vendeur de véhicules et par la fourrière.
 */
public final class Payment {
    public enum Method { CASH, CARD }

    public record Result(boolean ok, String message) {}

    private Payment() {}

    /** Le joueur peut-il payer par carte ? */
    public static PayResult cardStatus(ServerPlayer p, int price) {
        return EconomyBridge.check(p, price);
    }

    /**
     * Vérifie qu'on peut payer, sans rien débiter.
     * @param purpose complément de phrase, ex. "pour acheter ce véhicule"
     * @return le message d'erreur à afficher (sans couleur), ou null si le paiement est possible
     */
    public static String precheck(ServerPlayer p, int price, Method method, List<VehicleConfig.Denom> den, String purpose) {
        if (price <= 0) return null;
        if (method == Method.CARD) {
            PayResult st = EconomyBridge.check(p, price);
            if (st == PayResult.OK) return null;
            if (st == PayResult.INSUFFICIENT_FUNDS) {
                long bal = EconomyBridge.balance(p);
                return "Solde insuffisant : il te manque " + (price - bal) + "€ " + purpose + " (" + price + "€, solde " + bal + "€).";
            }
            return st.message();
        }
        int total = Money.count(p, den);
        if (total < price) {
            return "Il te manque " + (price - total) + "€ " + purpose + " (" + price + "€, tu as " + total + "€).";
        }
        return null;
    }

    /** Débite le joueur selon le mode choisi. Si ok == false, rien n'a été retiré. */
    public static Result pay(ServerPlayer p, int price, Method method, List<VehicleConfig.Denom> den) {
        if (price <= 0) return new Result(true, "");
        if (method == Method.CARD) {
            PayResult r = EconomyBridge.charge(p, price);
            return new Result(r == PayResult.OK, r.message());
        }
        if (Money.count(p, den) < price) return new Result(false, "Pas assez d'espèces.");
        Money.take(p, price, den);
        return new Result(true, "");
    }

    /** Annule un paiement. Espèces : rendues en coupures, grosses d'abord. */
    public static void refund(ServerPlayer p, int price, Method method, List<VehicleConfig.Denom> den) {
        if (price <= 0) return;
        if (method == Method.CARD) {
            EconomyBridge.refund(p, price);
            return;
        }
        int remaining = price;
        for (VehicleConfig.Denom d : den) {            // décroissant
            int k = remaining / d.value();
            while (k > 0) {
                int n = Math.min(k, 64);
                ItemStack stack = new ItemStack(d.item(), n);
                p.getInventory().add(stack);
                if (!stack.isEmpty()) p.drop(stack, false);
                k -= n;
                remaining -= n * d.value();
            }
        }
    }
}
