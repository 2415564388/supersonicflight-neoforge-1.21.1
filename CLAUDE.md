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

**Important**: Use JDK 21 (not newer). The local JDK 21 is at `C:\Program Files\Java\jdk-21`; set `JAVA_HOME` before building:

```bash
JAVA_HOME="C:/Program Files/Java/jdk-21" ./gradlew build
```

(A Temurin 21 is also on `PATH`, though `JAVA_HOME` is what the wrapper reads.)

**Note on proxy**: `gradle.properties` contains HTTP/HTTPS proxy settings (`127.0.0.1:10809`). Remove or adjust these if not needed in your environment.

## Architecture

This is a **NeoForge 1.21.1** mod (mod ID: `supersonicflight`) that adds superhero-style flight to Minecraft. The mod runs on both the logical client and server.

### Flight State Machine

`FlightState` enum: `NONE → LAUNCH → HOVER → SONIC` (and back).

- **NONE**: Player is not flying. When flight is enabled by command, player is immune to fall damage.
- **LAUNCH**: Double-tap space triggers a vertical takeoff. 10 ticks (0.5s) of upward boost at 6 blocks/tick. Flame/cloud/explosion particles at feet. Optional terrain destruction (configurable). Also clears a tube above the player so an underground takeoff smashes through the ceiling instead of getting stuck (`breakBlocksAboveOnTakeoff` / `takeoffClearRadius` / `takeoffClearHeight`). Transitions to HOVER automatically when takeoff ticks expire.
- **HOVER**: Normal flight mode. WASD-style movement — W key moves forward at **2 blocks/tick** in the look direction. Release W decelerates at 0.9× per tick. No elytra pose. Entered from LAUNCH completion or from SONIC when Ctrl is released.
- **SONIC**: Supersonic mode triggered by **holding Ctrl** (left or right). Moves at **9 blocks/tick** (capped by `maxFlightSpeed` config). Player model adopts Superman/fall-flying pose (shared flag 7 set). Client FOV ramps up via a vertical-FOV multiplier (default 1.5×, smoothed, window-size independent). Speed-line trails and a shockwave ring render behind the player; a heat glow renders on the player. Only accessible from HOVER (not from LAUNCH). Releasing Ctrl returns to HOVER.

Key speeds defined as constants in `PlayerEntityMixin`:
- `NORMAL_SPEED = 2.0f` (HOVER forward)
- `SONIC_SPEED = 9.0f` (SONIC forward)
- `LAUNCH_SPEED = 6.0f` (vertical takeoff boost)

**Collision handling** (server-side only in `PlayerEntityMixin.onTick()`):
- **Collisions never end flight.** Double-tapping space is the only way out of a flight state. A player who lands keeps their current state and simply sits there until they fly off again.
- Ground impact or horizontal collision during SONIC triggers `sonicGroundImpact()`: terrain destruction, **true damage** (`DamageTypes.GENERIC_KILL`, bypasses armor/resistance) to nearby entities in a 6-block radius, sonic boom + takeoff sounds, explosion/firework particles. LAUNCH takeoff deals the same true damage to entities within a 5-block radius.
- `sonicGroundImpact` is **edge-triggered**, not per-tick: it fires once per contact episode. The `sonicImpactArmed` flag is cleared on impact and re-armed only after a tick with no ground or wall contact, with a 20-tick cooldown as a backstop against `horizontalCollision` flickering while sliding along a wall. Without this, a player resting on the ground would re-trigger the destruction every tick. It also sets the synced `IMPACT_FX_TICKS` countdown (20 ticks), which is the **only** signal the client gets that an impact happened — see the VFX pipeline for why the client cannot infer it from the flight state.
- Collisions never change the flight state — the collision block says so explicitly. A player pressed against a wall just stops moving; nothing needs suppressing. Ceiling rams are detected as `verticalCollision && !verticalCollisionBelow`, because hitting something *above* sets neither `onGround` (vanilla only sets it while descending) nor `horizontalCollision`.
- LAUNCH is exempt from collision handling.

### Core Pattern: Mixin + Interface

The `SupersonicFlightPlayer` interface is mixed into `Player.class` via `PlayerEntityMixin`. The interface defines all flight-related getters/setters. The mixin implements them using `SynchedEntityData` — all flight data auto-syncs between client and server via vanilla's entity data system. Any code needing flight state casts a `Player` to `SupersonicFlightPlayer`.

### Flight Activation Flow

`/pulsar super [target]` (permission level 2) → `isFlightEnabled()=true` → double-tap space → `LAUNCH` → auto-transition to `HOVER` → hold W to fly forward → hold Ctrl for `SONIC`

