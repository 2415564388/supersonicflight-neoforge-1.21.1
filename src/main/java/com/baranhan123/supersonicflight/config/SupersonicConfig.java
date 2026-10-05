package com.baranhan123.supersonicflight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;

public class SupersonicConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File(FMLPaths.CONFIGDIR.get().toFile(), "supersonicflight.json");

    // --- 飞行 ---

    @ConfigComment("超音速(SONIC)模式的最大飞行速度，单位：格/游戏刻（20刻 = 1秒）。9.0 即每秒 180 格。")
    public float maxFlightSpeed = 9.0f;

    // --- 地形破坏总开关 ---

    @ConfigComment("两次起飞之间的最短间隔，单位：游戏刻（20刻 = 1秒）。\n"
            + "这是服务端的安全限制：起飞由客户端发起且会破坏地形，不限制的话改过的客户端\n"
            + "可以远超人类手速地反复触发，把地形和服务器线程一起拖垮。\n"
            + "填 0 关闭限制。默认 0.5 秒不影响正常操作（双击→取消→再双击远超此时长）。")
    public int takeoffCooldownTicks = 10;

    @ConfigComment("起飞时是否破坏脚下的地形（挖出弹坑）。")
    public boolean breakBlocksOnTakeoff = true;

    @ConfigComment("超音速撞击地面或墙壁时是否破坏地形。")
    public boolean breakBlocksOnImpact = true;

    @ConfigComment("重拳是否破坏正前方的地形。")
    public boolean punchBreakBlocks = true;

    @ConfigComment("重拳破坏地形的锥形半径，单位：格（默认 6，约 230 个方块）。\n"
            + "注意：重拳的【命中范围】会随飞行速度变大，但【破坏地形】的范围固定由这个值决定。\n"
            + "不设上限时，全速飞行的重拳会挖掉上万个方块。")
    public int punchDestructionRadius = 6;

    @ConfigComment("重拳打飞的生物在飞行途中是否撞穿地形。关闭后它会被地形挡下并爆炸。")
    public boolean launchBreakBlocks = true;

    @ConfigComment("抓取时是否让手里的生物碾碎沿途的方块（并因此受到碾磨伤害）。")
    public boolean grabGrindBlocks = true;

    @ConfigComment("起飞时是否清理头顶的竖井，防止在地下起飞时被天花板卡住。")
    public boolean breakBlocksAboveOnTakeoff = true;

    // --- 地形破坏数值 ---

    @ConfigComment("起飞/撞击弹坑的半径，单位：格。\n"
            + "注意：弹坑是半球形，体积按半径的立方增长——半径 5 约 260 个方块，半径 8 约 1000 个。")
    public int destructionRadius = 5;

    @ConfigComment("可被破坏的方块硬度上限。硬度大于或等于此值的方块不会被破坏。\n"
            + "参考：基岩为 -1（永远打不碎），黑曜石为 50。默认 50 表示黑曜石也打不碎。")
    public float destructionMaxHardness = 50.0f;

    @ConfigComment("起飞/撞击所破坏方块的掉落概率，单位：百分比。")
    public float destructionDropChance = 40.0f;

    @ConfigComment("头顶竖井的水平半径，单位：格。")
    public int takeoffClearRadius = 1;

    @ConfigComment("头顶竖井的高度，单位：格。")
    public int takeoffClearHeight = 64;

    // --- 技能伤害倍率 ---
    // 全部为「玩家攻击力」的倍数，且全部是真实伤害：
    // 使用 GENERIC_KILL 伤害类型，无视护甲与抗性。

    @ConfigComment("起飞瞬间对周围生物造成的伤害倍数（相对于玩家攻击力）。")
    public float takeoffDamageMultiplier = 1.0f;

    @ConfigComment("超音速撞击地面/墙壁时对周围生物造成的伤害倍数（相对于玩家攻击力）。")
    public float impactDamageMultiplier = 10.0f;

    @ConfigComment("抓取时把生物在地形上碾磨的伤害倍数（相对于玩家攻击力）。\n"
            + "注意：每游戏刻结算一次，数值调大会非常快致死。")
    public float grabGrindDamageMultiplier = 1.0f;

    @ConfigComment("重拳打飞的生物撞进地形那一刻受到的伤害倍数（相对于玩家攻击力）。只在撞击瞬间结算一次。")
    public float launchImpactDamageMultiplier = 1.0f;

    @ConfigComment("重拳打飞的生物最终爆炸时受到的伤害倍数（相对于玩家攻击力）。")
    public float launchExplosionDamageMultiplier = 10.0f;

    @ConfigComment("重拳打飞的生物最终爆炸的威力。原版 TNT 为 4.0，默认 3.0。\n"
            + "注意：爆炸坑的大小由这个值决定，与 destructionRadius 无关——想让坑更小就调低它。")
    public float launchExplosionPower = 3.0f;

    // --- 抓取 / 重拳 ---

    @ConfigComment("抓取与重拳技能的总开关。仍需先用 /pulsar super 开启能力才能使用。")
    public boolean grabEnabled = true;

    @ConfigComment("触发抓取所需的物品ID。若填错或对应模组未安装，技能会自动禁用（不会报错）。")
    public String grabItem = "l2weaponry:sculkium_claw";

    @ConfigComment("是否必须主手和副手都持有该物品才能触发抓取。")
    public boolean requireBothHands = true;

    @ConfigComment("是否只能抓取敌对生物。关闭后除玩家外的任意生物都能抓。")
    public boolean grabHostileOnly = true;

    @ConfigComment("抓取判定的距离，单位：格。搜索范围是以准星为轴、向视线前方延伸的锥形，\n"
            + "范围内离准星最近的那只生物会被抓住（而不是盒子里随便一只）。")
    public double grabReach = 3.0;

    @ConfigComment("被抓生物在左右方向上拉向准星的程度，取值 0.0 – 1.0。\n"
            + "0.0 = 保持钉在手部的位置（会偏在手那一侧，也就是偏左一点）；\n"
            + "1.0 = 完全钉在准星轴线上（生物会挡住正前方视野）。\n"
            + "只收左右偏移，前后距离不变，所以不改变重拳的手感。\n"
            + "重拳和松手已不依赖准星判定，这一项纯粹是观感。")
    public double grabCentering = 0.5;

    @ConfigComment("重拳命中的基础伤害。飞行速度越快，实际伤害越高。")
    public float punchBaseDamage = 10.0f;

    @ConfigComment("重拳打飞目标的基础力度。飞行速度越快，目标被打飞得越远。")
    public float punchLaunchForce = 4.5f;

    @ConfigComment("重拳所破坏方块的掉落概率，单位：百分比。")
    public float punchBlockDropChance = 40.0f;

    @ConfigComment("重拳的冷却时间，单位：游戏刻（20刻 = 1秒）。")
    public int punchCooldownTicks = 40;

    public static SupersonicConfig INSTANCE = new SupersonicConfig();

    public static void load() {
        if (FILE.exists()) {
            SupersonicConfig loaded = CommentedJson.load(FILE, SupersonicConfig.class, GSON);
            if (loaded != null) {
                INSTANCE = loaded;
            }
        }
        // Persist missing fields (newer options) and the comments themselves back into
        // pre-existing config files, so every option shows up in the file.
        save();
    }

    public static void save() {
        CommentedJson.save(FILE, INSTANCE, GSON);
    }
}
