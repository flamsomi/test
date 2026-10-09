# ComboInfo 1.1.0 (source)

Display-only melee timing HUD for GTM-style hit-stun PvP on Minecraft 1.19.4 (Fabric, client side).
It reads state your client already has. It never sends input or packets.

## What changed from 1.0.4
| Area | 1.0.4 | 1.1.0 |
|---|---|---|
| Your own hits | not tracked | **Hit confirmation** (target's red flash after your swing), **your combo streak**, **"Swing now" timer** |
| Opponents | one shared combo | **Per opponent** stun timers (GTM's stun is per pair) |
| Attacker guess | nearest swinging player within 7 blocks | within 5 (configurable), **must be facing you**, 2-tick lookback for packet ordering |
| Logic runs on | HUD render callback (stops with F1, depends on FPS) | **client tick** (render only draws) |
| Weapons | diamond sword only (hard-coded) | **any item list**, set in game with `/combo weapon add` while holding it |
| Ping | ignored | optional ping compensation, ping in the confirm window |
| Stats | swings, hits taken, reaction | + hits landed, hit rate, trade (landed vs taken), your gaps |
| Log | flushed every row on render thread | flushed every 1 s and on exit; new events `HIT_LANDED`, `CUE_SWING_NOW`, `OUT_COMBO_END` |
| HUD | fixed spot | `/combo pos x y` |

## GTM data used (2026 only)
Source: grandtheftmc.net announcements, **Patch 3.4.0 "Hit Reg and Armor", 14 Jun 2026**
(https://grandtheftmc.net/threads/hit-reg-and-armor-patch-3-4-0.19397/)
- New hit system: **12 ticks between each katana hit**.
- After someone lands a hit on you, **you cannot damage *them* for 12 ticks** ("damage stun"). You can still damage others.
  Intent stated by the dev: reward the first hit and promote combos.
- Higher ping gets **1 tick of advantage per 100 ms**.
- The dev says these values can be changed live without a restart, so every number is a setting (`/combo delay`).

Also checked: Patch 3.1.0 (20 Apr 2026) was gun balance only (no melee weapon changes), and Patch 3.5.0 (23 Jun 2026) is a fishing event.
Not used: the wiki "Weapons" page (last edited 8 Nov 2025) and anything undated. **No 2026 source gives delays or damage for any melee weapon other than the katana's 12 ticks**, so the default is 12 for everything. Measure your weapon with the CSV (`gap_ticks`) and set `/combo delay`.
Heads-up from the same site: the dev said (Apr 2026) the server would move to "26.1.2" in the coming months, and the resource pack mentions 1.21.4+. Check what client version GTM wants before relying on a 1.19.4 build.

## Things I could NOT verify (please test in game)
1. Whether GTM's katana hit makes the target's **hurt flash** appear on your client. If `/combo stats` shows 0 hits landed while you are clearly hitting, run `/combo confirm false`.
2. Whether the ping rule shortens *your* wait. `/combo pingcomp true` assumes it does (cue = delay minus ping/100 ticks). Default off.
3. Which base item your melee weapons are (katana, golf club, etc.). Hold one and run `/combo weapon add`.
4. If `/combo stats` shows many "Other damage ignored" while you are being meleed, attacker detection is failing for that weapon (does its swing show on other clients?). The exact fix is a mixin on the 1.19.4 damage packet.

## Build
The sandbox this was written in had no network or Minecraft jars, so **no jar is included**.
- The game logic (`CombatModel`) is plain Java and was unit-tested (34 checks, all pass).
- `ComboInfoClient` was compile-checked against hand-written stubs of the Minecraft/Fabric signatures it uses, not the real Yarn 1.19.4 jars. If your build reports a wrong method name, it is a one-line rename.

With your own Loom project: copy `src/main` over and run `./gradlew build`.
Template build files are included, but their versions are from memory (check fabricmc.net/develop).

Run the model tests (JDK 17+):
    javac -d out src/main/java/com/example/comboinfo/CombatModel.java src/test/java/com/example/comboinfo/CombatModelTest.java
    java -cp out com.example.comboinfo.CombatModelTest

## Commands
/combo stats | reset | log | hud | always | delay | timeout | radius | pingcomp | confirm | pos | weapon
(`/combo` alone prints help. Settings live in `config/comboinfo.properties`, logs in `config/comboinfo/`.)
