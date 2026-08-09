package com.baranhan123.supersonicflight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class SupersonicFlightCameraConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File(FMLPaths.CONFIGDIR.get().toFile(), "supersonicflight-camera.json");

    public boolean cameraRoll = true;
    public float maxCameraRoll = 80.0f;
    public float cameraRollMultiplier = 0.11f;
    public float cameraRollRoughness = 7.0f;

    public static SupersonicFlightCameraConfig INSTANCE = new SupersonicFlightCameraConfig();

    public static void load() {
        if (FILE.exists()) {
            try (FileReader reader = new FileReader(FILE)) {
                INSTANCE = GSON.fromJson(reader, SupersonicFlightCameraConfig.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            save();
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
