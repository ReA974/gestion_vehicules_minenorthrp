package com.minenorth.vehicles.handle;

import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth_eurobank.api.PayResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Point d'entrée unique pour payer un achat du garage : espèces (billets configurés) ou carte bancaire.
 * Utilisable pour le vendeur de véhicules comme pour la fourrière.
 */
public final class Payment {
    public enum Method { CASH, CARD }

    public record Result(boolean ok, String message) {}

    private Payment() {}

    /** Le joueur peut-il payer par carte ? (pour griser le bouton « Carte ») */
    public static PayResult cardStatus(ServerPlayer p, int price) {
        return EconomyBridge.check(p, price);
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

    /** Annule un paiement (ex. le véhicule n'a pas pu être livré). Espèces : rendues en coupures, grosses d'abord. */
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
