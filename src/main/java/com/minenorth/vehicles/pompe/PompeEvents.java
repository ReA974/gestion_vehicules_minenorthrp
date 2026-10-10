package com.minenorth.vehicles.pompe;

import com.minenorth.vehicles.VehiclesMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Clic droit sur une pompe MTS (ou d'un pack comme gvp, tant que c'est un décor « fuel_pump ») : l'action de MTS est annulée
 * (des deux côtés : le client ne doit pas ouvrir l'écran natif) et le serveur ouvre le menu MineNorth.
 * Exception : un OP accroupi garde la pompe native de MTS (réglages, bidons…).
 * Aucune config n'est lue ici : le client n'en a pas.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class PompeEvents {
    private PompeEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUse(PlayerInteractEvent.RightClickBlock e) {
        Player pl = e.getEntity();
        if (pl.isShiftKeyDown() && pl.hasPermissions(2)) return;
        if (MtsPump.pumpAt(e.getLevel(), e.getPos()) == null) return;
        e.setUseBlock(Event.Result.DENY);
        e.setUseItem(Event.Result.DENY);
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getLevel().isClientSide() || e.getHand() != InteractionHand.MAIN_HAND) return;
        if (pl instanceof ServerPlayer sp) PompeMenu.open(sp, e.getPos());
    }
}
