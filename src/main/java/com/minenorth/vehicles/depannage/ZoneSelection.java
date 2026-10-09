package com.minenorth.vehicles.depannage;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.miscs.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Sélection faite avec le bâton : centre (clic droit) + bord (clic gauche) → /garagezone select <nom> [tag]. */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class ZoneSelection {
    public static final class Sel {
        public String dim = "";
        public BlockPos center, edge;
    }

    private static final Map<UUID, Sel> SELECTIONS = new HashMap<>();

    private ZoneSelection() {}

    public static Sel of(UUID id) { return SELECTIONS.get(id); }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (e.getItemStack().getItem() != ModItems.ZONE_WAND.get()) return;
        e.setCanceled(true);
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!allowed(p)) return;
        Sel s = SELECTIONS.computeIfAbsent(p.getUUID(), k -> new Sel());
        String dim = p.level().dimension().location().toString();
        if (!dim.equals(s.dim)) s.edge = null;
        s.dim = dim;
        s.center = e.getPos();
        Msg.send(p, "&aCentre : &f" + s.center.toShortString() + "&a." + status(s));
    }

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        if (e.getItemStack().getItem() != ModItems.ZONE_WAND.get()) return;
        e.setCanceled(true);
        if (e.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!allowed(p)) return;
        Sel s = SELECTIONS.computeIfAbsent(p.getUUID(), k -> new Sel());
        String dim = p.level().dimension().location().toString();
        if (!dim.equals(s.dim)) s.center = null;
        s.dim = dim;
        s.edge = e.getPos();
        Msg.send(p, "&aBord : &f" + s.edge.toShortString() + "&a." + status(s));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        SELECTIONS.remove(e.getEntity().getUUID());
    }

    private static boolean allowed(ServerPlayer p) {
        if (p.hasPermissions(VehicleConfig.COMMAND_LEVEL.get())) return true;
        Msg.send(p, "&cCet objet est réservé à l'administration.");
        return false;
    }

    /** Rayon horizontal entre le centre et le bord (au moins 0,5). */
    public static double radius(Sel s) {
        double dx = s.edge.getX() - s.center.getX(), dz = s.edge.getZ() - s.center.getZ();
        return Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
    }

    private static String status(Sel s) {
        if (s.center == null) return " Clic droit sur un bloc pour le centre.";
        if (s.edge == null) return " Clic gauche sur un bloc pour définir le rayon.";
        return String.format(java.util.Locale.ROOT, " Rayon : &f%.1f&a blocs. Valide avec &f/garagezone select <nom> [tag]&a.", radius(s));
    }
}
