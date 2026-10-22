package com.hydrino;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Flies by elytra along chunk trails found by chunk-finder, boosts with fireworks, lands to repair with XP bottles. */
public class ElytraBot extends Module {
    private enum State { TAKEOFF, FLY, LAND, MEND }

    SettingGroup gf = settings.getDefaultGroup(), gm = settings.createGroup("XP Mending");
    Setting<Integer> cruiseO = Features.num(gf, "overworld-y", 220, 70, 320), cruiseN = Features.num(gf, "nether-y", 135, 0, 320),
        fwCd = Features.num(gf, "firework-cooldown", 25, 5, 100), noTarget = Features.num(gf, "no-target-seconds", 45, 5, 600),
        slot = Features.num(gf, "utility-hotbar-slot", 9, 1, 9);
    Setting<Double> minSpeed = Features.dbl(gf, "min-speed", 1.1, 0.3, 2.0), turn = Features.dbl(gf, "turn-rate", 12, 2, 40);
    Setting<Boolean> straight = Features.bool(gf, "continue-straight", true), useOld = Features.bool(gf, "follow-old-chunks", true), useStash = Features.bool(gf, "follow-chunk-finder", false);
    Setting<Boolean> mend = Features.bool(gm, "xp-mending", true);
    Setting<Integer> landBelow = Features.num(gm, "land-below-percent", 20, 1, 90), mendTo = Features.num(gm, "mend-to-percent", 90, 10, 100),
        throwDelay = Features.num(gm, "throw-delay-ticks", 3, 1, 20);

    State state = State.TAKEOFF;
    int airTicks, fwTimer, throwTimer, noTargetTicks, idleThrows, lastPct;
    boolean noMend, stopAfterLand;
    String stopReason = "";
    float holdYaw;
    Set<Long> visited = new HashSet<>();

    public ElytraBot() { super(HydrinoAddon.CATEGORY, "elytra-bot", "Auto elytra: follows chunk-finder trails, fireworks boost, lands and repairs with XP bottles."); }

    @Override public void onActivate() {
        visited.clear(); state = State.TAKEOFF; airTicks = 0; fwTimer = 0; noTargetTicks = 0; noMend = false; stopAfterLand = false;
        if (Features.ready()) holdYaw = mc.player.getYaw();
        if (useOld.get()) { OldChunks oc = Modules.get().get(OldChunks.class); if (oc != null && !oc.isActive()) oc.toggle(); }
        if (useStash.get()) { Features.ChunkFinder cf = Modules.get().get(Features.ChunkFinder.class); if (cf != null && !cf.isActive()) cf.toggle(); }
    }

    @Override public void onDeactivate() { release(); }

