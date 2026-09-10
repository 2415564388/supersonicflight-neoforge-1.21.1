package com.baranhan123.supersonicflight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class SupersonicConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File(FMLPaths.CONFIGDIR.get().toFile(), "supersonicflight.json");

    public float maxFlightSpeed = 9.0f;
    public boolean breakBlocksOnTakeoff = true;
    public boolean breakBlocksOnImpact = true;
    public int destructionRadius = 8;
    /** Clear a tube of blocks above the player during the vertical launch so an underground
     *  takeoff never gets stuck on a ceiling. */
    public boolean breakBlocksAboveOnTakeoff = true;
    /** Horizontal radius (in blocks) of the cleared tube above the player during launch. */
    public int takeoffClearRadius = 1;
    /** Height (in blocks) of the shaft cleared above the player at the moment of takeoff. */
    public int takeoffClearHeight = 64;

    public static SupersonicConfig INSTANCE = new SupersonicConfig();

    public static void load() {
        if (FILE.exists()) {
            try (FileReader reader = new FileReader(FILE)) {
                INSTANCE = GSON.fromJson(reader, SupersonicConfig.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // Persist missing fields (newer options) back into pre-existing config files so every
        // toggle shows up in the file.
        save();
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
