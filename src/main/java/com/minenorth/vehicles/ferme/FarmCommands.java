package com.minenorth.vehicles.ferme;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.miscs.Gui;
import com.minenorth.vehicles.miscs.Msg;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class FarmCommands {
    private static final Map<UUID, BlockPos[]> SELECTION = new HashMap<>();

    private FarmCommands() {}

    private static boolean admin(CommandSourceStack s) {
        return s.hasPermission(VehicleConfig.COMMAND_LEVEL.get());
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("farm").requires(FarmCommands::admin)
                .then(Commands.literal("reload").executes(c -> {
                    int n = FarmConfig.load();
                    c.getSource().sendSuccess(() -> Gui.comp(VehicleConfig.PREFIX.get()
                            + (n < 0 ? " &cErreur dans farm.json (voir la console)." : " &aFerme rechargée : " + n + " profil(s).")), false);
                    return Math.max(n, 0);
                }))
                .then(Commands.literal("info").executes(c -> info(c.getSource().getPlayerOrException()))));

        e.getDispatcher().register(Commands.literal("farmzone").requires(FarmCommands::admin)
                .then(Commands.literal("pos1").executes(c -> pos(c, 0)))
                .then(Commands.literal("pos2").executes(c -> pos(c, 1)))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                        .executes(FarmCommands::create)))
                .then(Commands.literal("list").executes(c -> list(c.getSource().getPlayerOrException())))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word())
                        .executes(FarmCommands::remove))));
    }

    private static int pos(CommandContext<CommandSourceStack> c, int idx) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        BlockPos[] sel = SELECTION.computeIfAbsent(p.getUUID(), k -> new BlockPos[2]);
        sel[idx] = p.blockPosition();
        Msg.send(p, "&aPoint " + (idx + 1) + " défini : " + sel[idx].getX() + ", " + sel[idx].getZ());
        return 1;
    }

    private static int create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        BlockPos[] sel = SELECTION.get(p.getUUID());
        if (sel == null || sel[0] == null || sel[1] == null) {
            Msg.send(p, "&cDéfinis d'abord /farmzone pos1 et /farmzone pos2.");
            return 0;
        }
        String name = StringArgumentType.getString(c, "name");
        FarmData d = FarmData.get(p.getServer());
        FarmData.Zone z = new FarmData.Zone();
        z.name = name;
        z.dim = p.level().dimension().location().toString();
        z.minX = Math.min(sel[0].getX(), sel[1].getX());
        z.maxX = Math.max(sel[0].getX(), sel[1].getX());
        z.minZ = Math.min(sel[0].getZ(), sel[1].getZ());
        z.maxZ = Math.max(sel[0].getZ(), sel[1].getZ());
        d.zones.put(name, z);
        d.setDirty();
        Msg.send(p, "&aZone de ferme \"" + name + "\" créée (" + (z.maxX - z.minX + 1) + " x " + (z.maxZ - z.minZ + 1) + " blocs, toute la hauteur).");
        return 1;
    }

    private static int list(ServerPlayer p) {
        FarmData d = FarmData.get(p.getServer());
        if (d.zones.isEmpty()) {
            Msg.send(p, "&eAucune zone de ferme. Utilise /farmzone pos1, pos2 puis create <nom>.");
            return 0;
        }
        Msg.send(p, "&eZones de ferme (" + d.zones.size() + ") :");
        for (FarmData.Zone z : d.zones.values()) {
            Msg.send(p, "&7- &f" + z.name + " &8| " + z.dim + " | X " + z.minX + ".." + z.maxX + " | Z " + z.minZ + ".." + z.maxZ);
        }
        return d.zones.size();
    }

    private static int remove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        FarmData d = FarmData.get(p.getServer());
        String name = StringArgumentType.getString(c, "name");
        if (d.zones.remove(name) == null) {
            Msg.send(p, "&cZone inconnue : " + name);
            return 0;
        }
        d.setDirty();
        Msg.send(p, "&aZone \"" + name + "\" supprimée.");
        return 1;
    }

    /** Diagnostic : pourquoi (ou pourquoi pas) la récolte se déclenche. */
    private static int info(ServerPlayer p) {
        FarmData d = FarmData.get(p.getServer());
        String dim = p.level().dimension().location().toString();
        Msg.send(p, "&eFerme activée : &f" + FarmConfig.enabled + " &7| zone requise : &f" + FarmConfig.requireZone);
        Msg.send(p, "&eDans une zone de ferme : &f" + d.inZone(dim, p.getX(), p.getZ()));
        FarmEvents.Match m = FarmEvents.findProfile(p, (ServerLevel) p.level(), p.getServer().getTickCount());
        if (m == null) {
            Msg.send(p, "&eVéhicule agricole détecté : &cnon &7(assis dans un véhicule ? mots-clés dans farm.json ?)");
        } else {
            Msg.send(p, "&eProfil : &a" + m.profile().name + " &7| rayon " + m.profile().radius
                    + " | rendement " + m.profile().yieldMin + "-" + m.profile().yieldMax + " | récolte : " + m.profile().harvest);
            Msg.send(p, "&7Détecté sur : " + MtsBridge.typeId(m.matched()));
        }
        int tr = d.tracked.getOrDefault(dim, java.util.Set.of()).size();
        Msg.send(p, "&eCultures suivies dans cette dimension : &f" + tr);
        return 1;
    }
}
