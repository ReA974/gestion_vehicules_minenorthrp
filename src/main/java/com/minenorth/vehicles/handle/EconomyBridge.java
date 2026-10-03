package com.minenorth.vehicles.handle;

import com.minenorth_eurobank.api.BankApi;
import com.minenorth_eurobank.api.PayResult;
import net.minecraft.server.level.ServerPlayer;

/**
 * Passerelle vers le mod d'économie MineNorth (paiement par carte).
 * Les prix du garage sont en unités entières ; CENTS_PER_UNIT = 100 si 1 unité = 1 €.
 */
public final class EconomyBridge {
    private static final long CENTS_PER_UNIT = 100L;

    private EconomyBridge() {}

    /** Vérifie qu'un paiement par carte serait possible (compte, SA carte, solde) sans rien débiter. */
    public static PayResult check(ServerPlayer p, int price) {
        return BankApi.check(p, price * CENTS_PER_UNIT);
    }

    /** Débite le compte du joueur. */
    public static PayResult charge(ServerPlayer p, int price) {
        return BankApi.charge(p, price * CENTS_PER_UNIT);
    }

    /** Recrédite le compte (livraison échouée après le paiement). */
    public static void refund(ServerPlayer p, int price) {
        BankApi.refund(p, price * CENTS_PER_UNIT);
    }

    /** Solde affichable, en unités du garage. */
    public static long balance(ServerPlayer p) {
        return BankApi.balance(p) / CENTS_PER_UNIT;
    }
}
