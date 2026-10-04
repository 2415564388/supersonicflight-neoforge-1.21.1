package com.baranhan123.supersonicflight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;

public class SupersonicConfigClient {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File(FMLPaths.CONFIGDIR.get().toFile(), "supersonicflight-client.json");

    @ConfigComment("是否启用高速飞行时的视场角(FOV)拉伸效果。")
    public boolean enableFovEffect = true;

    @ConfigComment("全速 SONIC 状态下垂直视场角的倍数（使用时会被限制在 1.0~2.0）。\n"
            + "该值在任何窗口尺寸/宽高比下都相同，所以调整窗口大小不会造成视野跳变。")
    public float fovMultiplier = 1.5f;

    @ConfigComment("水平视场角的上限，单位：度。用于防止超宽屏出现鱼眼或投影反转。")
    public float fovMaxHorizontal = 150.0f;

    @ConfigComment("视场角变化的平滑速度，取值 0.02~1.0。数值越大变化越快。")
    public float fovSmooth = 0.15f;

    @ConfigComment("是否启用飞行时的循环风声。")
    public boolean enableWindLoopSound = true;

    @ConfigComment("风声的音量倍数。")
    public float windVolumeMultiplier = 1.0f;

    public static SupersonicConfigClient INSTANCE = new SupersonicConfigClient();

    public static void load() {
        if (FILE.exists()) {
            SupersonicConfigClient loaded = CommentedJson.load(FILE, SupersonicConfigClient.class, GSON);
            if (loaded != null) {
                INSTANCE = loaded;
            }
            // Migrate configs written by older versions: the old fovMultiplier was an absolute
            // 11.0 modifier (now meaningless), so reset such stale values to the new default.
            if (INSTANCE.fovMultiplier > 5.0f) {
                INSTANCE.fovMultiplier = 1.5f;
            }
        }
        save();
    }

    public static void save() {
        CommentedJson.save(FILE, INSTANCE, GSON);
    }
}
