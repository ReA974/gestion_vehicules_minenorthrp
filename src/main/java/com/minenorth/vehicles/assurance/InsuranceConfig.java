package com.minenorth.vehicles.assurance;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/minenorth-assurance.toml */
public final class InsuranceConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue MIN_PRICE, UNKNOWN_PRICE;
    public static final ForgeConfigSpec.DoubleValue PERCENT;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("assurance");
        PERCENT = b.comment("Prix de l'assurance : pourcentage du prix du véhicule au catalogue.",
                        "Valable jusqu'à la destruction : le véhicule revient alors au garage, dans l'état où il en est sorti, et n'est plus assuré.")
                .defineInRange("pourcentagePrixVehicule", 10.0, 0.0, 100.0);
        MIN_PRICE = b.comment("Prix minimum d'une assurance (€)").defineInRange("prixMinimum", 100, 0, 100_000_000);
        UNKNOWN_PRICE = b.comment("Prix de l'assurance (€) d'un véhicule absent du catalogue").defineInRange("prixVehiculeInconnu", 500, 0, 100_000_000);
        b.pop();
        SPEC = b.build();
    }

    private InsuranceConfig() {}
}