Double-tapping space while already flying cancels flight entirely (returns to NONE). This is the **only** way to leave a flight state — collisions deliberately do not end it.

The grab / heavy-punch skill set is gated on the same `isFlightEnabled()` flag, so `/pulsar super` unlocks flight, grab and punch together.

### Grab & Heavy Punch

A separate combat subsystem, ported from ViltrumiteCore. It shares `PlayerEntityMixin`'s synched data but has its own manager and event handler.

**Trigger**: hold the configured item (default `l2weaponry:sculkium_claw`) in **both hands**, then:

| Input | Effect |
|---|---|
| Right-click a hostile mob | Grab it (or, if already holding one, let it go) |
| **Left-click anywhere** | Heavy punch — the only way to throw one |
| Drop the grab item / lose `/pulsar super` | Releases the grab |

**Neither click goes through vanilla's crosshair, and that is load-bearing.** The victim is pinned to the player's **hand** (`GrabPunchManager.resolveHoldPos`), which sits off the eye→look axis, so the vanilla pick ray never reaches it and both inputs would silently no-op. `GrabbedAimTargetMixin` (`Minecraft`) therefore makes both clicks act on the held mob wherever the crosshair points — and it splits the two inputs, because the left click is not vanilla's to dispatch once a combat mod is installed:

- **Left click** is dispatched directly via `MultiPlayerGameMode#attack` — the same call vanilla's own ENTITY branch makes. Forging the crosshair result and letting vanilla run does *not* work here: Better Combat's `MinecraftClientInject#pre_doAttack` cancels `Minecraft#startAttack` at HEAD whenever the main-hand item carries Better Combat weapon attributes (the configured claw is one), then runs its own upswing and sends its own `C2S_AttackRequest` packet, so vanilla's switch on `hitResult` — and any forged result with it — never executes. Returning `false` also keeps a held mob's left click from swinging at whatever else is in reach.
- **Right click** does forge an `EntityHitResult` for the held mob and lets vanilla's ENTITY branch run, so the swing and the interact packet come along for free. That is safe because Better Combat's `pre_doItemUse` only cancels mid-upswing, and the left-click handler above stops any upswing from starting while something is held.

Re-aiming the victim at the crosshair was rejected as the fix: the client's pick tests the entity's **network-synced** position while the server pins it from its own view of the player — one tick of client prediction apart, i.e. ~9 blocks at SONIC speed — so the ray would miss again at exactly the speeds the skill is built around.

- **Grab**: the victim is pinned to the player's hand by `GrabPunchManager.tickGrab`, AI disabled (`Mob#setNoAi`), persistence forced, tagged `SupersonicGrabbed`, and dragged through terrain (hardness 0–50) which grinds it for damage. Releasing requires dropping the grab item, the victim dying, or punching. `LivingEntityGrabFailsafeMixin` clears a stale tag/AI if the grabber vanishes.
- **Which mob you grab** is decided by `GrabPunchManager.findGrabTarget`, not by the click: the search volume is a cone around the crosshair (half-width `grabReach * 0.5`) and the mob **closest to the ray** wins, with depth only breaking ties. It used to return whichever entity `getEntities` listed first inside an axis-aligned cube, so right-clicking one mob could grab its neighbour.
- **Where the victim sits**: `GrabPunchManager.centerOnAim` pulls the hold point sideways toward the eye→look axis by `grabCentering` (0–1) while leaving its forward distance alone. 0 keeps it on the hand, i.e. off to the holding side; 1 puts it on the crosshair axis. The renderer calls the same helper (`GrabbedEntityPositionMixin`), so the drawn model and the server-side hitbox cannot drift apart. Note the side effect at high values: the victim moves into the flight path, so `grabGrindBlocks` starts smashing whatever is straight ahead.
- **Punch**: started only via `SupersonicFlightPlayer.startPunch()`, which is atomic — it checks the cooldown and whether a punch is already running *before* flipping `IS_LEFT_ARM_PUNCH`. Splitting those two steps made a rejected punch still toggle the arm, which the client renders as the arm twitching. Vanilla can deliver both `EntityInteractSpecific` and `EntityInteract` for a single click, so the atomicity is load-bearing, not defensive. `GrabModelMixin` and `FirstPersonGrabMixin` also bail out while `punchTicks > 0`, because the punch keyframes both arms and would otherwise fight the grab pose during the wind-up (the victim is held until the impact tick).
- **Punch**: a 20-tick animation whose impact lands on tick 15 (`GrabPunchManager.executePunch`). Everything in a forward cone (plus a close-range guaranteed zone) takes damage scaled by flight throttle and is launched; the held victim is always launched. Launched entities become tracked "meteors" — `tickLaunchedEntities` ploughs them through blocks and detonates them once they exceed a momentum-derived block budget or meet an unbreakable block. Each meteor carries its thrower, so its collision and explosion damage scale to that player's attack damage.

