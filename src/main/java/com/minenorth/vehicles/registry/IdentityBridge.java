package com.minenorth.vehicles.registry;

import fr.minenorth.api.Identity;
import fr.minenorth.api.MineNorth;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/** Identité RP du propriétaire, lue via MineNorth API (plus de réflexion). */
public final class IdentityBridge {
    private IdentityBridge() {}

    /** {prénom, nom, date de naissance, lieu, nationalité, n° de carte}, ou null si pas de carte d'identité. */
    public static String[] identity(MinecraftServer s, UUID id) {
        if (s == null || id == null) return null;
        Optional<Identity> idt = MineNorth.identity().get(s, id);
        if (idt.isEmpty()) return null;
        Identity i = idt.get();
        return new String[]{i.firstName(), i.lastName(), i.birthDate(), i.birthPlace(), i.nationality(), i.cardNumber()};
    }
}
