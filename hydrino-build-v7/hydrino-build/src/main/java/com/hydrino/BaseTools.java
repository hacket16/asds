package com.hydrino;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Base-building tools: area miner, tunnel digger, area filler (bunkers/walls/floors), auto light, portal builder, bridge. */
public class BaseTools {
    public enum Area { Forward, Around, Corners }
    public enum Shape { Fill, Hollow, Walls, Floor, Roof }

    // ---------- shared helpers ----------
    static List<BlockPos> region(Area a, int len, int wid, int up, int down, int radius, BlockPos c1, BlockPos c2) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos o = mc.player.getBlockPos();
        switch (a) {
            case Forward -> {
                Direction f = mc.player.getHorizontalFacing();
                int fx = f.getOffsetX(), fz = f.getOffsetZ(), lx = -fz, lz = fx;
                for (int l = 1; l <= len; l++) for (int w = -(wid / 2); w < wid - (wid / 2); w++) for (int h = -down; h < up; h++)
                    out.add(o.add(fx * l + lx * w, h, fz * l + lz * w));
            }
            case Around -> {
                for (int x = -radius; x <= radius; x++) for (int y = -down; y < up; y++) for (int z = -radius; z <= radius; z++) out.add(o.add(x, y, z));
            }
            case Corners -> {
                for (int x = Math.min(c1.getX(), c2.getX()); x <= Math.max(c1.getX(), c2.getX()); x++)
                    for (int y = Math.min(c1.getY(), c2.getY()); y <= Math.max(c1.getY(), c2.getY()); y++)
                        for (int z = Math.min(c1.getZ(), c2.getZ()); z <= Math.max(c1.getZ(), c2.getZ()); z++) out.add(new BlockPos(x, y, z));
            }
        }
        if (out.size() > 60000) out = new ArrayList<>(out.subList(0, 60000));
        return out;
    }

    static boolean shapeOk(Shape s, BlockPos p, BlockPos mn, BlockPos mx) {
        boolean ex = p.getX() == mn.getX() || p.getX() == mx.getX(), ez = p.getZ() == mn.getZ() || p.getZ() == mx.getZ(), ey = p.getY() == mn.getY() || p.getY() == mx.getY();
        return switch (s) { case Fill -> true; case Hollow -> ex || ez || ey; case Walls -> ex || ez; case Floor -> p.getY() == mn.getY(); case Roof -> p.getY() == mx.getY(); };
    }

    static BlockPos[] bounds(List<BlockPos> l) {
        int x1 = Integer.MAX_VALUE, y1 = x1, z1 = x1, x2 = Integer.MIN_VALUE, y2 = x2, z2 = x2;
        for (BlockPos p : l) { x1 = Math.min(x1, p.getX()); y1 = Math.min(y1, p.getY()); z1 = Math.min(z1, p.getZ()); x2 = Math.max(x2, p.getX()); y2 = Math.max(y2, p.getY()); z2 = Math.max(z2, p.getZ()); }
        return l.isEmpty() ? new BlockPos[]{BlockPos.ORIGIN, BlockPos.ORIGIN} : new BlockPos[]{new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2)};
    }

    static void walkTo(BlockPos p, boolean jump) {
        mc.player.setYaw((float) Rotations.getYaw(Vec3d.ofCenter(p)));
        mc.options.forwardKey.setPressed(true);
        mc.options.jumpKey.setPressed(jump && mc.player.horizontalCollision && mc.player.isOnGround());
    }
    static void stopWalk() { mc.options.forwardKey.setPressed(false); mc.options.jumpKey.setPressed(false); }

    static void mine(BlockPos p, boolean rotate, boolean silent) {
        FindItemResult t = InvUtils.findFastestTool(mc.world.getBlockState(p));
        if (t.found() && t.isHotbar()) InvUtils.swap(t.slot(), false);
        Runnable r = () -> BlockUtils.breakBlock(p, true);
        if (rotate) HydrinoAddon.Aim.at(p, silent, r); else r.run();
    }

    static void place(BlockPos p, FindItemResult item, boolean rotate, boolean silent) {
        Runnable r = () -> BlockUtils.place(p, item, false, 0);
        if (rotate) HydrinoAddon.Aim.at(p, silent, r); else r.run();
    }

    static boolean breakable(BlockPos p, boolean avoidFluids, boolean skipContainers) {
        BlockState s = mc.world.getBlockState(p);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.getHardness(mc.world, p) < 0) return false;
        Block b = s.getBlock();
        if (skipContainers && (b instanceof ChestBlock || b instanceof ShulkerBoxBlock || b instanceof BarrelBlock || b instanceof EnderChestBlock)) return false;
        if (avoidFluids) for (Direction d : Direction.values()) if (!mc.world.getFluidState(p.offset(d)).isEmpty()) return false;
        return true;
    }

    // ---------- 1. Area Miner ----------
    public static class AreaMiner extends Module {
        SettingGroup ga = settings.getDefaultGroup(), gb = settings.createGroup("Behavior");
        Setting<Area> area = ga.add(new EnumSetting.Builder<Area>().name("area").description("Forward = box in front of you, Around = box centered on you, Corners = two coordinates.").defaultValue(Area.Forward).build());
        Setting<Integer> len = Features.num(ga, "length", 8, 1, 64), wid = Features.num(ga, "width", 5, 1, 64), up = Features.num(ga, "height", 3, 1, 64),
            down = Features.num(ga, "depth", 0, 0, 64), rad = Features.num(ga, "radius", 3, 1, 16);
        Setting<BlockPos> c1 = ga.add(new BlockPosSetting.Builder().name("corner-1").defaultValue(BlockPos.ORIGIN).visible(() -> area.get() == Area.Corners).build()),
            c2 = ga.add(new BlockPosSetting.Builder().name("corner-2").defaultValue(BlockPos.ORIGIN).visible(() -> area.get() == Area.Corners).build());
        Setting<Double> range = Features.dbl(gb, "range", 4.5, 1, 6);
        Setting<Boolean> rotate = Features.bool(gb, "rotate", true), silent = Features.bool(gb, "silent-aim", true), walk = Features.bool(gb, "auto-walk", true),
            avoidFluids = Features.bool(gb, "avoid-fluids", true), skipCont = Features.bool(gb, "skip-containers", true), onlyList = Features.bool(gb, "use-only-list", false);
        Setting<List<Block>> only = gb.add(new BlockListSetting.Builder().name("only-mine").defaultValue().visible(onlyList::get).build());
        Setting<SettingColor> col = Features.color(gb, "color", 255, 120, 0);

        List<BlockPos> todo = new ArrayList<>();
        BlockPos mn = BlockPos.ORIGIN, mx = BlockPos.ORIGIN, current;
        int refresh;
        public AreaMiner() { super(HydrinoAddon.CATEGORY, "area-miner", "Clears a box top-down: in front of you, around you, or between two corners."); }

        private boolean ok(BlockPos p) { return breakable(p, avoidFluids.get(), skipCont.get()) && (!onlyList.get() || only.get().contains(mc.world.getBlockState(p).getBlock())); }

        @Override public void onActivate() {
            if (!Features.ready()) return;
            todo = region(area.get(), len.get(), wid.get(), up.get(), down.get(), rad.get(), c1.get(), c2.get());
            BlockPos[] b = bounds(todo); mn = b[0]; mx = b[1];
            BlockPos o = mc.player.getBlockPos();
            todo.sort(Comparator.<BlockPos>comparingInt(p -> -p.getY()).thenComparingDouble(p -> p.getSquaredDistance(o)));
            current = null; refresh = 0;
            info("Area miner: %d blocks queued.", todo.size());
        }
        @Override public void onDeactivate() { stopWalk(); todo.clear(); }

        @EventHandler private void onTick(TickEvent.Pre e) {
            if (!Features.ready()) return;
            if (refresh-- <= 0) { refresh = 10; todo.removeIf(p -> !ok(p)); }
            if (todo.isEmpty()) { stopWalk(); info("Area cleared."); toggle(); return; }
            Vec3d eye = mc.player.getEyePos();
            double r2 = range.get() * range.get();
            BlockPos pick = null;
            if (current != null && todo.contains(current) && current.getSquaredDistance(eye) <= r2 && ok(current)) pick = current;
            else for (BlockPos p : todo) {
                if (p.getSquaredDistance(eye) > r2) continue;
                if (pick == null || p.getY() > pick.getY() || (p.getY() == pick.getY() && p.getSquaredDistance(eye) < pick.getSquaredDistance(eye))) pick = p;
            }
            if (pick != null) { current = pick; stopWalk(); mine(pick, rotate.get(), silent.get()); }
            else if (walk.get()) {
                BlockPos near = todo.get(0);
                for (BlockPos p : todo) if (p.getSquaredDistance(eye) < near.getSquaredDistance(eye)) near = p;
                walkTo(near, true);
            }
        }
        @EventHandler private void onRender(Render3DEvent e) {
            if (todo.isEmpty()) return;
            e.renderer.box(mn.getX(), mn.getY(), mn.getZ(), mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1, new SettingColor(col.get().r, col.get().g, col.get().b, 15), col.get(), ShapeMode.Both, 0);
            if (current != null) e.renderer.box(current, new SettingColor(255, 0, 0, 60), new SettingColor(255, 0, 0, 220), ShapeMode.Both, 0);
        }
    }

    // ---------- 2. Auto Tunnel ----------
    public static class AutoTunnel extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Integer> wid = Features.num(g, "width", 3, 1, 9), hei = Features.num(g, "height", 3, 2, 9);
        Setting<Double> range = Features.dbl(g, "range", 4.5, 1, 6);
        Setting<Boolean> rotate = Features.bool(g, "rotate", true), silent = Features.bool(g, "silent-aim", true), stopLava = Features.bool(g, "stop-at-lava", true);
        Direction f = Direction.NORTH;
        public AutoTunnel() { super(HydrinoAddon.CATEGORY, "auto-tunnel", "Digs a straight tunnel in the direction you face (nether highways, mining)."); }

        @Override public void onActivate() { if (Features.ready()) { f = mc.player.getHorizontalFacing(); mc.player.setYaw(f.asRotation()); } }
        @Override public void onDeactivate() { stopWalk(); }

        @EventHandler private void onTick(TickEvent.Pre e) {
            if (!Features.ready()) return;
            BlockPos o = mc.player.getBlockPos();
            int fx = f.getOffsetX(), fz = f.getOffsetZ(), lx = -fz, lz = fx, half = wid.get() / 2;
            Vec3d eye = mc.player.getEyePos();
            BlockPos pick = null;
            for (int l = 1; l <= 4; l++) for (int w = -half - 1; w <= wid.get() - half; w++) for (int h = -1; h <= hei.get(); h++) {
                BlockPos p = o.add(fx * l + lx * w, h, fz * l + lz * w);
                boolean inside = w >= -half && w < wid.get() - half && h >= 0 && h < hei.get();
                if (stopLava.get() && mc.world.getFluidState(p).isIn(FluidTags.LAVA)) { warning("Lava ahead - stopped."); toggle(); return; }
                if (!inside) continue;
                if (!mc.world.getFluidState(p).isEmpty()) { warning("Fluid in tunnel - stopped."); toggle(); return; }
                if (pick == null && breakable(p, false, true) && p.getSquaredDistance(eye) <= range.get() * range.get()) pick = p;
            }
            mc.player.setYaw(f.asRotation());
            if (pick != null) { mc.options.forwardKey.setPressed(false); mine(pick, rotate.get(), silent.get()); }
            else { mc.options.forwardKey.setPressed(true); mc.options.jumpKey.setPressed(mc.player.horizontalCollision && mc.player.isOnGround()); }
        }
    }

    // ---------- 3. Area Filler (floors, walls, bunkers, roofs) ----------
    public static class AreaFiller extends Module {
        SettingGroup ga = settings.getDefaultGroup(), gb = settings.createGroup("Behavior");
        Setting<Area> area = ga.add(new EnumSetting.Builder<Area>().name("area").defaultValue(Area.Around).build());
        Setting<Shape> shape = ga.add(new EnumSetting.Builder<Shape>().name("shape").description("Hollow around you = instant bunker.").defaultValue(Shape.Hollow).build());
        Setting<List<Block>> blocks = ga.add(new BlockListSetting.Builder().name("blocks").description("Uses the first one you have.").defaultValue(Blocks.OBSIDIAN, Blocks.COBBLESTONE).build());
        Setting<Integer> len = Features.num(ga, "length", 8, 1, 64), wid = Features.num(ga, "width", 5, 1, 64), up = Features.num(ga, "height", 3, 1, 64),
            down = Features.num(ga, "depth", 0, 0, 64), rad = Features.num(ga, "radius", 2, 1, 16);
        Setting<BlockPos> c1 = ga.add(new BlockPosSetting.Builder().name("corner-1").defaultValue(BlockPos.ORIGIN).visible(() -> area.get() == Area.Corners).build()),
            c2 = ga.add(new BlockPosSetting.Builder().name("corner-2").defaultValue(BlockPos.ORIGIN).visible(() -> area.get() == Area.Corners).build());
        Setting<Double> range = Features.dbl(gb, "range", 4.5, 1, 6);
        Setting<Integer> perTick = Features.num(gb, "blocks-per-tick", 3, 1, 20);
        Setting<Boolean> rotate = Features.bool(gb, "rotate", true), silent = Features.bool(gb, "silent-aim", true), walk = Features.bool(gb, "auto-walk", false);
        Setting<SettingColor> col = Features.color(gb, "color", 0, 150, 255);
        List<BlockPos> todo = new ArrayList<>();
        public AreaFiller() { super(HydrinoAddon.CATEGORY, "area-filler", "Builds floors, walls, roofs, boxes and bunkers from blocks in your inventory."); }

        @Override public void onActivate() {
            if (!Features.ready()) return;
            List<BlockPos> all = region(area.get(), len.get(), wid.get(), up.get(), down.get(), rad.get(), c1.get(), c2.get());
            BlockPos[] b = bounds(all);
            todo = new ArrayList<>();
            BlockPos me = mc.player.getBlockPos();
            for (BlockPos p : all) if (shapeOk(shape.get(), p, b[0], b[1]) && !p.equals(me) && !p.equals(me.up())) todo.add(p);
            todo.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> p.getSquaredDistance(me)));
            info("Area filler: %d blocks queued.", todo.size());
        }
        @Override public void onDeactivate() { stopWalk(); todo.clear(); }

        private FindItemResult item() { for (Block b : blocks.get()) { FindItemResult f = InvUtils.find(b.asItem()); if (f.found()) return f; } return null; }

        @EventHandler private void onTick(TickEvent.Pre e) {
            if (!Features.ready()) return;
            todo.removeIf(p -> !mc.world.getBlockState(p).isReplaceable());
            if (todo.isEmpty()) { stopWalk(); info("Build finished."); toggle(); return; }
            FindItemResult it = item();
            if (it == null) { stopWalk(); warning("Out of blocks."); toggle(); return; }
            Vec3d eye = mc.player.getEyePos();
            double r2 = range.get() * range.get();
            int n = 0;
            for (BlockPos p : todo) {
                if (n >= perTick.get()) break;
                if (p.getSquaredDistance(eye) > r2) continue;
                place(p, it, rotate.get(), silent.get()); n++;
            }
            if (n == 0 && walk.get()) walkTo(todo.get(0), true); else stopWalk();
        }
        @EventHandler private void onRender(Render3DEvent e) {
            int n = 0;
            for (BlockPos p : todo) { e.renderer.box(p, new SettingColor(col.get().r, col.get().g, col.get().b, 30), col.get(), ShapeMode.Both, 0); if (++n > 800) break; }
        }
    }

    // ---------- 4. Auto Light ----------
    public static class AutoLight extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Integer> minLight = Features.num(g, "light-below", 8, 1, 14), spacing = Features.num(g, "min-spacing", 5, 2, 12), delay = Features.num(g, "delay-ticks", 4, 1, 40);
        Setting<Double> range = Features.dbl(g, "range", 4.0, 1, 5);
        Setting<Boolean> rotate = Features.bool(g, "rotate", true), silent = Features.bool(g, "silent-aim", true);
        List<BlockPos> placed = new ArrayList<>();
        int timer;
        public AutoLight() { super(HydrinoAddon.CATEGORY, "auto-light", "Places torches in dark spots so mobs can't spawn in your base."); }
        @Override public void onDeactivate() { placed.clear(); }

        @EventHandler private void onTick(TickEvent.Pre e) {
            if (!Features.ready() || timer-- > 0) return;
            timer = delay.get();
            FindItemResult torch = InvUtils.find(Items.TORCH);
            if (!torch.found()) return;
            BlockPos o = mc.player.getBlockPos();
            int r = (int) Math.ceil(range.get());
            BlockPos best = null; double bd = Double.MAX_VALUE;
            for (int x = -r; x <= r; x++) for (int y = -2; y <= 2; y++) for (int z = -r; z <= r; z++) {
                BlockPos p = o.add(x, y, z);
                if (!mc.world.getBlockState(p).isAir() || !mc.world.getBlockState(p.down()).isSolidBlock(mc.world, p.down())) continue;
                if (mc.world.getLightLevel(LightType.BLOCK, p) >= minLight.get()) continue;
                boolean near = false;
                for (BlockPos q : placed) if (q.getSquaredDistance(p) < spacing.get() * spacing.get()) { near = true; break; }
                if (near) continue;
                double d = p.getSquaredDistance(mc.player.getEyePos());
                if (d < bd && d <= range.get() * range.get()) { bd = d; best = p; }
            }
            if (best != null) { place(best, torch, rotate.get(), silent.get()); placed.add(best); if (placed.size() > 500) placed.remove(0); }
        }
    }

    // ---------- 5. Portal Builder ----------
    public static class PortalBuilder extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Integer> dist = Features.num(g, "distance", 3, 2, 5);
        Setting<Boolean> light = Features.bool(g, "light-with-flint", true), rotate = Features.bool(g, "rotate", true), silent = Features.bool(g, "silent-aim", true);
        List<BlockPos> frame = new ArrayList<>();
        BlockPos base;
        int timer;
        public PortalBuilder() { super(HydrinoAddon.CATEGORY, "portal-builder", "Builds a nether portal frame in front of you from obsidian and lights it."); }

        @Override public void onActivate() {
            frame.clear();
            if (!Features.ready()) return;
            Direction f = mc.player.getHorizontalFacing();
            int fx = f.getOffsetX(), fz = f.getOffsetZ(), lx = -fz, lz = fx;
            BlockPos o = mc.player.getBlockPos();
            for (int h = 0; h < 5; h++) for (int w = 0; w < 4; w++) {
                boolean edge = w == 0 || w == 3 || h == 0 || h == 4, corner = (w == 0 || w == 3) && (h == 0 || h == 4);
                if (edge && !corner) frame.add(o.add(fx * dist.get() + lx * (w - 1), h - 1, fz * dist.get() + lz * (w - 1)));
            }
            base = o.add(fx * dist.get(), -1, fz * dist.get());
            timer = 0;
        }

        @EventHandler private void onTick(TickEvent.Pre e) {
            if (!Features.ready() || frame.isEmpty() || timer-- > 0) return;
            frame.removeIf(p -> mc.world.getBlockState(p).isOf(Blocks.OBSIDIAN));
            Vec3d eye = mc.player.getEyePos();
            if (!frame.isEmpty()) {
                FindItemResult obi = InvUtils.find(Items.OBSIDIAN);
                if (!obi.found()) { warning("Out of obsidian."); toggle(); return; }
                int n = 0;
                for (BlockPos p : frame) { if (n >= 2) break; if (p.getSquaredDistance(eye) <= 20 && mc.world.getBlockState(p).isReplaceable()) { place(p, obi, rotate.get(), silent.get()); n++; } }
                if (n == 0) { warning("Move closer / need solid ground to place the frame."); timer = 40; }
                return;
            }
            if (light.get()) {
                FindItemResult fl = InvUtils.find(Items.FLINT_AND_STEEL);
                if (fl.found()) {
                    BlockPos target = base;
                    Runnable r = () -> {
                        InvUtils.swap(fl.slot(), true);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(target).add(0, 0.5, 0), Direction.UP, target, false));
                        InvUtils.swapBack();
                    };
                    if (rotate.get()) HydrinoAddon.Aim.at(target, silent.get(), r); else r.run();
                } else warning("No flint and steel - light it yourself.");
            }
            info("Portal built.");
            toggle();
        }
    }

    // ---------- 6. Auto Bridge ----------
    public static class AutoBridge extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Integer> wid = Features.num(g, "width", 1, 1, 5), ahead = Features.num(g, "look-ahead", 2, 1, 4);
        Setting<List<Block>> blocks = g.add(new BlockListSetting.Builder().name("blocks").defaultValue(Blocks.OBSIDIAN, Blocks.COBBLESTONE, Blocks.NETHERRACK).build());
        Setting<Boolean> walk = Features.bool(g, "walk-forward", true), rotate = Features.bool(g, "rotate", true), silent = Features.bool(g, "silent-aim", true);
        Direction f = Direction.NORTH;
        public AutoBridge() { super(HydrinoAddon.CATEGORY, "auto-bridge", "Walks forward and builds a bridge/highway under you."); }
        @Override public void onActivate() { if (Features.ready()) { f = mc.player.getHorizontalFacing(); mc.player.setYaw(f.asRotation()); } }
        @Override public void onDeactivate() { stopWalk(); }

        @EventHandler private void onTick(TickEvent.Pre e) {
            if (!Features.ready()) return;
            FindItemResult it = null;
            for (Block b : blocks.get()) { FindItemResult x = InvUtils.find(b.asItem()); if (x.found()) { it = x; break; } }
            if (it == null) { stopWalk(); warning("Out of blocks."); toggle(); return; }
            mc.player.setYaw(f.asRotation());
            BlockPos o = mc.player.getBlockPos().down();
            int fx = f.getOffsetX(), fz = f.getOffsetZ(), lx = -fz, lz = fx, half = wid.get() / 2, n = 0;
            for (int d = 0; d <= ahead.get() && n < 3; d++) for (int w = -half; w < wid.get() - half && n < 3; w++) {
                BlockPos p = o.add(fx * d + lx * w, 0, fz * d + lz * w);
                if (mc.world.getBlockState(p).isReplaceable()) { place(p, it, rotate.get(), silent.get()); n++; }
            }
            if (walk.get()) { mc.options.forwardKey.setPressed(true); mc.options.jumpKey.setPressed(false); }
        }
    }
}
