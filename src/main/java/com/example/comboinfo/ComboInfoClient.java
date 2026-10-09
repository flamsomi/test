package com.example.comboinfo;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * ComboInfo 1.1.0 - passive melee timing HUD for GTM-style hit-stun PvP.
 * Display only: it reads state the client already has and never sends input or packets.
 *
 * Game logic lives in {@link CombatModel}; this class is the Minecraft adapter
 * (events, attacker detection, HUD, commands, config, CSV log).
 */
public class ComboInfoClient implements ClientModInitializer {

    private static final String PREFIX = "\u00a7b[Combo] \u00a7f";
    private static final String TICK = "\u2713";
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GREEN = 0xFF55FF55;
    private static final String DEFAULT_WEAPON = "minecraft:diamond_sword";

    // ---- user settings that are not part of the combat model ----
    private boolean hudEnabled = true;
    private boolean alwaysShowAttack = false;
    private int radius = 5;                         // blocks: who counts as "could have hit me"
    private int hudX = 0, hudY = 0;                 // offset from the default spot under the crosshair
    private final Set<String> weapons = new LinkedHashSet<>();   // item ids; "*" means any item

    private final CombatModel model = new CombatModel(System::nanoTime);

    // ---- per-tick state ----
    private int clientTick = 0;
    private ClientPlayerEntity lastPlayer;
    private int lastHurt = 0;
    private float lastCharge = -1f;
    private Entity tickStartTarget;                 // crosshair target the attack of this tick used
    private final Map<String, Integer> swingSeen = new HashMap<>();   // player -> last tick we saw their hand swing
    private final Map<String, Integer> prevHurt = new HashMap<>();    // player -> hurtTime last tick
    private boolean crashed = false;

    // ---- CSV log ----
    private BufferedWriter log;
    private Path logPath;
    private boolean logDirty = false;
    private final long sessionNano = System.nanoTime();

    // =====================================================================================
    // Init
    // =====================================================================================

