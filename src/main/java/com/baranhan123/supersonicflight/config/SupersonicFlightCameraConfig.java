package com.baranhan123.supersonicflight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;

public class SupersonicFlightCameraConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File(FMLPaths.CONFIGDIR.get().toFile(), "supersonicflight-camera.json");

    @ConfigComment("是否启用高速转向时的镜头倾斜（滚转）效果。")
    public boolean cameraRoll = true;

    @ConfigComment("镜头倾斜的最大角度，单位：度。")
    public float maxCameraRoll = 80.0f;

    @ConfigComment("转向速度换算成倾斜角度的倍数。数值越大，轻微转向也会明显倾斜。")
    public float cameraRollMultiplier = 0.11f;

    @ConfigComment("倾斜回正的粗糙度，数值越大回正越迟钝。")
    public float cameraRollRoughness = 7.0f;

    public static SupersonicFlightCameraConfig INSTANCE = new SupersonicFlightCameraConfig();

    public static void load() {
        if (FILE.exists()) {
            SupersonicFlightCameraConfig loaded =
                    CommentedJson.load(FILE, SupersonicFlightCameraConfig.class, GSON);
            if (loaded != null) {
                INSTANCE = loaded;
            }
        }
        save();
    }

    public static void save() {
        CommentedJson.save(FILE, INSTANCE, GSON);
    }
}
