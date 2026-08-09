# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build the mod
./gradlew build

# Run the Minecraft client with the mod loaded
./gradlew runClient

# Run the Minecraft server with the mod loaded
./gradlew runServer

# Clean build artifacts
./gradlew clean

# Refresh Gradle dependencies and IDE project files
./gradlew --refresh-dependencies
./gradlew eclipse   # or: ./gradlew idea
```

There are no tests. The primary development loop is `./gradlew runClient` to test changes live in Minecraft.

**Important**: Use JDK 21 (not newer). The project has a local JDK 21 at `C:\Program Files\Java\jdk-21.0.12+8`. Set `JAVA_HOME` and `PATH` before building.

**Note on proxy**: `gradle.properties` contains HTTP/HTTPS proxy settings (`127.0.0.1:10809`). Remove or adjust these if not needed in your environment.

## Architecture

This is a **NeoForge 1.21.1** mod (mod ID: `supersonicflight`) that adds superhero-style flight to Minecraft. The mod runs on both the logical client and server.

### Flight State Machine

`FlightState` enum: `NONE → LAUNCH → HOVER → SONIC` (and back).

- **NONE**: Player is not flying. When flight is enabled by command, player is immune to fall damage.
- **LAUNCH**: Double-tap space triggers a vertical takeoff. 10 ticks (0.5s) of upward boost at 6 blocks/tick. Flame/cloud/explosion particles at feet. Optional terrain destruction (configurable). Transitions to HOVER automatically when takeoff ticks expire.
- **HOVER**: Normal flight mode. WASD-style movement — W key moves forward at **2 blocks/tick** in the look direction. Release W decelerates at 0.9× per tick. No elytra pose. Entered from LAUNCH completion or from SONIC when Ctrl is released.
- **SONIC**: Supersonic mode triggered by **holding Ctrl** (left or right). Moves at **9 blocks/tick** (capped by `maxFlightSpeed` config). Player model adopts Superman/fall-flying pose (shared flag 7 set). FOV widens to 11×. Heat glow renders on the player. Continuous shockwave ring behind the player. Only accessible from HOVER (not from LAUNCH). Releasing Ctrl returns to HOVER.

Key speeds defined as constants in `PlayerEntityMixin`:
- `NORMAL_SPEED = 2.0f` (HOVER forward)
- `SONIC_SPEED = 9.0f` (SONIC forward)
- `LAUNCH_SPEED = 6.0f` (vertical takeoff boost)

**Collision handling** (server-side only in `PlayerEntityMixin.onTick()`):
- Ground impact or horizontal collision during SONIC triggers `sonicGroundImpact()`: terrain destruction, 50× attack damage to nearby entities in a 6-block radius, sonic boom + takeoff sounds, explosion/firework particles.
- Horizontal collision during HOVER calls `switchToHover()` to reset state.
- LAUNCH is exempt from collision handling.

### Core Pattern: Mixin + Interface

The `SupersonicFlightPlayer` interface is mixed into `Player.class` via `PlayerEntityMixin`. The interface defines all flight-related getters/setters. The mixin implements them using `SynchedEntityData` — all flight data auto-syncs between client and server via vanilla's entity data system. Any code needing flight state casts a `Player` to `SupersonicFlightPlayer`.

### Flight Activation Flow

`/pulsar super [target]` (permission level 2) → `isFlightEnabled()=true` → double-tap space → `LAUNCH` → auto-transition to `HOVER` → hold W to fly forward → hold Ctrl for `SONIC`

Double-tapping space while already flying cancels flight entirely (returns to NONE).

### Package Structure

| Package | Purpose |
|---|---|
| `com.baranhan123.supersonicflight` | Root: `SupersonicFlight.java` — main mod class, registers sounds and command |
| `.util` | `FlightState` enum and `SupersonicFlightPlayer` interface |
| `.mixin` | Server/common mixins: `PlayerEntityMixin` (core flight logic + physics), `FlightAntiCheatBypassMixin` (prevents anti-cheat kicks at high speed), `FallingBlockEntityInvoker` (accessor for block-throwing on terrain destruction) |
| `.network` | `ModMessages` registers the 4 play-to-server payloads at protocol version "1.1" |
| `.network.packet` | `FlightLaunchPayload` (takeoff), `FlightTogglePayload` (cancel), `FlightSonicPayload` (Ctrl toggle), `FlightForwardPayload` (W key toggle). Three additional payloads exist as files but are **not registered**: `FlightAcceleratePayload`, `FlightSpeedLockPayload`, `HoverInputPayload`. |
| `.config` | `SupersonicConfig` (server-side), `SupersonicConfigClient` (client: FOV, sounds), `SupersonicFlightCameraConfig` (client: camera roll) — all JSON stored in the config directory |
| `.command` | `SupersonicFlightCommand` — `/pulsar super [target]` toggles `isFlightEnabled()` on a player (permission level 2) |
| `.registry` | `ModSounds` — `DeferredRegister` for `sonic_boom`, `wind_loop`, `takeoff` sound events |
| `.client` | `SupersonicFlightClient` (client init, loads client configs, registers `FlightInputHandler`), `FlightInputHandler` (client tick → detects double-tap, W, Ctrl → dispatches packets), `ModKeybinds` (R key — currently unused in the actual architecture), `MachDiskManager` (renders expanding shockwave rings in-world), `SonicBoomEffect` (post-processing shader with screen shake), `PoseDataManager` (data model for pose transforms) |
| `.client.mixin` | 5 client-side mixins for FOV, camera roll, first-person hand position, and shader access |
| `.client.render` | `FlightAnimManager` (per-player smooth animation state: supermanFactor, descendFactor), `AtmosphericHeatFeatureRenderer` (orange heat glow layer during SONIC) |
| `.client.sound` | `FlightWindSoundInstance` (looping wind sound, volume/pitch tied to throttle) |
| `.client.gui` | `FirstPersonScreen` (in-game client config UI with sliders/checkboxes), `ThirdPersonScreen` (36-slider body-part pose editor, placeholder values), `PoseSlider` (reusable slider widget) |

### Network Flow

All 4 active network payloads are **client→server only** (`playToServer`). The client (`FlightInputHandler`) polls input each client tick and dispatches:

| Packet | Trigger | Server Action |
|---|---|---|
| `FlightLaunchPayload` | Double-tap space when not flying | Sets LAUNCH state, 10 takeoff ticks, spawns particles+sounds, optional terrain destruction, 50× attack damage to nearby entities |
| `FlightTogglePayload` | Double-tap space when flying | Calls `stopFlight()`, clears all flight state |
| `FlightSonicPayload` (bool) | Ctrl pressed/released | Switches between SONIC and HOVER |
| `FlightForwardPayload` (bool) | W pressed/released in HOVER | Sets `isFlightAccelerating()` to control forward movement |

### Mixin Organization

**`supersonicflight.mixins.json`** (server/common, 3 mixins):
- `PlayerEntityMixin` — the core: implements `SupersonicFlightPlayer`, defines 9 synced data entries, runs per-tick flight physics, handles collisions, persists state to NBT
- `FlightAntiCheatBypassMixin` — resets `receivedMovePacketCount` and position trackers for LAUNCH/SONIC to prevent "moved too quickly" kicks
- `FallingBlockEntityInvoker` — `@Invoker` accessor for `FallingBlockEntity` constructor (used in terrain destruction)

**`supersonicflight.client.mixins.json`** (client-only, 5 mixins):
- `AbstractClientPlayerMixin` — forces `getFieldOfViewModifier` to return 11.0 during SONIC for extreme speed FOV
- `FlightCameraMixin` — calculates camera roll (Z-rotation) based on turn speed with exponential smoothing
- `GameRendererMixin` — hook point for camera roll application in `renderLevel`
- `ItemInHandRendererMixin` — translates first-person hand position down/back during flight
- `PostChainAccessor` — `@Accessor` exposing `PostChain.passes` for shader uniform manipulation

Several additional mixin source files exist but are **not registered** in the client mixin config: `PlayerModelMixin`, `SlowFlyingModelMixin`, `SlowFlyingRendererMixin`, `PlayerFeatureRendererMixin`, `CapeHeatFeatureMixin`, `PlayerRendererMixin`, `LivingEntityRendererAccessor`, `PlayerModelAccessor`.

### Config Files

Three JSON configs auto-generated in `FMLPaths.CONFIGDIR` on first run:

**`supersonicflight.json`** (server-side):
- `maxFlightSpeed` (default 9.0) — caps SONIC speed
- `isHeatEnabled` (default true) — atmospheric heat glow during SONIC
- `breakBlocksOnTakeoff` (default true) — destroy terrain on LAUNCH
- `breakBlocksOnImpact` (default true) — destroy terrain on SONIC ground/wall impact
- `destructionRadius` (default 8) — radius in blocks for terrain destruction

**`supersonicflight-client.json`** (client-side):
- `enableFovEffect` (true), `fovMultiplier` (10.0), `smoothFov` (200.0) — FOV during SONIC
- `enableWindLoopSound` (true), `windVolumeMultiplier` (1.0) — continuous wind audio
- `flightRotY` (0.85) — model rotation amount during flight
- `enableSonicBoomSound` (true), `sonicBoomVolume` (5.0)

**`supersonicflight-camera.json`** (client-side):
- `cameraRoll` (true), `maxCameraRoll` (80°), `cameraRollMultiplier` (0.11), `cameraRollRoughness` (7.0)

### Visual Effects Pipeline

1. **MachDiskManager** — in-world rendered shockwave rings (5 concentric layers, 80 segments each). Spawned on: LAUNCH entry (horizontal ring at feet), SONIC entry (ring behind player), SONIC→NONE on ground (impact ring). Rings expand (+0.25 radius/tick) and fade (-0.028 life/tick). Plays local sonic boom sound on SONIC entry.

2. **SonicBoomEffect** — full-screen post-processing shader (`assets/supersonicflight/shaders/post/sonic_boom.json`). Uniforms: `Throttle` (0-1 speed factor), `RippleTime` (decay on sonic exit), `Time`, `TakeoffShake` (8-tick decay on launch, 15-tick decay on impact). Shader is recreated on error.

3. **AtmosphericHeatFeatureRenderer** — render layer added via `PlayerFeatureRendererMixin`. Renders entire player model with translucent orange tint during SONIC only.

4. **FlightWindSoundInstance** — client-side looping sound. Volume = throttle × `windVolumeMultiplier`. Pitch = 0.5 + throttleLerped × 1.5.

5. **FlightAnimManager** — per-player `HashMap<UUID, AnimState>` tracking `supermanFactor` (0→1 smoothed based on throttle), `descendFactor` (vertical speed based), `takeoffAnim`, `sonicBoomAnim`, `throttleSmooth`.

### Key Minecraft Version Details

- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.150
- **Java**: 21
- **Gradle**: Userdev plugin `net.neoforged.gradle.userdev` version `7.0.145`
- **Mixin compatibility level**: JAVA_21
- **Mod version**: 1.5.1
- Access transformer file: `src/main/resources/META-INF/accesstransformer.cfg` (exists but is empty)
- All mixins reference `supersonicflight.refmap.json`

### Mod ID

`supersonicflight` — used for resource locations, mixin configs, network channel namespace, and config filenames. The mod ID constant is `SupersonicFlight.MOD_ID`.
