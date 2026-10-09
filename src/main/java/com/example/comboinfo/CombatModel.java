package com.example.comboinfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Pure-Java combat bookkeeping for ComboInfo. No Minecraft imports on purpose,
 * so it can be unit-tested with plain javac/java (see CombatModelTest).
 *
 * Model of GTM's hit system (patch 3.4.0, 14 Jun 2026): after player A lands a hit
 * on B, B cannot damage A for ~12 ticks ("damage stun"), and A's own weapon has a
 * ~12 tick delay between hits. B can still damage other players. So the stun is
 * tracked PER OPPONENT, in both directions:
 *   incoming: opponents who hit you   -> you are stunned vs them until hitDelay passes
 *   outgoing: your streak on someone  -> they are stunned vs you until hitDelay passes
 */
final class CombatModel {

    /** Receives one CSV row per event. */
    interface Sink {
        void row(String event, String target, String charge, String damage,
                 String combo, String gap, String reaction, int tick);
    }

    /** An opponent who is hitting you. */
    static final class Incoming {
        final String name;
        int count;
        final int startTick;
        int lastHitTick;
        boolean cueFired;
        boolean reacted;
        long cueNano;

        Incoming(String name, int tick) {
            this.name = name;
            this.startTick = tick;
            this.lastHitTick = tick;
        }
    }

    // ---- settings (owned here because they change the model's behaviour) ----
    int hitDelay = 12;          // GTM: 12 ticks (patch 3.4.0). Server-tunable, so keep it configurable.
    int comboTimeout = 30;      // a streak ends after this many ticks without a hit
    boolean pingComp = false;   // GTM: "1 tick advantage per 100ms of ping" (interpretation, opt-in)
    boolean confirmHits = true; // count a swing as a landed hit only if the target visibly got hurt
    int pingMs = 0;

    // ---- live state ----
    final Map<String, Incoming> incoming = new LinkedHashMap<>();
    String outTarget = null;
    int outCount = 0;
    int outStart = 0;
    int outLast = 0;
    boolean outCueFired = false;
    int lastSwingTick = -1000;

    private String pendTarget = null;   // swing waiting to be confirmed by the target's hurt flash
    private int pendSwingTick = -1000;
    private float pendCharge = 0f;

    private boolean dmgPending = false; // HIT_TAKEN row waits one tick so we can include damage
    private int dmgTick;
    private float dmgHpBefore;
    private String dmgAttacker;
    private int dmgCombo;
    private int dmgGap;
    private float lastHp = -1f;

    // ---- stats ----
    int swings, swingsAtPlayer, swingsAtOther, swingsAtAir, fullChargeSwings;
    double sumCharge;
    int earlySwings;
    int hitsTaken, combosTaken, maxCombo;
    int hitsLanded, combosLanded, maxOwnCombo;
    int otherDamageEvents;
    long gapSum;  int gapCount;  int minGap = Integer.MAX_VALUE;
    long ownGapSum; int ownGapCount; int minOwnGap = Integer.MAX_VALUE;
    int reactionCount; long reactionSumMs; long reactionBestMs = Long.MAX_VALUE;

    private final LongSupplier nano;
    private Sink sink = (e, t, c, d, co, g, r, tick) -> { };

    CombatModel(LongSupplier nano) {
        this.nano = nano;
    }

    void setSink(Sink s) {
        this.sink = s;
    }

    // ------------------------------------------------------------------ settings helpers

    /** Hit delay actually used for cues. Optionally shortened by ping (1 tick per 100 ms). */
    int effDelay() {
        int d = hitDelay;
        if (pingComp) d -= Math.max(0, pingMs) / 100;
        return Math.max(1, d);
    }

    /** How long after a swing a target's hurt flash still counts as "that was my hit". */
    int confirmWindow() {
        return 3 + Math.min(10, Math.max(0, pingMs) / 50);
    }

    // ------------------------------------------------------------------ events

    void onHitTaken(int tick, String attacker) {
        Incoming in = incoming.get(attacker);
        int gap = -1;
        if (in == null) {
            in = new Incoming(attacker, tick);
            incoming.put(attacker, in);
            combosTaken++;
        } else {
            gap = tick - in.lastHitTick;
            gapSum += gap;
            gapCount++;
            minGap = Math.min(minGap, gap);
        }
        in.count++;
        in.lastHitTick = tick;
        in.cueFired = false;
        in.reacted = false;
        hitsTaken++;
        maxCombo = Math.max(maxCombo, in.count);

        // They just hit you, so your streak on them is over (you were stunned vs them).
        if (attacker.equals(outTarget)) endOutgoing(tick);

        dmgPending = true;
        dmgTick = tick;
        dmgHpBefore = lastHp;
        dmgAttacker = attacker;
        dmgCombo = in.count;
        dmgGap = gap;
    }

