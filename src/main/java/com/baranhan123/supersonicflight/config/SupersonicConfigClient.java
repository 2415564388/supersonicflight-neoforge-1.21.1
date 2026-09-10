package com.baranhan123.supersonicflight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class SupersonicConfigClient {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File(FMLPaths.CONFIGDIR.get().toFile(), "supersonicflight-client.json");

    public boolean enableFovEffect = true;
    /** VERTICAL FOV multiplier during full SONIC (clamped 1.0–2.0 at use). The same value is
     *  applied at every window size/aspect so resizing the window never jumps the FOV. */
    public float fovMultiplier = 1.5f;
    /** Cap for the horizontal FOV in degrees, prevents fish-eye / projection inversion. */
    public float fovMaxHorizontal = 150.0f;
    /** Exponential smoothing rate for the FOV ramp (0.02–1.0). */
    public float fovSmooth = 0.15f;
    public boolean enableWindLoopSound = true;
    public float windVolumeMultiplier = 1.0f;

    public static SupersonicConfigClient INSTANCE = new SupersonicConfigClient();

    public static void load() {
        if (FILE.exists()) {
            try (FileReader reader = new FileReader(FILE)) {
                INSTANCE = GSON.fromJson(reader, SupersonicConfigClient.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
            // Migrate configs written by older versions: the old fovMultiplier was an absolute
            // 11.0 modifier (now meaningless), so reset such stale values to the new default.
            if (INSTANCE.fovMultiplier > 5.0f) {
                INSTANCE.fovMultiplier = 1.5f;
                save();
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
