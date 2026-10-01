package com.minenorth.vehicles;

import com.minenorth.vehicles.config.VehicleConfig;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(VehiclesMod.MODID)
public class VehiclesMod {
    public static final String MODID = "minenorth_rp_vehicles";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VehiclesMod() {
        // config/minenorth-vehicles.toml  (+ config/minenorth_vehicles/catalog.json)
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, VehicleConfig.SPEC, "minenorth-vehicles.toml");
    }
}