**Terrain destruction is centralised.** `util/TerrainDestruction` owns the one hardness gate, drop roll and block-break particle burst. `FlightLaunchPayload.destroyTerrain` (takeoff + sonic impact) digs a `shatterCrater` bowl clipped at the player's feet, and `GrabPunchManager.shatterTerrain` tests its cone then calls the same `shatterBlock`. Earlier the flight path only popped the topmost block of each column and flung it as a falling-block entity, which read as a different mod from the punch's volume dig.

**Two-hand detection caveat**: `PlayerInteractEvent.EntityInteractSpecific` alone does *not* suppress the held item's own `interactLivingEntity` — vanilla falls through to `EntityInteract` on the client unless the cancellation result is `SUCCESS`. `GrabPunchHandler` therefore cancels *both* events. Read `getMainHandItem()`/`getOffhandItem()` rather than `event.getHand()`, since a two-handed hold fires the event once per hand.

**Hand-position sync**: the client sends a **player-relative offset** (`HandPosSyncPayload`), not a world position — at SONIC speed an absolute position arrives stale and the victim visibly trails the hand.

### Package Structure

| Package | Purpose |
|---|---|
| `com.baranhan123.supersonicflight` | Root: `SupersonicFlight.java` — main mod class (`MOD_ID`, static `LOGGER`), registers sounds and command |
| `.util` | `FlightState` enum, `SupersonicFlightPlayer` interface, `GrabPunchManager` (server-side grab/punch physics + meteor tracking), `TerrainDestruction` (the single destruction rule shared by takeoff, impacts and the punch) |
| `.event` | `GrabPunchHandler` — `@EventBusSubscriber(bus = GAME)`; right-click grab/release. Only `EntityInteractSpecific` acts, because acting on both interact events would make the toggle release-then-regrab on a single click |
| `.mixin` | Server/common mixins: `PlayerEntityMixin` (core flight logic + physics + all Player synched data), `FlightAntiCheatBypassMixin` (prevents anti-cheat kicks at high speed), `FallingBlockEntityInvoker` (accessor for block-throwing on terrain destruction), `GrabbedEntityPhysicsMixin` (cancels `Entity#push` on a held entity), `LivingEntityGrabFailsafeMixin` (restores a held entity's AI if the grabber vanishes) |
| `.network` | `ModMessages` registers 5 play-to-server payloads and 1 play-to-client, at protocol version "1.2" |
| `.network.packet` | `FlightLaunchPayload` (takeoff), `FlightTogglePayload` (cancel), `FlightSonicPayload` (Ctrl toggle), `FlightForwardPayload` (W key toggle), `HandPosSyncPayload` (grab hand offset) |
| `.config` | `SupersonicConfig` (server-side), `SupersonicConfigClient` (client: FOV, sounds), `SupersonicFlightCameraConfig` (client: camera roll) — all JSON stored in the config directory |
| `.command` | `SupersonicFlightCommand` — `/pulsar super [target]` toggles `isFlightEnabled()` on a player (permission level 2) |
| `.registry` | `ModSounds` — `DeferredRegister` for `sonic_boom`, `wind_loop`, `takeoff`, `punch_impact` sound events |
| `.client` | `SupersonicFlightClient` (client init, loads client configs, registers `FlightInputHandler`, registers the Cloth Config screen), `FlightInputHandler` (client tick → detects double-tap, W, Ctrl → dispatches packets), `GrabAnimationManager` (grab-pose blend weight), `ClientGrabbedIndex` (keeps `GrabbedEntityIndex` fresh each client tick), `ShaderCompat` (shadow-pass probe, so pose mixins stand down while a shader pack renders the shadow), `FlightShockwaveManager` (spawns the shockwave at takeoff and sonic entry), `SpeedLineManager` (anime-style additive speed-line trails during SONIC/instant takeoff, ported from ViltrumiteCore `DashVFXManager`), `ShockwaveRenderTypes` (shared RenderTypes for the shockwaves & speed lines), `SonicBoomEffect` (post-processing shader with screen shake) |
| `.client.mixin` | 14 client-side mixins: high-speed FOV, camera roll, first-person hand position, shader access, plus the grab/punch set (`GrabbedAimTargetMixin`, `FirstPersonGrabMixin` 1200, `FirstPersonPunchMixin` 1500, `GrabModelMixin` 2000, `PunchModelMixin` 1150, `PunchRendererCoreMixin`, `GrabbedEntityPositionMixin`, `GrabbedEntityRendererMixin`, `GrabbedEntityRendererMixin2`, `HandPositionTrackerMixin`) |
| `.client.vfx` | `PunchVFXManager` — the punch's additive "paint-pixel" shockwave ring; `ShockwaveGrid` — that grid's geometry, plus the ring-plane matrix and the model-view stack helpers, shared with `FlightShockwaveManager` |
| `.client.sound` | `FlightWindSoundInstance` (looping wind sound, volume/pitch tied to throttle) |
| `.client.gui` | `SupersonicConfigScreen` — the Cloth Config screen behind the mod list's Config button; `ConfigEntries` — reflective entry builders it is built from |

### Network Flow

All the payloads except one are **client→server** (`playToServer`). The client (`FlightInputHandler`) polls input each client tick and dispatches — except Ctrl, which is also handled on `InputEvent.Key` so the request leaves on the key event instead of waiting for the next tick, since the shockwave fires off the server echo and the poll quantises by up to 50 ms:

| Packet | Trigger | Server Action |
|---|---|---|
| `FlightLaunchPayload` | Double-tap space when not flying | Sets LAUNCH state, 10 takeoff ticks, spawns particles+sounds, optional terrain destruction, clears the column above when `breakBlocksAboveOnTakeoff`, true damage (`GENERIC_KILL`) to nearby entities |
| `FlightTogglePayload` | Double-tap space when flying | Calls `stopFlight()`, clears all flight state |
| `FlightSonicPayload` (bool) | Ctrl pressed/released | Switches between SONIC and HOVER |
| `FlightForwardPayload` (bool) | W pressed/released in HOVER | Sets `isFlightAccelerating()` to control forward movement |
| `HandPosSyncPayload` (3 doubles) | Client renders the grabbing arm | Stores a **player-relative** hand offset; the server pins the held entity to `player.position() + offset`, then applies `centerOnAim`. Ignored unless a target is grabbed, and rejected if further than 8 blocks from the player (`HandPosSyncPayload.MAX_OFFSET`) |

| Packet | Direction | Trigger | Action |
|---|---|---|---|
| `FlightStatePingPayload` (int state) | **server → client** | Server sets LAUNCH (`FlightLaunchPayload`) or SONIC/HOVER (`FlightSonicPayload`) | The client writes its own copy of the synced flight state. Purely a latency fix: entity data goes out in the end-of-tick batch, so the client otherwise learns of the transition up to a tick late — 24–51 ms measured here — and the takeoff/sonic shockwave visibly trails the key press. Sent to the acting player only; other players still learn through the normal sync |

### Mixin Organization

**`supersonicflight.mixins.json`** (server/common, 5 mixins):
- `PlayerEntityMixin` — the core: implements `SupersonicFlightPlayer`, defines **all 13** synced data entries (flight + grab/punch + the impact shockwave countdown), runs per-tick flight physics and `GrabPunchManager.tick`, handles collisions, persists state to NBT. All `Player` accessors live here on purpose: `SynchedEntityData.defineId` assigns ids in class-init order, so splitting them across two mixins risks a client/server id desync — and a new entry must be appended **last** so the existing ids do not shift. Adding one is also a hard client/server version break that `ModMessages`' protocol version does **not** catch (it covers payloads only), so both sides have to ship together.
- `FlightAntiCheatBypassMixin` — resets `receivedMovePacketCount` and position trackers for LAUNCH/SONIC to prevent "moved too quickly" kicks
- `FallingBlockEntityInvoker` — `@Invoker` accessor for `FallingBlockEntity` constructor (used in terrain destruction)
- `GrabbedEntityPhysicsMixin` — cancels `Entity#push` (both overloads) for held entities so the world cannot shove them out of the player's hand
- `LivingEntityGrabFailsafeMixin` — clears the `SupersonicGrabbed` tag and restores AI if the grabber disconnects, dies, or unloads

**`supersonicflight.client.mixins.json`** (client-only, 14 mixins):

*Flight:*
- `GameRendererMixin` — high-speed FOV widening in `getFov` (vertical-FOV multiplier with smoothing ramp + horizontal-FOV cap so window resize / ultra-wide doesn't jump or fish-eye; only touches `useFovSetting == true`, keeps held-item FOV vanilla), plus the hook point for camera roll application in `renderLevel`. Also publishes `SupersonicFlightClient.currentFovMultiplier` for the grab renderer.
- `FlightCameraMixin` — calculates camera roll (Z-rotation) based on turn speed with exponential smoothing
- `ItemInHandRendererMixin` — translates first-person hand position down/back during flight; stands down while grabbing or punching so it does not offset their keyframes
- `PostChainAccessor` — `@Accessor` exposing `PostChain.passes` for shader uniform manipulation

*Grab / punch (priorities matter — grab and punch both write the same model parts and both hook `renderArmWithItem`; `GrabbedAimTargetMixin` touches no model parts and sits outside that ordering):*
- `GrabbedAimTargetMixin` — the input layer: makes left/right click act on the held mob wherever the crosshair points (see Grab & Heavy Punch). Targets `Minecraft#startAttack`/`#startUseItem` via MixinExtras `@WrapMethod`
- `FirstPersonGrabMixin` (1200) — first-person "force choke" arm; also computes and sends the hand offset
- `FirstPersonPunchMixin` (1500) — first-person punch keyframes
- `GrabModelMixin` (2000) — third-person grab arm, built by copying the head rotation
- `PunchModelMixin` (1150) — third-person full-body punch keyframes (`@Shadow`s `PlayerModel.cloak`)
- `PunchRendererCoreMixin` — whole-body lunge, only while flying
- `GrabbedEntityPositionMixin` — re-anchors a held entity to the hand in `EntityRenderDispatcher.render`, then applies the same `centerOnAim` the server pins with
- `GrabbedEntityRendererMixin` — held-entity facing/pose; uses `@WrapOperation` (not the reference's `@Redirect`) on `EntityModel.setupAnim` so it composes with other mods
- `GrabbedEntityRendererMixin2` — culling + nametag placement for held entities
- `HandPositionTrackerMixin` — third-person hand offset source; local player only, so another player's grab cannot overwrite your hand position

> `AbstractClientPlayerMixin` was **removed** — its `getFieldOfViewModifier` FOV override is obsolete; the FOV logic now lives in `GameRendererMixin`.

Two porting hazards worth remembering: `setupAnim` on `PlayerModel` exists twice (the real `LivingEntity`-erased override plus an `Entity`-erased bridge) — target the `LivingEntity` one; and `Model.renderToBuffer` in 1.21.1 takes 5 args with a packed-colour `int` last, not the older `IIFFFF` form.

### Config Files

Three JSON configs auto-generated in `FMLPaths.CONFIGDIR` on first run. They are **commented JSON**: `config/CommentedJson` writes a `//` note above every option (taken from the field's `@ConfigComment`) and strips those lines again on read.

**These files are deliberately not valid JSON** — an editor will flag them, and that is expected. The notes are Chinese because the files are read by the pack author and players, and JSON has no comment syntax. Do not "fix" the warnings by deleting the comments.

Verified against Gson 2.10.1: its lenient reader tolerates both `//` and `#` natively, but nothing relies on that — the stripper is explicit, so changing the parser or its leniency cannot silently corrupt a config. The stripper tracks string literals, so an option value containing `//` survives; Gson never sees a comment.

Never write these files by hand without the annotation, and never add a field without one.

**`supersonicflight.json`** (server-side):
- `maxFlightSpeed` (default 9.0) — caps SONIC speed
- `takeoffCooldownTicks` (10) — **server-side rate limit on LAUNCH.** The takeoff packet is client-triggered and destroys terrain, so without this a modified client could alternate launch/cancel far faster than a human can double-tap and repeatedly crater the world on the server thread. 0 disables it; 10 ticks (0.5 s) does not affect normal play, since a cancel-and-relaunch sequence takes far longer than that.
- `breakBlocksOnTakeoff` (default true) — destroy terrain on LAUNCH
- `breakBlocksOnImpact` (default true) — destroy terrain on SONIC ground/wall impact
- `destructionRadius` (default 5) — radius in blocks for takeoff/impact craters. Crater volume grows with the cube of the radius: 5 is ~260 blocks, 8 is ~1000. `TerrainDestruction.MAX_BLOCKS_PER_CRATER` (4096) caps a single crater so a mis-set value cannot lock the server.
- `breakBlocksAboveOnTakeoff` (default true) — clear a vertical tube above the player during LAUNCH so an underground takeoff doesn't get stuck on a ceiling
- `takeoffClearRadius` (default 1) — horizontal radius (blocks) of the cleared tube above the player
- `takeoffClearHeight` (default 64) — height (blocks) of the shaft cleared at the moment of takeoff
- `grabEnabled` (true) — master switch for the grab / heavy-punch skill
- `grabItem` (default `"l2weaponry:sculkium_claw"`) — item that arms the skill; an unresolvable id simply disables it
- `requireBothHands` (true) — require the item in both hands
- `grabHostileOnly` (true) — only `Enemy` mobs; players are never valid targets
- `grabReach` (3.0) — length of the grab search cone. The search volume is a cone around the crosshair and the mob **closest to the ray** wins; the click itself does not pick the target
- `grabCentering` (0.5) — how far the held mob is pulled sideways toward the crosshair axis, 0–1. 0 keeps it on the hand (off to the holding side), 1 puts it on the crosshair and blocks the view. Forward distance is unchanged, and punch/release no longer depend on the crosshair, so this is purely cosmetic — except that raising it moves the victim into the flight path, where `grabGrindBlocks` starts eating the terrain ahead
- `grabGrindBlocks` (true) — held entity smashes and is damaged by blocks it is dragged through
- `punchBaseDamage` (10.0), `punchLaunchForce` (4.5) — punch power; both scale with flight throttle
- `punchBreakBlocks` (true), `punchBlockDropChance` (40.0) — punch terrain destruction
- `punchDestructionRadius` (6) — **caps the punch's terrain cone independently of its hit volume.** The hit cone scales with flight throttle (`rOut = 9 * (1 + throttle * 1.5)`), which at full throttle is 22 blocks long — about 12,000 blocks, 45x the `destructionRadius` preset. The terrain cone is clamped to this radius and scaled proportionally so the shape is unchanged, just shorter.
- `launchExplosionPower` (3.0) — power of a punched entity's final explosion. Was derived from the meteor's speed (up to 10, i.e. 2.5x TNT), which is what made the end crater dwarf everything else. Ignored entirely when `launchBreakBlocks` is off.
- `punchCooldownTicks` (40) — punch cooldown

**Shared terrain destruction:**
- `destructionMaxHardness` (50.0) — blocks with a destroy speed at or above this survive (bedrock is -1, obsidian is 50). Shared by takeoff, impacts and the punch via `util/TerrainDestruction`.
- `destructionDropChance` (40.0) — drop roll for blocks destroyed by takeoff and sonic impacts (the punch has its own `punchBlockDropChance`)

**Ability damage.** Every value below is a multiple of the player's `ATTACK_DAMAGE`, dealt as true damage via `DamageTypes.GENERIC_KILL`. See `GrabPunchManager.attackDamage`/`trueDamage`.
- `takeoffDamageMultiplier` (1.0) — to nearby entities at takeoff
- `impactDamageMultiplier` (10.0) — to nearby entities on a sonic ground/wall impact
- `grabGrindDamageMultiplier` (1.0) — per grinding tick to a held entity, applied once per tick (not per block)
- `launchImpactDamageMultiplier` (1.0) — to a punched entity when it first bites into terrain
- `launchExplosionDamageMultiplier` (10.0) — to a punched entity by the final explosion

**Terrain-destruction toggles.** Every ability that can destroy terrain has its own switch, all defaulting to on: `breakBlocksOnTakeoff`, `breakBlocksOnImpact`, `punchBreakBlocks`, `launchBreakBlocks` (whether a punched entity ploughs through terrain on its way), `grabGrindBlocks`, `breakBlocksAboveOnTakeoff`. With `launchBreakBlocks` off the entity still stops against terrain and detonates — it just does not eat through it.

**`supersonicflight-client.json`** (client-side):
- `enableFovEffect` (true) — high-speed FOV effect on/off
- `fovMultiplier` (1.5) — **vertical** FOV multiplier at full SONIC, clamped 1.0–2.0 at use; stale values >5.0 (old absolute-multiplier configs) auto-reset to 1.5 on load
- `fovMaxHorizontal` (150.0) — cap on the resulting horizontal FOV (degrees), prevents fish-eye on ultra-wide windows
- `fovSmooth` (0.15) — exponential smoothing rate of the FOV ramp (0.02–1.0)
- `enableWindLoopSound` (true), `windVolumeMultiplier` (1.0) — continuous wind audio

**`supersonicflight-camera.json`** (client-side):
- `cameraRoll` (true), `maxCameraRoll` (80°), `cameraRollMultiplier` (0.11), `cameraRollRoughness` (7.0)

### In-Game Config Screen (Cloth Config, optional)

All 40 options are editable in game through Cloth Config, reached from the **mod list's Config button**. NeoForge resolves a mod's config screen itself via `IConfigScreenFactory.getForMod`, so no ModMenu is involved: `SupersonicFlightClient.registerConfigScreen` registers the `IConfigScreenFactory` extension point on the mod container, guarded on `ModList.get().isLoaded("cloth_config")`. That guard is also what keeps `SupersonicConfigScreen` — and the Cloth classes it references — from ever being resolved when the library is absent, which is what lets the dependency stay optional in the first place.

Cloth Config is `compileOnly` + `runtimeOnly` in `build.gradle` (`cloth_config_version` in `gradle.properties`, repo `https://maven.shedaniel.me/`), so nothing is bundled, and `type = "optional"` in `neoforge.mods.toml`.

- `client/gui/SupersonicConfigScreen.java` — uses Cloth's **manual** builder API, not `AutoConfig`. AutoConfig brings its own serializer and would bypass `CommentedJson`, destroying the commented-JSON format (the `//` notes, the indentation, the blank lines). Driving it manually and hanging the write-off on `setSavingRunnable` → `saveAll()` keeps the file byte-for-byte what hand-editing leaves. `saveAll()` must call all **three** `save()`s; they write three different files.
- `client/gui/ConfigEntries.java` — builds entries reflectively off the config classes, mirroring how `CommentedJson` already works. Labels are the field names, tooltips are each field's `@ConfigComment` split on newlines, and default values come from a freshly constructed instance of the config class — so the screen restates nothing and cannot drift from the fields it edits. An unknown field name **throws** rather than being skipped: a typo has to fail at screen-build time, not ship as a silently missing option.

Bounds in the screen deliberately mirror the clamps applied at use time (`grabReach ≥ 0.5`, `grabCentering` 0–1, `fovMultiplier` 1–2, `fovSmooth` 0.02–1, `punchDestructionRadius ≥ 1`, `maxCameraRoll ≥ 0`, `cameraRollRoughness > 0`), so the screen cannot offer a value that would be silently corrected later. `takeoffCooldownTicks` keeps 0 reachable — that is the documented "no rate limit" value, not an edge case.

**Server-side caveat.** `SupersonicConfig`'s 30 options are read by the server. In singleplayer (integrated server) screen edits apply immediately; against a dedicated server the screen edits the *client's* copy while the server keeps using its own file. The three server-side categories carry a coloured note saying so, because the alternative — a screen that silently does nothing — is worse than one that explains itself.

### Visual Effects Pipeline

1. **FlightShockwaveManager** — the shockwave at the two flight moments that have one: LAUNCH entry and SONIC entry. Drawn in-world on `RenderLevelStageEvent` (AFTER_LEVEL) through `ShockwaveRenderTypes.additiveQuad()`. Waves are **millisecond**-based (`progress = (now − spawnTime) / lifetimeMs`, ease-out radius), so their motion is frame-rate independent, and several are STAGGERED per moment: 3 concentric horizontal waves at the feet on LAUNCH entry (1.2→11.0 / 0.9→9.0 / 0.6→7.0, 900/850/800 ms, +0/+90/+180 ms), and 3 waves stacked along the look direction on SONIC entry (1.5→22.0, 1200 ms, +0/+100/+200 ms, rate-limited per player to one burst per `SonicBoomEffect.SONIC_ENTRY_COOLDOWN_MS` = 300 ms). Waves are fixed in the world where they were created, not carried along with the player, so at SONIC speed the player flies out of them within a tick and sees most of the expansion after slowing down again. **A real sonic impact draws no shockwave** — only the server's explosion particles and sounds. The rings used to spawn there too, two horizontal ones at the feet; at SONIC speed the player crosses their plane within a tick, so they were invisible at an actual collision and only ever appeared on the entry tick (the server counts pressing Ctrl while still touching terrain as an impact), reading as a second, unrelated ring arriving after the entry burst. The geometry is the same "paint pixel" grid the punch uses (`client/vfx/ShockwaveGrid`), but the flight waves use a **fixed** cell count (`GRID_RESOLUTION` = 64) and a **fixed** band width (`BAND_CELLS` = 5, i.e. 5/64 ≈ 7.8% of the radius), rather than the punch's own ramps. The punch can afford to ramp its count 4→32 because its radius only reaches 6.5 blocks; at the flight moments' radius 1 a 4-cell grid is six squares, and six squares do not make a circle — the wave's first 200–300 ms rendered as a crooked blob and only resolved into a ring once it had grown. Alpha holds at full through the expansion and fades only over the last 25%: fading linearly from the first frame made the ring half transparent by the time it was half expanded, so the only clearly visible part of it was the small early phase and the outward expansion went unnoticed. Those two constants are the tuning knob: raise `BAND_CELLS` for a thicker line, `GRID_RESOLUTION` for finer pixels. Each wave's plane is perpendicular to its stored normal, built by `ShockwaveGrid.facingTransform`; the sign of the normal is irrelevant (the grid is radially symmetric and mirrored). Plays the local sonic boom sound on SONIC entry. Also emits the takeoff CLOUD burst particles.

   **The burst is spawned the instant the new state reaches the client, pushed out immediately rather than left to the tick.** `FlightStatePingPayload` (server → client, sent from `FlightLaunchPayload` and `FlightSonicPayload` right after they set the state) carries it on the same immediate path the takeoff particles use; the client's handler then calls `FlightShockwaveManager.onStatePushed`, which spawns the burst *and* advances `PREV_STATE` so the per-tick pass does not fire it a second time. Measured on the takeoff path: the state lands 10 ms after the request, against 53 ms when it waited for the tick boundary — the per-tick loop looks only once per client tick, so it quantised the effect by up to 50 ms. That loop is kept as the fallback, and is the only path for **other** players. A dedicated `FlightStatePingPayload` beats driving the effect off the key press (which was tried and reverted): the waves are positioned relative to the player at spawn time, so predicting them while the player is still hovering lands them somewhere quite different.

2. **SpeedLineManager** — anime-style additive white speed-line trails (ported from ViltrumiteCore `DashVFXManager`) that trail the player during SONIC flight and instant takeoff. ~4 quads/tick, ~200 ms lifetime, stretched backwards along movement, max 400 lines, drawn over the level (`RenderLevelStageEvent`), via `ShockwaveRenderTypes`.

3. **SonicBoomEffect** — full-screen post-processing shader (`assets/supersonicflight/shaders/post/sonic_boom.json`). Uniforms: `Throttle` (0-1 speed factor), `RippleTime` (decay on sonic exit), `Time`, `TakeoffShake` (8-tick decay on launch, 15-tick decay on impact). Shader is recreated on error.

4. **FlightWindSoundInstance** — client-side looping sound. Volume = throttle × `windVolumeMultiplier`. Pitch = 0.5 + throttleLerped × 1.5.

5. **PunchVFXManager** — the heavy punch's shockwave, drawn on `RenderLevelStageEvent` (AFTER_LEVEL) through `ShockwaveRenderTypes.additiveQuad()`. Not a smooth band: it is a grid of "paint pixels" of decreasing world size (4→32 cells, radius 0.5→6.5), tinted 240/250/255, live only for the `[0.25, 0.65]` slice of the 20-tick punch. Built on `ShockwaveGrid` rather than the reference's raw `Tesselator`/`BufferBuilder`, which targets an older GL state API.
   Two things here look like bugs and are **deliberately preserved**, because they are what the effect is liked for — do not "fix" either without expecting the look to change:
   - `progress = (time − 0.25f) / (0.4f / 1.5f)` reaches 1.0 at `time ≈ 0.5167`, so the `time > 0.65f` upper bound is dead code and the ring finishes expanding in ~8 ticks rather than ~13.
   - The ring's plane is **not** perpendicular to the look vector. Post-multiplying `Ry(−yaw)·Rx(−pitch)` makes its world normal `(−sinY, cosY·sinP, cosY·cosP)` while the look is `(−sinY·cosP, −sinP, cosY·cosP)` — the pitch is sign-flipped, so it only faces the look when the player is level. This is also why `ShockwaveGrid` takes a ready-made matrix instead of being rebuilt from a normal at the punch's call site.
   Both callers bracket their flush with `ShockwaveGrid.pushIdentityModelView()` / `popModelView()` in a `try/finally`: ModelViewMat has to be identity while the shared buffer flushes, and an unbalanced push would leak into the held-item pass and the GUI.

`GrabAnimationManager` (client) supplies the 0–1 blend weight for the grab pose — keyed weakly by entity, so the arm eases in and out instead of snapping.

### Key Minecraft Version Details

- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.150
- **Java**: 21
- **Gradle**: Userdev plugin `net.neoforged.gradle.userdev` version `7.0.145`
- **Mixin compatibility level**: JAVA_21
- **Mod version**: 1.2.2
- Access transformer file: `src/main/resources/META-INF/accesstransformer.cfg` (exists but is empty)
- All mixins reference `supersonicflight.refmap.json`

### Mod ID

`supersonicflight` — used for resource locations, mixin configs, network channel namespace, and config filenames. The mod ID constant is `SupersonicFlight.MOD_ID`.
