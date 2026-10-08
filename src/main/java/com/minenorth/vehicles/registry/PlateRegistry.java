package com.minenorth.vehicles.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Fichier des immatriculations (SIV RP) : une ligne par véhicule vendu avec plaque.
 * Rempli à l'achat chez le vendeur, avec l'identité RP du propriétaire (mod Identité) au moment de l'achat.
 * Lu par le mod Police (onglet IMMAT. de la tablette) par réflexion : garder les champs publics et les noms stables.
 * Stockage : data/minenorth_immatriculations.dat ; copie lisible : minenorth_immatriculations.json à la racine du monde.
 */
public class PlateRegistry extends SavedData {
    private static final String NAME = "minenorth_immatriculations";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final class Entry {
        public String plate = "", model = "", color = "", itemId = "", shopId = "", method = "";
        public UUID owner;
        /** Pseudo Minecraft au moment de l'achat. */
        public String ownerName = "";
        /** Identité RP au moment de l'achat (vide si le joueur n'avait pas de carte d'identité). */
        public String lastName = "", firstName = "", birthDate = "", birthPlace = "", nationality = "", cardNumber = "";
        public int price;
        public long time;

        /** « Prénom NOM », sinon le pseudo. */
        public String displayName() {
            if (firstName.isBlank() && lastName.isBlank()) return ownerName;
            return (firstName + " " + lastName.toUpperCase(Locale.ROOT)).trim();
        }
    }

    public final List<Entry> entries = new ArrayList<>();

    public static PlateRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PlateRegistry::load, PlateRegistry::new, NAME);
    }

    public static String norm(String plate) {
        return plate == null ? "" : plate.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
    }

    public Entry byPlate(String plate) {
        String n = norm(plate);
        if (n.isEmpty()) return null;
        for (int i = entries.size() - 1; i >= 0; i--) if (norm(entries.get(i).plate).equals(n)) return entries.get(i);
        return null;
    }

    public boolean taken(String plate) { return byPlate(plate) != null; }

    public List<Entry> ofOwner(UUID owner) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (owner.equals(e.owner)) out.add(e);
        return out;
    }

    /** Recherche par plaque, modèle, nom, prénom ou pseudo (insensible à la casse et aux tirets). */
    public List<Entry> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String qp = norm(query);
        List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (q.isEmpty() || (!qp.isEmpty() && norm(e.plate).contains(qp))
                    || (e.model + " " + e.firstName + " " + e.lastName + " " + e.ownerName).toLowerCase(Locale.ROOT).contains(q)) out.add(e);
        }
        return out;
    }

    public void add(MinecraftServer server, Entry e) {
        entries.add(e);
        changed(server);
    }

    public boolean remove(MinecraftServer server, String plate) {
        String n = norm(plate);
        boolean r = entries.removeIf(e -> norm(e.plate).equals(n));
        if (r) changed(server);
        return r;
    }

    /** Change le propriétaire d'une plaque (revente). */
    public boolean transfer(MinecraftServer server, String plate, UUID owner, String ownerName) {
        return transfer(server, plate, owner, ownerName, -1, null);
    }

    /** Revente entre joueurs : change le propriétaire et note le prix et le mode (price < 0 : inchangé). */
    public boolean transfer(MinecraftServer server, String plate, UUID owner, String ownerName, int price, String method) {
        Entry e = byPlate(plate);
        if (e == null) return false;
        if (price >= 0) e.price = price;
        if (method != null) e.method = method;
        e.owner = owner;
        e.ownerName = ownerName == null ? "" : ownerName;
        String[] id = IdentityBridge.identity(server, owner);
        e.firstName = id == null ? "" : id[0];
        e.lastName = id == null ? "" : id[1];
        e.birthDate = id == null ? "" : id[2];
        e.birthPlace = id == null ? "" : id[3];
        e.nationality = id == null ? "" : id[4];
        e.cardNumber = id == null ? "" : id[5];
        e.time = System.currentTimeMillis();
        changed(server);
        return true;
    }

    private void changed(MinecraftServer server) {
        setDirty();
        if (VehicleConfig.REGISTRY_JSON.get()) exportJson(server);
    }

    public void exportJson(MinecraftServer server) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm");
            JsonArray arr = new JsonArray();
            for (Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("plaque", e.plate);
                o.addProperty("modele", e.model);
                o.addProperty("couleur", e.color);
                o.addProperty("item", e.itemId);
                o.addProperty("proprietaire_uuid", e.owner == null ? "" : e.owner.toString());
                o.addProperty("pseudo", e.ownerName);
                o.addProperty("nom", e.lastName);
                o.addProperty("prenom", e.firstName);
                o.addProperty("date_naissance", e.birthDate);
                o.addProperty("lieu_naissance", e.birthPlace);
                o.addProperty("nationalite", e.nationality);
                o.addProperty("numero_carte", e.cardNumber);
                o.addProperty("vendeur", e.shopId);
                o.addProperty("prix", e.price);
                o.addProperty("paiement", e.method);
                o.addProperty("date_achat", fmt.format(new Date(e.time)));
                arr.add(o);
            }
            Path file = server.getWorldPath(LevelResource.ROOT).resolve(NAME + ".json");
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { GSON.toJson(arr, w); }
        } catch (Exception ex) {
            VehiclesMod.LOGGER.warn("Export des immatriculations impossible : {}", ex.toString());
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag t = new CompoundTag();
            t.putString("plate", e.plate);
            t.putString("model", e.model);
            t.putString("color", e.color);
            t.putString("item", e.itemId);
            t.putString("shop", e.shopId);
            t.putString("method", e.method);
            if (e.owner != null) t.putUUID("owner", e.owner);
            t.putString("ownerName", e.ownerName);
            t.putString("lastName", e.lastName);
            t.putString("firstName", e.firstName);
            t.putString("birthDate", e.birthDate);
            t.putString("birthPlace", e.birthPlace);
            t.putString("nationality", e.nationality);
            t.putString("card", e.cardNumber);
            t.putInt("price", e.price);
            t.putLong("time", e.time);
            list.add(t);
        }
        tag.put("entries", list);
        return tag;
    }

    public static PlateRegistry load(CompoundTag tag) {
        PlateRegistry r = new PlateRegistry();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Entry e = new Entry();
            e.plate = t.getString("plate");
            e.model = t.getString("model");
            e.color = t.getString("color");
            e.itemId = t.getString("item");
            e.shopId = t.getString("shop");
            e.method = t.getString("method");
            if (t.hasUUID("owner")) e.owner = t.getUUID("owner");
            e.ownerName = t.getString("ownerName");
            e.lastName = t.getString("lastName");
            e.firstName = t.getString("firstName");
            e.birthDate = t.getString("birthDate");
            e.birthPlace = t.getString("birthPlace");
            e.nationality = t.getString("nationality");
            e.cardNumber = t.getString("card");
            e.price = t.getInt("price");
            e.time = t.getLong("time");
            r.entries.add(e);
        }
        return r;
    }
}