    @Override
    public void onInitializeClient() {
        loadConfig();
        model.setSink(this::logRow);
        ClientTickEvents.START_CLIENT_TICK.register(mc -> tickStartTarget = mc.targetedEntity);
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        HudRenderCallback.EVENT.register(this::onHud);
        ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> closeLog());
    }

    // =====================================================================================
    // Per-tick logic (runs on END_CLIENT_TICK, so it does not depend on frame rate or F1)
    // =====================================================================================

    private void onEndTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            if (lastPlayer != null) resetWorldState();
            return;
        }
        if (crashed) return;
        try {
            if (p != lastPlayer) {
                resetWorldState();
                lastPlayer = p;
                lastHurt = p.hurtTime;
            }
            clientTick++;
            int t = clientTick;
            if (t % 10 == 0) updatePing(mc, p);

            // 1) look at the other players: who swung, whose hurt flash just started
            List<String> hurtRisers = new ArrayList<>();
            scanPlayers(mc, p, t, hurtRisers);

            // 2) did I just take a hit?
            if (p.hurtTime > lastHurt) {
                String attacker = findAttacker(mc, p, t);
                if (attacker != null) model.onHitTaken(t, attacker);
                else model.onOtherDamage(t);
            }
            lastHurt = p.hurtTime;

            // 3) did I just swing? (attack cooldown dropped while my hand is swinging)
            float charge = p.getAttackCooldownProgress(0.0F);
            if (lastCharge >= 0f && charge < lastCharge - 0.001F && p.handSwinging) {
                String name = null;
                boolean isPlayer = false;
                Entity target = tickStartTarget;
                if (target instanceof LivingEntity && target.isAlive()) {
                    name = target.getEntityName();
                    isPlayer = target instanceof PlayerEntity;
                }
                model.onSwing(t, lastCharge, name, isPlayer);
            }
            lastCharge = charge;

            // 4) hurt flashes AFTER the swing was registered, so they can confirm it
            for (String name : hurtRisers) model.onTargetHurt(t, name);

            model.tick(t, p.getHealth() + p.getAbsorptionAmount());
            if (logDirty && t % 20 == 0) flushLog();
        } catch (Throwable ex) {
            crashed = true;
            msg("\u00a7cComboInfo hit an error and switched itself off: " + ex);
        }
    }

    private void resetWorldState() {
        model.resetTransient();
        lastPlayer = null;
        lastCharge = -1f;
        swingSeen.clear();
        prevHurt.clear();
    }

    private void updatePing(MinecraftClient mc, ClientPlayerEntity p) {
        ClientPlayNetworkHandler h = mc.getNetworkHandler();
        if (h == null) return;
        PlayerListEntry e = h.getPlayerListEntry(p.getUuid());
        if (e != null) model.pingMs = e.getLatency();
    }

    private void scanPlayers(MinecraftClient mc, ClientPlayerEntity me, int t, List<String> hurtRisers) {
        Set<String> present = new HashSet<>();
        for (AbstractClientPlayerEntity o : mc.world.getPlayers()) {
            if (o == me) continue;
            String name = o.getEntityName();
            present.add(name);
            if (o.handSwinging) swingSeen.put(name, t);
            Integer before = prevHurt.get(name);
            if (before != null && o.hurtTime > before) hurtRisers.add(name);
            prevHurt.put(name, o.hurtTime);
        }
        swingSeen.keySet().retainAll(present);
        prevHurt.keySet().retainAll(present);
    }

    /**
     * Best guess of who hit me: the nearest player inside {@link #radius} blocks who swung within the
     * last 2 ticks AND is roughly facing me. Returns null if nobody fits (fire, fall, arrows, ...).
     * Exact attribution would need the 1.19.4 damage packet (mixin) - see README.
     */
    private String findAttacker(MinecraftClient mc, ClientPlayerEntity me, int t) {
        double r2 = (double) radius * radius;
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (AbstractClientPlayerEntity o : mc.world.getPlayers()) {
            if (o == me) continue;
            String name = o.getEntityName();
            Integer seen = swingSeen.get(name);
            if (seen == null || t - seen > 2) continue;
            double d2 = me.squaredDistanceTo(o);
            if (d2 > r2 || d2 >= bestD) continue;
            Vec3d toMe = me.getEyePos().subtract(o.getEyePos());
            if (toMe.lengthSquared() > 1.0E-6) {
                double facing = o.getRotationVec(1.0F).dotProduct(toMe.normalize());
                if (facing < 0.2) continue;       // swinging at something else
            }
            best = name;
            bestD = d2;
        }
        return best;
    }

    private boolean holdingMelee(ClientPlayerEntity p) {
        if (weapons.contains("*")) return true;
        ItemStack s = p.getMainHandStack();
        if (s == null || s.isEmpty()) return false;
        return weapons.contains(Registries.ITEM.getId(s.getItem()).toString());
    }

    // =====================================================================================
    // HUD
    // =====================================================================================

    private void onHud(MatrixStack m, float td) {
        if (crashed || !hudEnabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.textRenderer == null) return;
        if (mc.options != null && mc.options.hudHidden) return;
        try {
            draw(mc, p, m, td);
        } catch (Throwable ex) {
            hudEnabled = false;
            msg("\u00a7cComboInfo HUD hit an error and was switched off: " + ex);
        }
    }

    private void draw(MinecraftClient mc, ClientPlayerEntity p, MatrixStack m, float td) {
        TextRenderer tr = mc.textRenderer;
        Window w = mc.getWindow();
        if (w == null) return;
        int cx = w.getScaledWidth() / 2 + hudX;
        int y = w.getScaledHeight() / 2 + 22 + hudY;
        float now = clientTick + td;
        boolean melee = holdingMelee(p);
        int timeout = Math.max(1, model.comboTimeout);
        int delay = model.effDelay();

        CombatModel.Incoming in = model.primaryIncoming();
        if (in != null) {
            // You are being comboed: show the streak, then when your stun vs this opponent ends.
            float since = now - in.lastHitTick;
            float left = Math.max(0f, timeout - since);
            float frac = left / timeout;
            String who = in.name + (model.incoming.size() > 1 ? " +" + (model.incoming.size() - 1) : "");
            y = barLine(tr, m, cx, y, "Combo'd x" + in.count + " (" + who + ") " + CombatModel.fmt2(left / 20f) + "s",
                    frac, lerpColor(RED, YELLOW, 1f - frac));
            if (melee) {
                if (since < delay) {
                    float rem = Math.max(0f, delay - since);
                    float f = rem / delay;
                    barLine(tr, m, cx, y, "Hit again " + CombatModel.fmt2(rem / 20f) + "s",
                            f, lerpColor(YELLOW, GREEN, 1f - f));
                } else {
                    line(tr, m, cx, y, TICK + " Hit ready", GREEN);
                }
            }
            return;
        }

        if (model.outTarget != null) {
            // You are comboing someone: show your streak and when your next hit is allowed.
            float since = now - model.outLast;
            float left = Math.max(0f, timeout - since);
            float frac = left / timeout;
            y = barLine(tr, m, cx, y, "Your combo x" + model.outCount + " (" + model.outTarget + ") "
                    + CombatModel.fmt2(left / 20f) + "s", frac, lerpColor(YELLOW, GREEN, frac));
            if (melee) {
                if (since < delay) {
                    float rem = Math.max(0f, delay - since);
                    float f = rem / delay;
                    barLine(tr, m, cx, y, "Next hit " + CombatModel.fmt2(rem / 20f) + "s",
                            f, lerpColor(YELLOW, GREEN, 1f - f));
                } else {
                    line(tr, m, cx, y, TICK + " Swing now", GREEN);
                }
            }
            return;
        }

        // Not in a fight: plain attack-cooldown readout (vanilla charge), as in 1.0.x.
        if (!melee) return;
        if (!alwaysShowAttack && now - model.lastSwingTick >= 60f) return;
        float c = p.getAttackCooldownProgress(td);
        if (c >= 0.999f) {
            line(tr, m, cx, y, TICK + " Hit ready", GREEN);
        } else {
            float perTick = p.getAttackCooldownProgressPerTick();
            float secs = perTick > 0f ? (1f - c) * perTick / 20f : 0f;
            barLine(tr, m, cx, y, "Hit again " + CombatModel.fmt2(secs) + "s", c, lerpColor(YELLOW, GREEN, c));
        }
    }

    private int line(TextRenderer tr, MatrixStack m, int cx, int y, String text, int color) {
        tr.drawWithShadow(m, text, cx - tr.getWidth(text) / 2f, y, color);
        return y + 12;
    }

    private int barLine(TextRenderer tr, MatrixStack m, int cx, int y, String text, float frac, int color) {
        tr.drawWithShadow(m, text, cx - tr.getWidth(text) / 2f, y, color);
        int bw = 80;
        int bx = cx - bw / 2;
        int by = y + 10;
        DrawableHelper.fill(m, bx - 1, by - 1, bx + bw + 1, by + 5, 0xAA000000);
        int fw = Math.round(bw * Math.max(0f, Math.min(1f, frac)));
        if (fw > 0) DrawableHelper.fill(m, bx, by, bx + fw, by + 4, color);
        return y + 18;
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // =====================================================================================
    // Commands
    // =====================================================================================

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> d, CommandRegistryAccess access) {
        d.register(ClientCommandManager.literal("combo")
                .executes(c -> { help(); return 1; })
                .then(ClientCommandManager.literal("stats").executes(c -> { printStats(); return 1; }))
                .then(ClientCommandManager.literal("reset").executes(c -> {
                    model.resetStats();
                    logRow("RESET", "", "", "", "", "", "", clientTick);
                    msg("Stats reset (log file keeps running, a RESET row was added).");
                    return 1;
                }))
                .then(ClientCommandManager.literal("log").executes(c -> {
                    msg(logPath == null ? "(nothing logged yet)" : "Session log: " + logPath);
                    return 1;
                }))
                .then(boolCmd("hud", () -> hudEnabled, v -> hudEnabled = v, "HUD"))
                .then(boolCmd("always", () -> alwaysShowAttack, v -> alwaysShowAttack = v, "Attack bar always shown"))
                .then(boolCmd("pingcomp", () -> model.pingComp, v -> model.pingComp = v,
                        "Ping compensation (shortens the cue by ping/100 ticks)"))
                .then(boolCmd("confirm", () -> model.confirmHits, v -> model.confirmHits = v,
                        "Hit confirmation (count a swing only if the target flashes red)"))
                .then(intCmd("delay", 1, 40, () -> model.hitDelay, v -> model.hitDelay = v, "Hit delay", "ticks"))
                .then(intCmd("timeout", 5, 100, () -> model.comboTimeout, v -> model.comboTimeout = v, "Combo timeout", "ticks"))
                .then(intCmd("radius", 2, 8, () -> radius, v -> radius = v, "Attacker radius", "blocks"))
                .then(ClientCommandManager.literal("pos")
                        .executes(c -> { msg("HUD offset = " + hudX + ", " + hudY); return 1; })
                        .then(ClientCommandManager.literal("reset").executes(c -> {
                            hudX = 0; hudY = 0; saveConfig(); msg("HUD offset reset"); return 1;
                        }))
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer(-300, 300))
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer(-200, 200))
                                        .executes(c -> {
                                            hudX = IntegerArgumentType.getInteger(c, "x");
                                            hudY = IntegerArgumentType.getInteger(c, "y");
                                            saveConfig();
                                            msg("HUD offset = " + hudX + ", " + hudY);
                                            return 1;
                                        }))))
                .then(ClientCommandManager.literal("weapon")
                        .executes(c -> { listWeapons(); return 1; })
                        .then(ClientCommandManager.literal("list").executes(c -> { listWeapons(); return 1; }))
                        .then(ClientCommandManager.literal("add").executes(c -> {
                            String id = heldId();
                            if (id == null) { msg("Hold the item you want to add first."); return 0; }
                            weapons.remove("*");
                            weapons.add(id);
                            saveConfig();
                            msg("Added " + id);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("remove").executes(c -> {
                            String id = heldId();
                            if (id == null || !weapons.remove(id)) { msg("The held item is not in the list."); return 0; }
                            saveConfig();
                            msg("Removed " + id);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("any").executes(c -> {
                            weapons.clear();
                            weapons.add("*");
                            saveConfig();
                            msg("Any held item now counts as a melee weapon.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(c -> {
                            weapons.clear();
                            weapons.add(DEFAULT_WEAPON);
                            saveConfig();
                            msg("Weapon list reset to " + DEFAULT_WEAPON);
                            return 1;
                        }))));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> boolCmd(String name, BooleanSupplier get,
                                                                      Consumer<Boolean> set, String label) {
        return ClientCommandManager.literal(name)
                .executes(c -> { msg(label + ": " + onOff(get.getAsBoolean())); return 1; })
                .then(ClientCommandManager.argument("value", BoolArgumentType.bool()).executes(c -> {
                    set.accept(BoolArgumentType.getBool(c, "value"));
                    saveConfig();
                    msg(label + ": " + onOff(get.getAsBoolean()));
                    return 1;
                }));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> intCmd(String name, int min, int max, IntSupplier get,
                                                                     IntConsumer set, String label, String unit) {
        return ClientCommandManager.literal(name)
                .executes(c -> { msg(label + " = " + get.getAsInt() + " " + unit + " (" + CombatModel.fmt2(get.getAsInt() / 20f) + "s)"); return 1; })
                .then(ClientCommandManager.argument("value", IntegerArgumentType.integer(min, max)).executes(c -> {
                    set.accept(IntegerArgumentType.getInteger(c, "value"));
                    saveConfig();
                    msg(label + " = " + get.getAsInt() + " " + unit + " (" + CombatModel.fmt2(get.getAsInt() / 20f) + "s)");
                    return 1;
                }));
    }

    private String heldId() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return null;
        ItemStack s = mc.player.getMainHandStack();
        if (s == null || s.isEmpty()) return null;
        return Registries.ITEM.getId(s.getItem()).toString();
    }

    private void listWeapons() {
        msg("Melee items: " + (weapons.isEmpty() ? "(none)" : String.join(", ", weapons)));
        msg("/combo weapon add | remove | any | reset  (uses the item in your hand)");
    }

    private void help() {
        msg("Commands:");
        msg("/combo stats  - session summary");
        msg("/combo reset  - clear stats");
        msg("/combo log  - where the CSV is saved");
        msg("/combo hud <true|false>");
        msg("/combo always <true|false>  - keep the attack bar up whenever you hold a melee item");
        msg("/combo delay <ticks>  - hit stun / hit delay (now " + model.hitDelay + ")");
        msg("/combo timeout <ticks>  - combo ends after this long without a hit (now " + model.comboTimeout + ")");
        msg("/combo pingcomp <true|false>  - shorten cue by ping/100 ticks");
        msg("/combo confirm <true|false>  - count a swing only if the target flashes red");
        msg("/combo radius <blocks>  - how far away an attacker can be (now " + radius + ")");
        msg("/combo pos <x> <y> | pos reset  - move the HUD");
        msg("/combo weapon [add|remove|any|reset]  - which held items count as melee");
    }

    private void printStats() {
        for (String s : model.statsLines()) msg(s);
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    private void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) mc.player.sendMessage(Text.literal(PREFIX + s), false);
    }

    // =====================================================================================
    // Config (config/comboinfo.properties)
    // =====================================================================================

    private static Path baseDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private void loadConfig() {
        weapons.clear();
        weapons.add(DEFAULT_WEAPON);
        Path f = baseDir().resolve("comboinfo.properties");
        if (!Files.exists(f)) return;
        Properties pr = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            pr.load(in);
        } catch (Exception e) {
            return;
        }
        hudEnabled = Boolean.parseBoolean(pr.getProperty("hud", "true"));
        alwaysShowAttack = Boolean.parseBoolean(pr.getProperty("always", "false"));
        model.pingComp = Boolean.parseBoolean(pr.getProperty("pingcomp", "false"));
        model.confirmHits = Boolean.parseBoolean(pr.getProperty("confirm", "true"));
        model.hitDelay = clamp(parseInt(pr.getProperty("delay"), 12), 1, 40);
        model.comboTimeout = clamp(parseInt(pr.getProperty("timeout"), 30), 5, 100);
        radius = clamp(parseInt(pr.getProperty("radius"), 5), 2, 8);
        hudX = clamp(parseInt(pr.getProperty("hudx"), 0), -300, 300);
        hudY = clamp(parseInt(pr.getProperty("hudy"), 0), -200, 200);
        String w = pr.getProperty("weapons");
        if (w != null && !w.isBlank()) {
            weapons.clear();
            for (String part : w.split(",")) {
                String id = part.trim();
                if (!id.isEmpty()) weapons.add(id);
            }
            if (weapons.isEmpty()) weapons.add(DEFAULT_WEAPON);
        }
    }

    private void saveConfig() {
        Properties pr = new Properties();
        pr.setProperty("hud", String.valueOf(hudEnabled));
        pr.setProperty("always", String.valueOf(alwaysShowAttack));
        pr.setProperty("pingcomp", String.valueOf(model.pingComp));
        pr.setProperty("confirm", String.valueOf(model.confirmHits));
        pr.setProperty("delay", String.valueOf(model.hitDelay));
        pr.setProperty("timeout", String.valueOf(model.comboTimeout));
        pr.setProperty("radius", String.valueOf(radius));
        pr.setProperty("hudx", String.valueOf(hudX));
        pr.setProperty("hudy", String.valueOf(hudY));
        pr.setProperty("weapons", String.join(",", weapons));
        try {
            Path f = baseDir().resolve("comboinfo.properties");
            Files.createDirectories(f.getParent());
            try (OutputStream out = Files.newOutputStream(f)) {
                pr.store(out, "ComboInfo settings");
            }
        } catch (Exception ignored) {
            // settings just won't persist
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // =====================================================================================
    // CSV log (config/comboinfo/session-<timestamp>.csv)
    // =====================================================================================

    private void logRow(String event, String target, String charge, String damage,
                        String combo, String gap, String reaction, int tick) {
        try {
            if (log == null) {
                Path dir = baseDir().resolve("comboinfo");
                Files.createDirectories(dir);
                logPath = dir.resolve("session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
                log = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.write("t_ms,tick,event,target,charge,damage,combo_count,gap_ticks_or_combo_len,reaction_ms\n");
            }
            long ms = (System.nanoTime() - sessionNano) / 1_000_000L;
            log.write(ms + "," + tick + "," + csv(event) + "," + csv(target) + "," + csv(charge) + ","
                    + csv(damage) + "," + csv(combo) + "," + csv(gap) + "," + csv(reaction) + "\n");
            logDirty = true;
        } catch (Exception ignored) {
            // logging is best-effort
        }
    }

    private void flushLog() {
        try {
            if (log != null) log.flush();
        } catch (Exception ignored) {
            // best-effort
        }
        logDirty = false;
    }

    private void closeLog() {
        try {
            if (log != null) {
                log.flush();
                log.close();
            }
        } catch (Exception ignored) {
            // best-effort
        }
        log = null;
    }

    private static String csv(String s) {
        return s == null ? "" : s.replace(",", "_").replace("\n", " ");
    }
}
