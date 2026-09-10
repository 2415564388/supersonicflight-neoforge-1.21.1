# SupersonicFlight

超音速超级英雄飞行模组 | NeoForge 1.21.1

受《无敌少侠》(Invincible) 启发，为 Minecraft 添加三阶段超音速飞行能力——从垂直起飞到超音速冲击波，带来电影级的飞行体验。

## 安装

1. 安装 **NeoForge 21.1.150+**（Minecraft 1.21.1）
2. 将模组 `.jar` 文件放入 `mods` 文件夹
3. 启动游戏，首次运行后 `config/` 目录会自动生成三份配置文件

## 使用指南

### 开启飞行

```
/pulsar super          # 为自己开启/关闭飞行能力
/pulsar super <玩家>    # 为指定玩家开启/关闭
```

> 需要命令权限等级 2（作弊权限）。开启后聊天栏显示"脉门-开"，关闭显示"脉门-关"。

### 飞行操作

| 操作 | 效果 |
|---|---|
| **双击空格**（未飞行时） | 垂直起飞——LAUNCH 阶段 |
| **双击空格**（飞行中） | 取消飞行，回到普通状态 |
| **按住 W**（HOVER 状态） | 向视线方向飞行（2 方块/tick） |
| **松开 W** | 逐渐减速 |
| **按住 Ctrl**（HOVER 状态） | 进入超音速 SONIC 模式 |
| **松开 Ctrl** | 退出超音速，回到 HOVER |

### 飞行三阶段

```
双击空格 → LAUNCH（垂直起飞，0.5 秒）→ 自动进入 HOVER → 按 Ctrl → SONIC（超音速）
```

| 阶段 | 速度 | 说明 |
|---|---|---|
| **LAUNCH** | 6 方块/tick（垂直） | 持续 10 tick，脚下爆发火焰粒子，可破坏脚下方块；起飞瞬间自动打通头顶方块（`breakBlocksAboveOnTakeoff`），即使在山洞/地底起飞也不会被天花板卡住 |
| **HOVER** | 2 方块/tick | WASD 方向飞行，无鞘翅姿势 |
| **SONIC** | 9 方块/tick（可配置） | 超音速飞行，超人俯冲姿势，FOV 平滑拉伸，速度线与马赫环特效 |

### 碰撞效果

在 **SONIC** 模式下撞击地面或墙壁：
- 破坏周围方块（硬度 < 50，半径可配置）
- 对周围 6 格内生物造成 50 倍攻击力的**真实伤害**（无视护甲与抗性）
- 触发屏幕震动和音爆音效

---

## 配置文件

首次启动后，`config/` 目录下自动生成三份 JSON 配置文件。修改后重启游戏生效。

### `supersonicflight.json` — 服务端配置

```json
{
  "maxFlightSpeed": 9.0,
  "breakBlocksOnTakeoff": true,
  "breakBlocksOnImpact": true,
  "destructionRadius": 8,
  "breakBlocksAboveOnTakeoff": true,
  "takeoffClearRadius": 1,
  "takeoffClearHeight": 64
}
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `maxFlightSpeed` | 9.0 | SONIC 模式最大飞行速度（方块/tick） |
| `breakBlocksOnTakeoff` | true | 起飞时是否破坏脚下方块 |
| `breakBlocksOnImpact` | true | SONIC 撞击地面/墙壁时是否破坏方块 |
| `destructionRadius` | 8 | 破坏方块的圆形半径（格） |
| `breakBlocksAboveOnTakeoff` | true | 垂直起飞时是否自动打通头顶方块（防止被天花板挡住） |
| `takeoffClearRadius` | 1 | 起飞打通头顶时的水平半径（格） |
| `takeoffClearHeight` | 64 | 起飞时一次性打通头顶的高度（格） |

### `supersonicflight-client.json` — 客户端配置

```json
{
  "enableFovEffect": true,
  "fovMultiplier": 1.5,
  "fovMaxHorizontal": 150.0,
  "fovSmooth": 0.15,
  "enableWindLoopSound": true,
  "windVolumeMultiplier": 1.0
}
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `enableFovEffect` | true | 超音速飞行时是否启用 FOV 拉伸效果 |
| `fovMultiplier` | 1.5 | **垂直** FOV 放大倍数（SONIC 满效果、LAUNCH 半程，有效范围 1.0–2.0） |
| `fovMaxHorizontal` | 150.0 | 水平 FOV 上限（度），防止超宽屏画面过度拉伸/鱼眼变形 |
| `fovSmooth` | 0.15 | FOV 拉升与回落的速度平滑系数（0.02–1.0，越大响应越快） |
| `enableWindLoopSound` | true | 飞行中是否播放循环风声 |
| `windVolumeMultiplier` | 1.0 | 风声总音量倍率 |

> 旧版本把 `fovMultiplier` 当作绝对倍率（如 11.0）。检测到这类旧数值时模组会自动重置为新的 1.5 默认值。

### `supersonicflight-camera.json` — 相机配置

```json
{
  "cameraRoll": true,
  "maxCameraRoll": 80.0,
  "cameraRollMultiplier": 0.11,
  "cameraRollRoughness": 7.0
}
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `cameraRoll` | true | 转弯时是否启用画面倾斜 |
| `maxCameraRoll` | 80.0 | 最大倾斜角度（度） |
| `cameraRollMultiplier` | 0.11 | 倾斜灵敏度（越大越敏感） |
| `cameraRollRoughness` | 7.0 | 倾斜平滑度（越大越灵敏） |

---

## 视觉效果

- **速度线**：超音速（SONIC）飞行与起飞瞬间，画面内会出现向后拖曳的白色速度线
- **马赫环**：起飞、进入超音速、撞击地面时出现白色冲击环
- **屏幕着色器**：超音速飞行时的屏幕震动、色差分离和进入瞬间的白色闪光
- **相机倾斜**：转弯时画面随速度侧倾
- **粒子特效**：起飞火焰粒子、冲击环白烟粒子
- **风声**：循环风声，音量和音调随飞行速度变化

## 兼容性

- 兼容创造模式（已修复姿态抽搐问题）
- 内置反作弊绕过（防止高速飞行被服务器踢出）
- 几何渲染无自定义纹理，与光影模组（OptiFine / Iris）兼容

## 构建

```bash
./gradlew build          # 编译
./gradlew runClient      # 运行客户端测试
```

需要 JDK 21。

## 许可证

All Rights Reserved.