    void onOtherDamage(int tick) {
        otherDamageEvents++;
        sink.row("DAMAGE_OTHER", "", "", "", "", "", "", tick);
    }

    /**
     * Your attack cooldown dropped while your hand swung: you just swung.
     * @param targetName name of the living entity under the crosshair, or null for air
     * @param targetIsPlayer true if that entity is a player (only players take part in combos)
     */
    void onSwing(int tick, float charge, String targetName, boolean targetIsPlayer) {
        swings++;
        sumCharge += charge;
        if (charge >= 0.9f) fullChargeSwings++;
        lastSwingTick = tick;

        if (targetName == null) {
            swingsAtAir++;
            sink.row("SWING_MISS", "", fmt2(charge), "", outTarget != null ? String.valueOf(outCount) : "", "", "", tick);
            return;
        }
        if (!targetIsPlayer) {
            swingsAtOther++;
            sink.row("SWING_HIT", targetName, fmt2(charge), "", "", "", "", tick);
            return;
        }

        swingsAtPlayer++;
        long reaction = -1;
        Incoming in = incoming.get(targetName);
        if (in != null) {
            if (!in.cueFired) {
                earlySwings++; // swung at them while still stunned vs them
            } else if (!in.reacted) {
                in.reacted = true;
                reaction = (nano.getAsLong() - in.cueNano) / 1_000_000L;
                reactionCount++;
                reactionSumMs += reaction;
                reactionBestMs = Math.min(reactionBestMs, reaction);
            }
        }
        sink.row("SWING_HIT", targetName, fmt2(charge), "",
                in != null ? String.valueOf(in.count) : "", "",
                reaction >= 0 ? String.valueOf(reaction) : "", tick);

        if (confirmHits) {
            pendTarget = targetName;
            pendSwingTick = tick;
            pendCharge = charge;
        } else {
            registerLanded(tick, targetName, charge);
        }
    }

    /** A nearby player's hurt flash just started (their hurtTime went up). */
    void onTargetHurt(int tick, String name) {
        if (pendTarget != null && pendTarget.equals(name)
                && tick >= pendSwingTick && tick - pendSwingTick <= confirmWindow()) {
            // Anchor on the click, not on when the hurt flash arrived (that is one round trip later).
            registerLanded(pendSwingTick, name, pendCharge);
            pendTarget = null;
        }
    }

    /** @param tick the tick you clicked (the server measures your click cadence, not the flash) */
    private void registerLanded(int tick, String name, float charge) {
        hitsLanded++;
        int gap = -1;
        if (name.equals(outTarget)) {
            gap = tick - outLast;
            ownGapSum += gap;
            ownGapCount++;
            minOwnGap = Math.min(minOwnGap, gap);
            outCount++;
        } else {
            if (outTarget != null) endOutgoing(tick);
            outTarget = name;
            outCount = 1;
            outStart = tick;
            combosLanded++;
        }
        outLast = tick;
        outCueFired = false;
        maxOwnCombo = Math.max(maxOwnCombo, outCount);

        // Landing a hit on them breaks their streak on you.
        Incoming in = incoming.remove(name);
        if (in != null) endIncoming(in, tick);

        sink.row("HIT_LANDED", name, fmt2(charge), "", String.valueOf(outCount),
                gap >= 0 ? String.valueOf(gap) : "", "", tick);
    }

    /** Call once per client tick, after the events for that tick. */
    void tick(int tick, float hp) {
        if (dmgPending && tick > dmgTick) {
            float dmg = dmgHpBefore >= 0 ? Math.max(0f, dmgHpBefore - hp) : 0f;
            sink.row("HIT_TAKEN", dmgAttacker, "", fmt1(dmg), String.valueOf(dmgCombo),
                    dmgGap >= 0 ? String.valueOf(dmgGap) : "", "", dmgTick);
            dmgPending = false;
        }

        Iterator<Incoming> it = incoming.values().iterator();
        while (it.hasNext()) {
            Incoming in = it.next();
            int since = tick - in.lastHitTick;
            if (since > comboTimeout) {
                endIncoming(in, tick);
                it.remove();
            } else if (!in.cueFired && since >= effDelay()) {
                in.cueFired = true;
                in.cueNano = nano.getAsLong();
                sink.row("CUE_HIT_NOW", in.name, "", "", String.valueOf(in.count), "", "", tick);
            }
        }

        if (outTarget != null) {
            int since = tick - outLast;
            if (since > comboTimeout) {
                endOutgoing(tick);
            } else if (!outCueFired && since >= effDelay()) {
                outCueFired = true;
                sink.row("CUE_SWING_NOW", outTarget, "", "", String.valueOf(outCount), "", "", tick);
            }
        }

        if (pendTarget != null && tick - pendSwingTick > confirmWindow()) pendTarget = null;
        lastHp = hp;
    }

