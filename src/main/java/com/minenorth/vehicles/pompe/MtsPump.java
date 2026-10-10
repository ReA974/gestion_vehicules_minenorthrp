package com.minenorth.vehicles.pompe;

import com.minenorth.vehicles.VehiclesMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Pompes à carburant d'Immersive Vehicles (MTS), par réflexion : pas de dépendance de compilation à MTS.
 * On utilise le système natif : la pompe est une TileEntityFuelPump (décor « fuel_pump ») dont le réservoir est un EntityFluidTank,
 * exactement le même type que le réservoir des véhicules. Le plein reprend la logique de TileEntityFuelPump.fuelVehicle :
 * le réservoir du véhicule est rempli avec le fluide de la pompe (mod « wildcard »), puis celui de la pompe est vidé d'autant.
 * Classes lues dans Immersive Vehicles 24.0.0 (1.20.1).
 */
final class MtsPump {
    private MtsPump() {}

    static final String WILDCARD = "wildcard";

    private static boolean init, ok;
    private static Field teField, creativeField, builderEntity, fuelTank, bufferField, purchasedField, dispensedField;
    private static Class<?> chargerClass;
    private static double chargerMax = 1000;
    private static Class<?> pumpClass;
    private static Method getTank, tankFluid, tankMod, tankLevel, tankMax, tankFill, tankDrain, check;

    private static boolean init() {
        if (init) return ok;
        init = true;
        try {
            teField = Class.forName("mcinterface1201.BuilderTileEntity").getDeclaredField("tileEntity");
            teField.setAccessible(true);
            pumpClass = Class.forName("minecrafttransportsimulator.blocks.tileentities.instances.TileEntityFuelPump");
            creativeField = Class.forName("minecrafttransportsimulator.blocks.tileentities.instances.ATileEntityFuelPump").getField("isCreative");
            getTank = pumpClass.getMethod("getTank");
            Class<?> tank = Class.forName("minecrafttransportsimulator.entities.instances.EntityFluidTank");
            tankFluid = tank.getMethod("getFluid");
            tankMod = tank.getMethod("getFluidMod");
            tankLevel = tank.getMethod("getFluidLevel");
            tankMax = tank.getMethod("getMaxLevel");
            tankFill = tank.getMethod("fill", String.class, String.class, double.class, boolean.class);
            tankDrain = tank.getMethod("drain", double.class, boolean.class);
            chargerClass = Class.forName("minecrafttransportsimulator.blocks.tileentities.instances.TileEntityCharger");
            bufferField = chargerClass.getField("internalBuffer");
            Class<?> abstractPump = Class.forName("minecrafttransportsimulator.blocks.tileentities.instances.ATileEntityFuelPump");
            purchasedField = abstractPump.getField("fuelPurchased");
            dispensedField = abstractPump.getField("fuelDispensedThisPurchase");
            try {
                Field max = chargerClass.getDeclaredField("MAX_BUFFER");
                max.setAccessible(true);
                chargerMax = ((Number) max.get(null)).doubleValue();
            } catch (ReflectiveOperationException ignored) { /* 1000 par défaut, valeur de MTS 24.0.0 */ }
            builderEntity = Class.forName("mcinterface1201.BuilderEntityExisting").getDeclaredField("entity");
            builderEntity.setAccessible(true);
            Class<?> powered = Class.forName("minecrafttransportsimulator.entities.instances.AEntityVehicleE_Powered");
            fuelTank = powered.getField("fuelTank");
            check = powered.getMethod("checkFuelTankCompatibility", String.class);
            ok = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            VehiclesMod.LOGGER.warn("[Pompe] API MTS introuvable : les pompes restent celles de MTS.", e);
        }
        return ok;
    }

    /** Pompe MTS (TileEntityFuelPump) posée à cet endroit, ou null. Utilisable côté client et serveur. */
    static Object pumpAt(Level level, BlockPos pos) {
        if (!init()) return null;
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || !teField.getDeclaringClass().isInstance(be)) return null;
            Object te = teField.get(be);
            return te != null && pumpClass.isInstance(te) ? te : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Si ce bloc est une borne de recharge MTS (TileEntityCharger) : stock d'énergie plein et plus de plafond d'achat, pour qu'elle
     * distribue sans arrêt. Renvoie vrai si c'était une borne.
     */
    static boolean keepChargerFull(BlockEntity be) {
        if (!init()) return false;
        try {
            if (!teField.getDeclaringClass().isInstance(be)) return false;
            Object te = teField.get(be);
            if (te == null || !chargerClass.isInstance(te)) return false;
            if (bufferField.getDouble(te) < chargerMax) bufferField.setDouble(te, chargerMax);
            if (purchasedField.getInt(te) < 1_000_000_000) {
                purchasedField.setInt(te, 2_000_000_000);
                dispensedField.setDouble(te, 0);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ pompe

    static Object tank(Object pump) throws ReflectiveOperationException { return getTank.invoke(pump); }

    static boolean creative(Object pump) {
        try {
            return creativeField.getBoolean(pump);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ réservoirs (pompe ou véhicule : même classe)

    static String fluid(Object tank) throws ReflectiveOperationException {
        Object s = tankFluid.invoke(tank);
        return s == null ? "" : s.toString();
    }

    static String mod(Object tank) throws ReflectiveOperationException {
        Object s = tankMod.invoke(tank);
        return s == null ? "" : s.toString();
    }

    static double level(Object tank) throws ReflectiveOperationException { return ((Number) tankLevel.invoke(tank)).doubleValue(); }

    static double max(Object tank) throws ReflectiveOperationException { return ((Number) tankMax.invoke(tank)).doubleValue(); }

    /** Renvoie la quantité réellement versée ; doIt = false : simulation. */
    static double fill(Object tank, String fluid, String mod, double mb, boolean doIt) throws ReflectiveOperationException {
        return ((Number) tankFill.invoke(tank, fluid, mod, mb, doIt)).doubleValue();
    }

    /** Vide n'importe quel fluide du réservoir (comme le fait la pompe native après avoir rempli le véhicule). */
    static double drain(Object tank, double mb, boolean doIt) throws ReflectiveOperationException {
        return ((Number) tankDrain.invoke(tank, mb, doIt)).doubleValue();
    }

    // ------------------------------------------------------------------ véhicule

    /** Objet véhicule MTS motorisé porté par cette entité, ou null (remorque, avion sans moteur, autre entité…). */
    static Object vehicle(Entity e) {
        if (e == null || !init()) return null;
        try {
            if (!builderEntity.getDeclaringClass().isInstance(e)) return null;
            Object v = builderEntity.get(e);
            return v != null && fuelTank.getDeclaringClass().isInstance(v) ? v : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    static Object vehicleTank(Object vehicle) throws ReflectiveOperationException { return fuelTank.get(vehicle); }

    /** Les moteurs du véhicule acceptent-ils ce fluide ? (même test que la pompe native : checkFuelTankCompatibility) */
    static boolean accepts(Object vehicle, String fluid) throws ReflectiveOperationException {
        return "VALID".equals(String.valueOf(check.invoke(vehicle, fluid)));
    }

    /** Réservoir du véhicule rempli avec le fluide de la pompe, puis réservoir de la pompe vidé d'autant. Renvoie les mB transférés. */
    static double transfer(Object pumpTank, Object vehicleTank, String fluid, double mb, boolean creative) throws ReflectiveOperationException {
        double filled = fill(vehicleTank, fluid, WILDCARD, mb, true);
        if (filled > 0 && !creative) drain(pumpTank, filled, true);
        return filled;
    }
}
