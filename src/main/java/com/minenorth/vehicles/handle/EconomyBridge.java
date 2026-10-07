package com.minenorth.vehicles.handle;

import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import net.minecraft.server.level.ServerPlayer;

/**
 * Paiement par carte via MineNorth API (la banque verse au trésor avec la source).
 * Les prix du garage sont en unités entières ; CENTS_PER_UNIT = 100 si 1 unité = 1 €.
 */
public final class EconomyBridge {
    public static final long CENTS_PER_UNIT = 100L;

    private EconomyBridge() {}

    /** Vérifie qu'un paiement par carte serait possible (compte, SA carte, solde) sans rien débiter. */
    public static PayResult check(ServerPlayer p, int price) {
        return MineNorth.bank().check(p, price * CENTS_PER_UNIT);
    }

    /** Débite le compte du joueur ; la somme part au trésor sous cette source. */
    public static PayResult charge(ServerPlayer p, int price, String source) {
        return MineNorth.bank().charge(p, price * CENTS_PER_UNIT, source);
    }

    /** Recrédite le compte (livraison échouée après le paiement) et reprend la somme au trésor. */
    public static void refund(ServerPlayer p, int price, String source) {
        MineNorth.bank().refund(p.server, p.getUUID(), price * CENTS_PER_UNIT, source);
    }

    /** Solde affichable, en unités du garage. */
    public static long balance(ServerPlayer p) {
        return MineNorth.bank().balance(p.server, p.getUUID()) / CENTS_PER_UNIT;
    }
}
