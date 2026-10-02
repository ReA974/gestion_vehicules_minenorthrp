package com.minenorth.vehicles.fourriere;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.handle.MtsBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class ImpoundEvents {
    private ImpoundEvents() {}

    /** Sneak + clic droit sur un véhicule avec l'item de police (bâton de blaze) : menu de mise en fourrière. */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel().isClientSide() || e.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.isShiftKeyDown()) return;
        if (!MtsBridge.isVehicle(e.getTarget())) return;
        Item it = VehicleConfig.item(ImpoundConfig.POLICE_ITEM.get());
        if (it == Items.AIR || p.getMainHandItem().getItem() != it) return;
        if (!p.getTags().contains(ImpoundConfig.POLICE_TAG.get()) && !p.hasPermissions(VehicleConfig.COMMAND_LEVEL.get())) return;

        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        Impound.openPoliceMenu(p, e.getTarget());
    }
}
