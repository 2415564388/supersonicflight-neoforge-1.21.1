package com.baranhan123.supersonicflight.client.gui;

import com.baranhan123.supersonicflight.config.SupersonicConfig;
import com.baranhan123.supersonicflight.config.SupersonicConfigClient;
import com.baranhan123.supersonicflight.config.SupersonicFlightCameraConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The in-game config screen, offered through the mod list's Config button.
 *
 * <p>Built with Cloth Config's <b>manual</b> builder API rather than its {@code AutoConfig}
 * annotations. AutoConfig brings its own serializer, which would bypass {@code CommentedJson} and
 * destroy the on-disk format — the {@code //} notes above every option, the indentation and the blank
 * lines between entries. Driving it manually and hanging the write-off on {@code setSavingRunnable}
 * keeps that file exactly as hand-editing would leave it.
 *
 * <p>Labels and tooltips are not written here: {@link ConfigEntries} reads the field names and their
 * {@code @ConfigComment} notes straight off the config classes, so this file only decides
 * <em>grouping and bounds</em>.
 *
 * <p>Text is hard-coded Chinese to match the config comments, which are Chinese-only by design.
 */
public final class SupersonicConfigScreen {

    /** Amber, so a caveat reads as a caveat rather than as another option. */
    private static final int CAVEAT_COLOR = 0xFFAA00;

    /**
     * Shown on the server-side categories. The three options below the mod's flight and combat
     * settings are read by whichever side simulates the ability — which is the server. In singleplayer
     * that is the same process, so edits apply at once; against a dedicated server this screen edits
     * the <em>client's</em> copy and the server keeps using its own file.
     */
    private static final String SERVER_CAVEAT =
            "以下选项由服务端读取。单人游戏（集成服务端）改完立即生效；"
                    + "独立服务端需在服务端改同一项 config/supersonicflight.json，"
                    + "在这里改的是客户端本地那份，不影响服务端。";

    private SupersonicConfigScreen() {
    }

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("SupersonicFlight"))
                .setSavingRunnable(SupersonicConfigScreen::saveAll);

        ConfigEntryBuilder eb = builder.entryBuilder();
        Object server = SupersonicConfig.INSTANCE;

        flight(builder, eb, server);
        damage(builder, eb, server);
        grabAndPunch(builder, eb, server);

        Object client = SupersonicConfigClient.INSTANCE;
        clientOptions(builder, eb, client);

        Object camera = SupersonicFlightCameraConfig.INSTANCE;
        cameraOptions(builder, eb, camera);

        return builder.build();
    }

    /** Writes all three files. Each class owns a different JSON file; missing one silently drops edits. */
    private static void saveAll() {
        SupersonicConfig.save();
        SupersonicConfigClient.save();
        SupersonicFlightCameraConfig.save();
    }

    // ------------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------------

    private static void flight(ConfigBuilder builder, ConfigEntryBuilder eb, Object server) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("飞行"));
        ConfigEntries.note(category, eb, SERVER_CAVEAT, CAVEAT_COLOR);

        ConfigEntries.floatField(category, eb, server, "maxFlightSpeed", 0.5f, 50.0f);
        // 0 is the documented "no rate limit" value, so the lower bound has to allow it.
        ConfigEntries.intField(category, eb, server, "takeoffCooldownTicks", 0, 600);

        ConfigEntries.bool(category, eb, server, "breakBlocksOnTakeoff");
        ConfigEntries.bool(category, eb, server, "breakBlocksOnImpact");
        ConfigEntries.bool(category, eb, server, "breakBlocksAboveOnTakeoff");
        ConfigEntries.intSlider(category, eb, server, "takeoffClearRadius", 0, 8);
        ConfigEntries.intSlider(category, eb, server, "takeoffClearHeight", 0, 256);

        // Crater volume grows with the cube of the radius, so the slider stays deliberately short.
        ConfigEntries.intSlider(category, eb, server, "destructionRadius", 1, 16);
        // Not bounded: it is an arbitrary hardness threshold, and -1 (bedrock) is a meaningful value.
        ConfigEntries.floatField(category, eb, server, "destructionMaxHardness", -1.0f, 200.0f);
        ConfigEntries.floatField(category, eb, server, "destructionDropChance", 0.0f, 100.0f);
    }

    private static void damage(ConfigBuilder builder, ConfigEntryBuilder eb, Object server) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("技能伤害"));
        ConfigEntries.note(category, eb, SERVER_CAVEAT, CAVEAT_COLOR);

        // Every value is a multiple of the player's ATTACK_DAMAGE and is dealt as true damage that
        // bypasses armour and resistance, so the ceiling is generous but not unbounded.
        ConfigEntries.floatField(category, eb, server, "takeoffDamageMultiplier", 0.0f, 100.0f);
        ConfigEntries.floatField(category, eb, server, "impactDamageMultiplier", 0.0f, 100.0f);
        ConfigEntries.floatField(category, eb, server, "grabGrindDamageMultiplier", 0.0f, 100.0f);
        ConfigEntries.floatField(category, eb, server, "launchImpactDamageMultiplier", 0.0f, 100.0f);
        ConfigEntries.floatField(category, eb, server, "launchExplosionDamageMultiplier", 0.0f, 100.0f);
        // TNT is 4.0; the explosion's crater scales with this, not with destructionRadius.
        ConfigEntries.floatField(category, eb, server, "launchExplosionPower", 0.0f, 20.0f);
    }

    private static void grabAndPunch(ConfigBuilder builder, ConfigEntryBuilder eb, Object server) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("抓取与重拳"));
        ConfigEntries.note(category, eb, SERVER_CAVEAT, CAVEAT_COLOR);

        ConfigEntries.bool(category, eb, server, "grabEnabled");
        // Not validated against the registry on purpose: an unresolvable id disables the skill
        // silently, which is documented behaviour, and a screen that refused to save would be worse.
        ConfigEntries.str(category, eb, server, "grabItem");
        ConfigEntries.bool(category, eb, server, "requireBothHands");
        ConfigEntries.bool(category, eb, server, "grabHostileOnly");
        // Lower bound mirrors GrabPunchManager's own Math.max(0.5, ...).
        ConfigEntries.doubleField(category, eb, server, "grabReach", 0.5, 10.0);
        // Mirrors the Mth.clamp(..., 0.0, 1.0) at the point of use.
        ConfigEntries.doubleField(category, eb, server, "grabCentering", 0.0, 1.0);
        ConfigEntries.bool(category, eb, server, "grabGrindBlocks");

        ConfigEntries.floatField(category, eb, server, "punchBaseDamage", 0.0f, 1000.0f);
        ConfigEntries.floatField(category, eb, server, "punchLaunchForce", 0.0f, 50.0f);
        ConfigEntries.intField(category, eb, server, "punchCooldownTicks", 0, 600);
        ConfigEntries.bool(category, eb, server, "punchBreakBlocks");
        ConfigEntries.floatField(category, eb, server, "punchBlockDropChance", 0.0f, 100.0f);
        // Clamped at use time to the speed-scaled hit cone, so this is an effective ceiling.
        ConfigEntries.intSlider(category, eb, server, "punchDestructionRadius", 1, 16);
        ConfigEntries.bool(category, eb, server, "launchBreakBlocks");
    }

    private static void clientOptions(ConfigBuilder builder, ConfigEntryBuilder eb, Object client) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("客户端"));

        ConfigEntries.bool(category, eb, client, "enableFovEffect");
        // Both bounds mirror GameRendererMixin's clamp.
        ConfigEntries.floatField(category, eb, client, "fovMultiplier", 1.0f, 2.0f);
        ConfigEntries.floatField(category, eb, client, "fovMaxHorizontal", 30.0f, 180.0f);
        ConfigEntries.floatField(category, eb, client, "fovSmooth", 0.02f, 1.0f);
        ConfigEntries.bool(category, eb, client, "enableWindLoopSound");
        ConfigEntries.floatField(category, eb, client, "windVolumeMultiplier", 0.0f, 5.0f);
    }

    private static void cameraOptions(ConfigBuilder builder, ConfigEntryBuilder eb, Object camera) {
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("相机"));

        ConfigEntries.bool(category, eb, camera, "cameraRoll");
        // A negative bound would invert the clamp in FlightCameraMixin, so 0 is the floor.
        ConfigEntries.floatField(category, eb, camera, "maxCameraRoll", 0.0f, 180.0f);
        ConfigEntries.floatField(category, eb, camera, "cameraRollMultiplier", 0.0f, 1.0f);
        // Feeds Math.exp(-deltaTime * roughness); zero or below breaks the smoothing.
        ConfigEntries.floatField(category, eb, camera, "cameraRollRoughness", 0.1f, 50.0f);
    }
}