    private void endIncoming(Incoming in, int tick) {
        sink.row("COMBO_END", in.name, "", "", String.valueOf(in.count),
                String.valueOf(in.lastHitTick - in.startTick), "", tick);
    }

    private void endOutgoing(int tick) {
        if (outTarget == null) return;
        sink.row("OUT_COMBO_END", outTarget, "", "", String.valueOf(outCount),
                String.valueOf(outLast - outStart), "", tick);
        outTarget = null;
        outCount = 0;
        outCueFired = false;
    }

    // ------------------------------------------------------------------ queries for the HUD

    /** The opponent who hit you most recently, or null. */
    Incoming primaryIncoming() {
        Incoming best = null;
        for (Incoming i : incoming.values()) {
            if (best == null || i.lastHitTick > best.lastHitTick) best = i;
        }
        return best;
    }

    // ------------------------------------------------------------------ resets

    /** New player object / disconnect: forget live fights but keep stats. */
    void resetTransient() {
        incoming.clear();
        outTarget = null;
        outCount = 0;
        outCueFired = false;
        pendTarget = null;
        dmgPending = false;
        lastHp = -1f;
    }

    void resetStats() {
        swings = swingsAtPlayer = swingsAtOther = swingsAtAir = fullChargeSwings = 0;
        sumCharge = 0;
        earlySwings = 0;
        hitsTaken = combosTaken = maxCombo = 0;
        hitsLanded = combosLanded = maxOwnCombo = 0;
        otherDamageEvents = 0;
        gapSum = 0; gapCount = 0; minGap = Integer.MAX_VALUE;
        ownGapSum = 0; ownGapCount = 0; minOwnGap = Integer.MAX_VALUE;
        reactionCount = 0; reactionSumMs = 0; reactionBestMs = Long.MAX_VALUE;
        resetTransient();
    }

    // ------------------------------------------------------------------ stats text

    List<String> statsLines() {
        List<String> out = new ArrayList<>();
        out.add("---- Combo Stats ----");
        out.add("Swings: " + swings + "  (at players " + swingsAtPlayer + " / other " + swingsAtOther
                + " / air " + swingsAtAir + ")");
        if (swings > 0) {
            out.add("Avg charge at swing: " + fmt2((float) (sumCharge / swings))
                    + "   Full-charge swings: " + Math.round(100.0 * fullChargeSwings / swings) + "%");
        }
        if (confirmHits && swingsAtPlayer > 0) {
            out.add("Hits landed: " + hitsLanded + "  (" + Math.round(100.0 * hitsLanded / swingsAtPlayer)
                    + "% of swings at players)   Longest streak: " + maxOwnCombo);
        } else {
            out.add("Hits landed: " + hitsLanded + "   Longest streak: " + maxOwnCombo);
        }
        if (ownGapCount > 0) {
            float avg = (float) ownGapSum / ownGapCount;
            out.add("Gap between your hits: avg " + fmt1(avg) + "t (" + fmt2(avg / 20f) + "s), min " + minOwnGap + "t");
        }
        out.add("Hits taken from players: " + hitsTaken + "   Combos: " + combosTaken
                + "   Longest combo: " + maxCombo);
        if (hitsTaken > 0 || hitsLanded > 0) {
            out.add("Trade: landed " + hitsLanded + " vs taken " + hitsTaken);
        }
        if (gapCount > 0) {
            float avg = (float) gapSum / gapCount;
            out.add("Gap between hits taken: avg " + fmt1(avg) + "t (" + fmt2(avg / 20f) + "s), min " + minGap + "t");
        }
        out.add("Swings at an opponent before the cue (too early): " + earlySwings);
        if (reactionCount > 0) {
            out.add("Hit-back reaction after cue: avg " + (reactionSumMs / reactionCount) + "ms, best "
                    + reactionBestMs + "ms (n=" + reactionCount + ", +/-50ms tick resolution)");
        }
        out.add("Other damage ignored (not from a player): " + otherDamageEvents);
        out.add("Settings: hit delay " + hitDelay + "t (effective " + effDelay() + "t), combo timeout "
                + comboTimeout + "t, ping comp " + (pingComp ? "on" : "off")
                + ", hit confirm " + (confirmHits ? "on" : "off"));
        return out;
    }

    static String fmt1(float f) { return String.format(Locale.ROOT, "%.1f", f); }
    static String fmt2(float f) { return String.format(Locale.ROOT, "%.2f", f); }
}