    private void release() {
        mc.options.jumpKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);
    }

    private void finish() { warning("Elytra bot stopped: %s", stopReason.isEmpty() ? "done" : stopReason); toggle(); }

    // ---------- main loop ----------
    @EventHandler private void onTick(TickEvent.Pre e) {
        if (!Features.ready()) return;
        if (!mc.player.isAlive()) { toggle(); return; }
        if (fwTimer > 0) fwTimer--;
        if (!ensureElytra()) return;

        int pct = Features.pct(mc.player.getEquippedStack(EquipmentSlot.CHEST));
        boolean ground = mc.player.isOnGround();

        if (mend.get() && !noMend && pct <= landBelow.get() && state != State.MEND && state != State.LAND && InvUtils.find(Items.EXPERIENCE_BOTTLE).found()) {
            state = ground ? State.MEND : State.LAND;
            lastPct = pct; idleThrows = 0;
        }

        switch (state) {
            case TAKEOFF -> {
                if (ground) { airTicks = 0; mc.options.jumpKey.setPressed(true); }
                else {
                    airTicks++;
                    steer(false);
                    mc.options.jumpKey.setPressed(airTicks % 2 == 0);   // re-press in air to open the elytra
                    if (airTicks >= 10) { mc.options.jumpKey.setPressed(false); state = State.FLY; }
                }
            }
            case FLY -> {
                mc.options.jumpKey.setPressed(false);
                if (ground) { state = State.TAKEOFF; return; }
                steer(false);
                if (mc.player.getVelocity().length() < minSpeed.get() && fwTimer == 0) boost();
            }
            case LAND -> {
                mc.options.jumpKey.setPressed(false);
                if (ground) { if (stopAfterLand) { finish(); return; } state = State.MEND; lastPct = pct; idleThrows = 0; }
                else steer(true);
            }
            case MEND -> {
                mc.options.jumpKey.setPressed(false);
                if (!ground) { state = State.LAND; return; }
                if (pct >= mendTo.get()) { state = State.TAKEOFF; return; }
                if (!InvUtils.find(Items.EXPERIENCE_BOTTLE).found()) { warning("Out of XP bottles."); state = State.TAKEOFF; noMend = true; return; }
                if (throwTimer-- > 0) return;
                throwTimer = throwDelay.get();
                if (pct > lastPct) idleThrows = 0; else if (++idleThrows > 40) { noMend = true; warning("Elytra isn't repairing (no Mending?) - skipping XP."); state = State.TAKEOFF; return; }
                lastPct = pct;
                FindItemResult b = InvUtils.find(Items.EXPERIENCE_BOTTLE);
                if (!b.isHotbar() && !b.isOffhand()) { InvUtils.move().from(b.slot()).toHotbar(slot.get() - 1); return; }
                Rotations.rotate(mc.player.getYaw(), 90, 50, () -> use(b));
            }
        }
    }

    // ---------- helpers ----------
    private boolean ensureElytra() {
        ItemStack c = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (c.isOf(Items.ELYTRA) && c.getDamage() < c.getMaxDamage() - 1) return true;
        FindItemResult r = InvUtils.find(s -> s.isOf(Items.ELYTRA) && s.getDamage() < s.getMaxDamage() - 1);
        if (!r.found()) { error("No usable elytra."); toggle(); return false; }
        InvUtils.move().from(r.slot()).toArmor(2);
        return false; // wait a tick for the swap
    }

    private void use(FindItemResult r) {
        if (r.isOffhand()) { mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND); return; }
        InvUtils.swap(r.slot(), true);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        InvUtils.swapBack();
    }

    private void boost() {
        FindItemResult f = InvUtils.find(Items.FIREWORK_ROCKET);
        if (!f.found()) { stopAfterLand = true; stopReason = "out of fireworks"; state = State.LAND; return; }
        if (!f.isHotbar() && !f.isOffhand()) { InvUtils.move().from(f.slot()).toHotbar(slot.get() - 1); fwTimer = 3; return; }
        use(f);
        fwTimer = fwCd.get();
    }

    double[] best; double bestScore;

    private void consider(long key, int cx, int cz, double px, double pz, double fx, double fz, double fl) {
        if (visited.contains(key)) return;
        double x = cx * 16 + 8, z = cz * 16 + 8, d = Math.hypot(x - px, z - pz);
        if (d < 40) { visited.add(key); return; }
        if (d > 700) return;
        double dot = ((x - px) * fx + (z - pz) * fz) / (d * fl);
        if (dot < 0) return;                       // never turn back
        double score = d * (1.6 - dot);            // prefer chunks straight ahead
        if (score < bestScore) { bestScore = score; best = new double[]{x, z}; }
    }

    /** Next old chunk (or stash chunk) ahead of us along the trail. */
    private double[] target() {
        double px = mc.player.getX(), pz = mc.player.getZ();
        Vec3d v = mc.player.getVelocity();
        double fx = v.x, fz = v.z, fl = Math.hypot(fx, fz);
        if (fl < 0.1) { fx = -Math.sin(Math.toRadians(holdYaw)); fz = Math.cos(Math.toRadians(holdYaw)); fl = 1; }
        best = null; bestScore = Double.MAX_VALUE;
        OldChunks oc = Modules.get().get(OldChunks.class);
        if (useOld.get() && oc != null) for (long k : oc.old) consider(k, (int) (k >> 32), (int) k, px, pz, fx, fz, fl);
        Features.ChunkFinder cf = Modules.get().get(Features.ChunkFinder.class);
        if (useStash.get() && cf != null) for (var en : cf.found.entrySet()) consider(en.getKey(), en.getValue()[0], en.getValue()[1], px, pz, fx, fz, fl);
        return best;
    }

    private int groundBelow() {
        BlockPos p = mc.player.getBlockPos();
        for (int d = 1; d <= 12; d++) if (!mc.world.getBlockState(p.down(d)).isAir()) return d;
        return 99;
    }

    private void steer(boolean landing) {
        double[] t = target();
        if (t != null) { holdYaw = (float) Rotations.getYaw(new Vec3d(t[0], mc.player.getY(), t[1])); noTargetTicks = 0; }
        else if (state != State.LAND && ++noTargetTicks > 20) {
            if (!straight.get() || noTargetTicks > noTarget.get() * 20) { stopAfterLand = true; stopReason = "no more chunks to follow"; state = State.LAND; }
        }
        float r = turn.get().floatValue();
        mc.player.setYaw(mc.player.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(holdYaw - mc.player.getYaw()), -r, r));

        float pitchGoal;
        if (landing) pitchGoal = groundBelow() <= 5 ? -12f : 14f;
        else {
            double cruise = Features.dim().equals("the_nether") ? cruiseN.get() : cruiseO.get();
            pitchGoal = (float) -MathHelper.clamp(cruise - mc.player.getY(), -25, 45);
        }
        mc.player.setPitch(mc.player.getPitch() + MathHelper.clamp(pitchGoal - mc.player.getPitch(), -r, r));
    }
}
