package com.minenorth.vehicles.garage;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.miscs.Gui;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * MTS efface le nom de la clé quand on la lie à un véhicule (clic sur le véhicule) : elle redevient « Key » et le
 * rangement au garage ne la retrouve plus. Ici on lui rend son nom « Clé - <véhicule> » quelques ticks après le clic.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class KeyNaming {
    private KeyNaming() {}

    private record Task(UUID player, InteractionHand hand, String label, long due) {}

    private static final List<Task> TASKS = new ArrayList<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel level)) return;
        Item key = VehicleConfig.item(VehicleConfig.KEY_ITEM.get());
        if (key == Items.AIR || e.getItemStack().getItem() != key) return;

        Entity vehicle = e.getTarget();
        if (!MtsBridge.isVehicle(vehicle)) {
            // on a cliqué une pièce (siège...) : le véhicule est le plus proche
            vehicle = MtsBridge.nearest(level, e.getTarget().position(), VehicleConfig.SEARCH_RADIUS.get(), null);
        }
        if (vehicle == null) return;
        CompoundTag snap = MtsBridge.snapshot(vehicle);
        String label = Garage.labelOf(MtsBridge.itemId(snap), snap);
        TASKS.add(new Task(p.getUUID(), e.getHand(), label, p.getServer().getTickCount() + 3));
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || TASKS.isEmpty()) return;
        MinecraftServer server = ev.getServer();
        long now = server.getTickCount();
        Item key = VehicleConfig.item(VehicleConfig.KEY_ITEM.get());
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (t.due() > now) continue;
            it.remove();
            ServerPlayer p = server.getPlayerList().getPlayer(t.player());
            if (p == null || key == Items.AIR) continue;
            ItemStack s = p.getItemInHand(t.hand());
            if (s.getItem() == key) s.setHoverName(Gui.comp("&e&l" + VehicleConfig.KEY_NAME_PREFIX.get() + " " + t.label()));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        TASKS.removeIf(t -> t.player().equals(id));
    }
}
