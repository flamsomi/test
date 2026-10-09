package com.example.comboinfo;

import java.util.ArrayList;
import java.util.List;

/** Plain-Java tests (no JUnit needed): javac the two files, then `java ...CombatModelTest`. */
public final class CombatModelTest {
    static int failures = 0;
    static long fakeNano = 0;
    static final List<String> rows = new ArrayList<>();

    static void check(boolean ok, String msg) {
        if (!ok) { failures++; System.out.println("FAIL: " + msg); }
        else System.out.println("ok:   " + msg);
    }

    static CombatModel fresh() {
        rows.clear();
        fakeNano = 0;
        CombatModel m = new CombatModel(() -> fakeNano);
        m.setSink((e, t, c, d, co, g, r, tick) ->
                rows.add(tick + ":" + e + ":" + t + ":dmg=" + d + ":combo=" + co + ":gap=" + g + ":rx=" + r));
        return m;
    }

    static boolean hasRow(String prefix) {
        for (String r : rows) if (r.contains(prefix)) return true;
        return false;
    }

    /** Advance model ticks from a to b inclusive with constant hp. */
    static void run(CombatModel m, int a, int b, float hp) {
        for (int t = a; t <= b; t++) m.tick(t, hp);
    }

    public static void main(String[] args) {
        // 1. Defensive combo, cue timing, gap, and timeout end
        {
            CombatModel m = fresh();
            run(m, 1, 9, 20f);
            m.onHitTaken(10, "Bob"); m.tick(10, 18f);
            check(m.incoming.get("Bob").count == 1, "first hit starts combo x1");
            run(m, 11, 21, 18f);
            check(!m.incoming.get("Bob").cueFired, "no cue at 11 ticks after hit");
            m.tick(22, 18f);
            check(m.incoming.get("Bob").cueFired, "cue fires exactly 12 ticks after hit");
            check(hasRow("22:CUE_HIT_NOW:Bob"), "CUE_HIT_NOW row logged at tick 22");
            m.onHitTaken(22, "Bob"); m.tick(22, 16f);
            check(m.incoming.get("Bob").count == 2, "second hit makes combo x2");
            check(m.minGap == 12 && m.gapCount == 1, "gap of 12 ticks recorded");
            check(!m.incoming.get("Bob").cueFired, "cue re-armed after another hit");
            run(m, 23, 53, 16f);
            check(m.incoming.isEmpty(), "combo times out after >30 ticks");
            check(hasRow("COMBO_END:Bob") && hasRow("combo=2"), "COMBO_END row with length 2");
            check(hasRow("HIT_TAKEN:Bob:dmg=2.0"), "HIT_TAKEN row carries damage from HP delta");
        }
        // 2. Early swing vs reaction time
        {
            CombatModel m = fresh();
            m.onHitTaken(10, "Bob"); m.tick(10, 20f);
            run(m, 11, 14, 20f);
            m.onSwing(15, 1.0f, "Bob", true);
            check(m.earlySwings == 1, "swing before cue counts as early");
            run(m, 16, 22, 20f);                // cue fires at 22
            fakeNano = 0;                       // cue nano was 0
            fakeNano = 180_000_000L;            // 180 ms later
            m.onSwing(26, 1.0f, "Bob", true);
            check(m.reactionCount == 1 && m.reactionBestMs == 180, "reaction 180ms measured from cue");
            m.onSwing(28, 1.0f, "Bob", true);
            check(m.reactionCount == 1, "only first swing after cue counts as reaction");
        }
        // 3. Offensive streak with hit confirmation
        {
            CombatModel m = fresh();
            m.confirmHits = true;
            m.onSwing(50, 1.0f, "Bob", true);
            check(m.hitsLanded == 0, "swing alone is not a landed hit when confirm is on");
            m.onTargetHurt(52, "Bob");
            check(m.hitsLanded == 1 && "Bob".equals(m.outTarget) && m.outCount == 1, "hurt flash confirms hit, streak x1");
            run(m, 51, 61, 20f);
            check(!m.outCueFired, "no swing cue before 12 ticks");
            m.tick(62, 20f);
            check(m.outCueFired, "swing cue 12 ticks after confirmed hit");
            m.onSwing(63, 1.0f, "Bob", true); m.onTargetHurt(65, "Bob");
            check(m.outCount == 2 && m.minOwnGap == 13, "streak x2, gap 13 recorded");
            run(m, 64, 96, 20f);
            check(m.outTarget == null && hasRow("OUT_COMBO_END:Bob"), "own streak expires after timeout");
        }
        // 4. Late hurt flash does not count; third-party hurt does not count
        {
            CombatModel m = fresh();
            m.onSwing(10, 1.0f, "Bob", true);
            m.onTargetHurt(40, "Bob");
            check(m.hitsLanded == 0, "hurt flash far after swing is ignored");
            m.onSwing(60, 1.0f, "Bob", true);
            m.onTargetHurt(61, "Alice");
            check(m.hitsLanded == 0, "hurt flash on a different player is ignored");
        }
        // 5. Landing a hit ends their combo; being hit ends yours
        {
            CombatModel m = fresh();
            m.onHitTaken(10, "Bob"); m.tick(10, 20f);
            m.onSwing(22, 1.0f, "Bob", true); m.onTargetHurt(23, "Bob");
            check(m.incoming.isEmpty() && hasRow("COMBO_END:Bob"), "landing a hit ends Bob's combo on you");
            m.onHitTaken(35, "Bob");
            check(m.outTarget == null && hasRow("OUT_COMBO_END:Bob"), "Bob hitting you ends your streak on him");
        }
        // 6. Per-attacker tracking
        {
            CombatModel m = fresh();
            m.onHitTaken(10, "Bob"); m.tick(10, 20f);
            m.onHitTaken(14, "Alice"); m.tick(14, 18f);
            check(m.incoming.size() == 2, "two attackers tracked separately");
            check(m.primaryIncoming().name.equals("Alice"), "primary is most recent attacker");
            run(m, 15, 22, 18f);
            check(m.incoming.get("Bob").cueFired && !m.incoming.get("Alice").cueFired,
                    "Bob's stun ends at 22 while Alice's is still running");
        }
        // 7. Ping compensation and confirm window scale
        {
            CombatModel m = fresh();
            m.pingMs = 250;
            check(m.effDelay() == 12, "ping comp off by default: delay stays 12");
            m.pingComp = true;
            check(m.effDelay() == 10, "250ms ping with comp on: 12 - 2 = 10 ticks");
            m.pingMs = 5000;
            check(m.effDelay() >= 1, "delay never drops below 1 tick");
            check(m.confirmWindow() == 13, "confirm window grows with ping but is capped");
        }
        // 8. confirm off: swing counts immediately
        {
            CombatModel m = fresh();
            m.confirmHits = false;
            m.onSwing(5, 0.5f, "Bob", true);
            check(m.hitsLanded == 1, "confirm off: swing at a player counts as landed");
        }
        // 9. Non-player targets and air
        {
            CombatModel m = fresh();
            m.onSwing(5, 1.0f, "Zombie", false);
            m.onSwing(30, 0.3f, null, false);
            check(m.swingsAtOther == 1 && m.swingsAtAir == 1 && m.hitsLanded == 0, "mob/air swings never create combos");
            check(m.statsLines().size() > 4, "stats print without crashing");
        }
        // 10. Reset
        {
            CombatModel m = fresh();
            m.onHitTaken(1, "Bob"); m.onSwing(2, 1f, "Bob", true);
            m.resetStats();
            check(m.hitsTaken == 0 && m.swings == 0 && m.incoming.isEmpty() && m.minGap == Integer.MAX_VALUE, "resetStats clears everything");
        }

        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        if (failures != 0) System.exit(1);
    }
}
