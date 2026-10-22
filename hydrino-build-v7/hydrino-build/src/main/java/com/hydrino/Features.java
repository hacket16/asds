package com.hydrino;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** 25 anarchy utility modules for Hydrino. */
public class Features {
    // ---------- helpers ----------
    static Setting<Boolean> bool(SettingGroup g, String n, boolean d) { return g.add(new BoolSetting.Builder().name(n).defaultValue(d).build()); }
    static Setting<Integer> num(SettingGroup g, String n, int d, int lo, int hi) { return g.add(new IntSetting.Builder().name(n).defaultValue(d).range(lo, hi).sliderRange(lo, hi).build()); }
    static Setting<Double> dbl(SettingGroup g, String n, double d, double lo, double hi) { return g.add(new DoubleSetting.Builder().name(n).defaultValue(d).range(lo, hi).sliderRange(lo, hi).build()); }
    static Setting<SettingColor> color(SettingGroup g, String n, int r, int gr, int b) { return g.add(new ColorSetting.Builder().name(n).defaultValue(new SettingColor(r, gr, b, 200)).build()); }

    static void log(String file, String line) {
        try {
            Files.createDirectories(HydrinoAddon.FOLDER);
            Files.writeString(HydrinoAddon.FOLDER.resolve(file), line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
    static String dim() { return mc.world.getRegistryKey().getValue().getPath(); }
    static boolean enemy(PlayerEntity p) { return p != mc.player && !Friends.get().isFriend(p); }
    static boolean ready() { return mc.world != null && mc.player != null; }

    // ---------- 1. Chunk Finder (chunk trails) ----------
    public static class ChunkFinder extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Boolean> storage = bool(g, "storage", true), spawners = bool(g, "spawners", true), signs = bool(g, "signs", true),
            beds = bool(g, "beds", false), tracers = bool(g, "tracers", true), ping = bool(g, "chat-ping", true), save = bool(g, "save-to-file", true);
        Setting<Integer> min = num(g, "min-blocks", 3, 1, 50), every = num(g, "scan-interval", 40, 10, 200);
        Setting<SettingColor> col = color(g, "color", 255, 170, 0);
        Map<Long, int[]> found = new HashMap<>();
        int timer;
        public ChunkFinder() { super(HydrinoAddon.CATEGORY, "chunk-finder", "Marks chunks with lots of player-made blocks (stash/base/trail finder)."); }

        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || timer-- > 0) return;
            timer = every.get();
            int vd = mc.options.getViewDistance().getValue(), px = mc.player.getBlockX() >> 4, pz = mc.player.getBlockZ() >> 4;
            for (int cx = px - vd; cx <= px + vd; cx++) for (int cz = pz - vd; cz <= pz + vd; cz++) {
                WorldChunk c = mc.world.getChunkManager().getWorldChunk(cx, cz);
                if (c == null) continue;
                int n = 0;
                for (BlockEntity be : c.getBlockEntities().values()) {
                    Block b = be.getCachedState().getBlock();
                    if ((storage.get() && (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock || b instanceof HopperBlock || b instanceof DispenserBlock || b instanceof AbstractFurnaceBlock))
                        || (spawners.get() && b == Blocks.SPAWNER) || (signs.get() && b instanceof AbstractSignBlock) || (beds.get() && b instanceof BedBlock)) n++;
                }
                long key = ((long) cx << 32) | (cz & 0xffffffffL);
                if (n >= min.get()) {
                    if (found.put(key, new int[]{cx, cz, n}) == null) {
                        if (ping.get()) info("Chunk at %d, %d has %d interesting blocks", cx * 16, cz * 16, n);
                        if (save.get()) log("chunks.txt", dim() + " | " + cx * 16 + " " + cz * 16 + " | " + n);
                    }
                } else found.remove(key);
            }
        }

        @EventHandler private void onRender(Render3DEvent e) {
            double y1 = mc.world.getBottomY(), y2 = mc.world.getBottomY() + mc.world.getHeight();
            for (int[] f : found.values()) {
                double x = f[0] * 16, z = f[1] * 16;
                e.renderer.box(x, y1, z, x + 16, y2, z + 16, col.get(), col.get(), ShapeMode.Lines, 0);
                if (tracers.get()) e.renderer.line(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), x + 8, mc.player.getEyeY(), z + 8, col.get());
            }
        }
        @Override public void onDeactivate() { found.clear(); }
    }

    // ---------- 2. Block Finder ----------
    public static class BlockFinder extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<List<Block>> blocks = g.add(new BlockListSetting.Builder().name("blocks").defaultValue(Blocks.NETHER_PORTAL, Blocks.SPAWNER, Blocks.END_PORTAL_FRAME).build());
        Setting<Integer> range = num(g, "range", 24, 4, 64), every = num(g, "scan-interval", 40, 10, 200);
        Setting<Boolean> tracers = bool(g, "tracers", false);
        Setting<SettingColor> col = color(g, "color", 0, 200, 255);
        List<BlockPos> hits = new ArrayList<>();
        int timer;
        public BlockFinder() { super(HydrinoAddon.CATEGORY, "block-finder", "Highlights chosen blocks around you (portals, spawners, ...)."); }

        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || timer-- > 0) return;
            timer = every.get();
            hits.clear();
            Set<Block> want = new HashSet<>(blocks.get());
            int r = range.get();
            BlockPos c = mc.player.getBlockPos();
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
                if (want.contains(mc.world.getBlockState(m).getBlock())) { hits.add(m.toImmutable()); if (hits.size() > 2000) return; }
            }
        }
        @EventHandler private void onRender(Render3DEvent e) {
            for (BlockPos p : hits) {
                e.renderer.box(p, new SettingColor(col.get().r, col.get().g, col.get().b, 40), col.get(), ShapeMode.Both, 0);
                if (tracers.get()) e.renderer.line(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, col.get());
            }
        }
        @Override public void onDeactivate() { hits.clear(); }
    }

    // ---------- 3. Player Trails ----------
    public static class PlayerTrails extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Integer> max = num(g, "max-points", 300, 20, 2000);
        Setting<SettingColor> col = color(g, "color", 255, 60, 60);
        Map<UUID, ArrayList<double[]>> trails = new HashMap<>();
        public PlayerTrails() { super(HydrinoAddon.CATEGORY, "player-trails", "Draws the path other players walked - follow them."); }

        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready()) return;
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                ArrayList<double[]> t = trails.computeIfAbsent(p.getUuid(), k -> new ArrayList<>());
                double[] last = t.isEmpty() ? null : t.get(t.size() - 1);
                if (last == null || Math.pow(p.getX() - last[0], 2) + Math.pow(p.getY() - last[1], 2) + Math.pow(p.getZ() - last[2], 2) >= 1) {
                    t.add(new double[]{p.getX(), p.getY(), p.getZ()});
                    while (t.size() > max.get()) t.remove(0);
                }
            }
        }
        @EventHandler private void onRender(Render3DEvent e) {
            for (ArrayList<double[]> t : trails.values())
                for (int i = 1; i < t.size(); i++) e.renderer.line(t.get(i - 1)[0], t.get(i - 1)[1] + 0.1, t.get(i - 1)[2], t.get(i)[0], t.get(i)[1] + 0.1, t.get(i)[2], col.get());
        }
        @Override public void onDeactivate() { trails.clear(); }
    }

    // ---------- 4. Logout Spots ----------
    public static class LogoutSpots extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<SettingColor> col = color(g, "color", 255, 255, 0);
        Map<UUID, Object[]> seen = new HashMap<>();   // {name, x, y, z}
        Map<UUID, Object[]> spots = new HashMap<>();
        public LogoutSpots() { super(HydrinoAddon.CATEGORY, "logout-spots", "Marks where players logged out."); }

        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || mc.getNetworkHandler() == null) return;
            Set<UUID> now = new HashSet<>();
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                now.add(p.getUuid());
                seen.put(p.getUuid(), new Object[]{p.getName().getString(), p.getX(), p.getY(), p.getZ()});
                spots.remove(p.getUuid());
            }
            for (var it = seen.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                if (now.contains(en.getKey())) continue;
                if (mc.getNetworkHandler().getPlayerListEntry(en.getKey()) == null) {
                    Object[] s = en.getValue();
                    spots.put(en.getKey(), s);
                    info("%s logged out at %d, %d, %d", s[0], (int) (double) s[1], (int) (double) s[2], (int) (double) s[3]);
                }
                it.remove();
            }
        }
        @EventHandler private void onRender(Render3DEvent e) {
            for (Object[] s : spots.values()) {
                double x = (double) s[1], y = (double) s[2], z = (double) s[3];
                e.renderer.box(x - 0.4, y, z - 0.4, x + 0.4, y + 1.8, z + 0.4, new SettingColor(col.get().r, col.get().g, col.get().b, 40), col.get(), ShapeMode.Both, 0);
            }
        }
        @Override public void onDeactivate() { seen.clear(); spots.clear(); }
    }

    // ---------- 5. Player Alert ----------
    public static class PlayerAlert extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Boolean> leave = bool(g, "alert-on-leave", false);
        Set<UUID> known = new HashSet<>();
        public PlayerAlert() { super(HydrinoAddon.CATEGORY, "player-alert", "Chat alert when a non-friend enters your render distance."); }

        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready()) return;
            Set<UUID> now = new HashSet<>();
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (!enemy(p)) continue;
                now.add(p.getUuid());
                if (known.add(p.getUuid())) info("%s spotted %d blocks away at %d, %d, %d", p.getName().getString(), (int) mc.player.distanceTo(p), (int) p.getX(), (int) p.getY(), (int) p.getZ());
            }
            if (leave.get()) for (UUID u : known) if (!now.contains(u)) info("A player left your render distance.");
            known.retainAll(now);
        }
        @Override public void onDeactivate() { known.clear(); }
    }

    // ---------- 6. Player Log ----------
    public static class PlayerLog extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Double> radius = dbl(g, "radius", 64, 8, 256), health = dbl(g, "health", 0, 0, 20);
        public PlayerLog() { super(HydrinoAddon.CATEGORY, "player-log", "Disconnects when a non-friend gets close (or health gets low)."); }

        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || mc.isInSingleplayer() || mc.getNetworkHandler() == null) return;
            String why = null;
            if (health.get() > 0 && mc.player.getHealth() <= health.get()) why = "health low";
            for (PlayerEntity p : mc.world.getPlayers()) if (enemy(p) && mc.player.distanceTo(p) <= radius.get()) why = p.getName().getString() + " came within range";
            if (why != null) { mc.getNetworkHandler().getConnection().disconnect(Text.literal("[Hydrino] Logged out: " + why)); toggle(); }
        }
    }

    // ---------- 7. Save Pos ----------
    public static class SavePos extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<String> label = g.add(new StringSetting.Builder().name("label").defaultValue("spot").build());
        public SavePos() { super(HydrinoAddon.CATEGORY, "save-pos", "Bind a key: each press saves your coords to hydrino/coords.txt."); }
        @Override public void onActivate() {
            if (ready()) {
                String s = LocalDateTime.now().withNano(0) + " | " + label.get() + " | " + dim() + " | " + mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ();
                log("coords.txt", s); info("Saved: %s", s);
            }
            toggle();
        }
    }

    // ---------- 8. Death Pos ----------
    public static class DeathPos extends Module {
        boolean logged;
        public DeathPos() { super(HydrinoAddon.CATEGORY, "death-pos", "Saves and prints where you died."); }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready()) return;
            if (!mc.player.isAlive() && !logged) {
                logged = true;
                String s = LocalDateTime.now().withNano(0) + " | DEATH | " + dim() + " | " + mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ();
                log("coords.txt", s); info("You died at %d, %d, %d (%s)", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), dim());
            } else if (mc.player.isAlive()) logged = false;
        }
    }

    // ---------- 9. Coord Sniffer ----------
    public static class CoordSniffer extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Integer> min = num(g, "min-distance", 500, 0, 100000);
        Setting<Boolean> save = bool(g, "save-to-file", true);
        static final Pattern P = Pattern.compile("(-?\\d{3,7})[\\s,/]+(-?\\d{3,7})");
        public CoordSniffer() { super(HydrinoAddon.CATEGORY, "coord-sniffer", "Spots coordinates posted in chat and logs them."); }
        @EventHandler private void onMsg(ReceiveMessageEvent e) {
            String m = e.getMessage().getString();
            Matcher mt = P.matcher(m);
            while (mt.find()) {
                try {
                    long x = Long.parseLong(mt.group(1)), z = Long.parseLong(mt.group(2));
                    if (Math.abs(x) < min.get() && Math.abs(z) < min.get()) continue;
                    info("Coords spotted: %d, %d", x, z);
                    if (save.get()) log("sniffed.txt", LocalDateTime.now().withNano(0) + " | " + x + " " + z + " | " + m);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // ---------- 10. Auto Reply ----------
    public static class AutoReply extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<List<String>> rules = g.add(new StringListSetting.Builder().name("rules").description("trigger=reply").defaultValue(List.of("coords?=no")).build());
        Setting<Integer> cooldown = num(g, "cooldown-ticks", 200, 20, 2000);
        int cd;
        public AutoReply() { super(HydrinoAddon.CATEGORY, "auto-reply", "Replies to chat triggers (trigger=reply)."); }
        @EventHandler private void onTick(TickEvent.Post e) { if (cd > 0) cd--; }
        @EventHandler private void onMsg(ReceiveMessageEvent e) {
            if (cd > 0 || !ready()) return;
            String m = e.getMessage().getString(), low = m.toLowerCase(), me = mc.getSession().getUsername();
            if (m.startsWith("<" + me + ">")) return;
            for (String r : rules.get()) {
                int i = r.indexOf('=');
                if (i < 1) continue;
                String trig = r.substring(0, i).trim().toLowerCase(), reply = r.substring(i + 1).trim();
                if (!trig.isEmpty() && !reply.isEmpty() && low.contains(trig) && !low.contains(reply.toLowerCase())) {
                    meteordevelopment.meteorclient.utils.player.ChatUtils.sendPlayerMsg(reply);
                    cd = cooldown.get();
                    return;
                }
            }
        }
    }

    // ---------- 11-13. Gear alerts ----------
    static int pct(ItemStack s) { return s.isDamageable() ? 100 - s.getDamage() * 100 / Math.max(1, s.getMaxDamage()) : 100; }

    public static class ElytraGuard extends Module {
        Setting<Integer> below = num(settings.getDefaultGroup(), "percent", 15, 1, 100);
        boolean warned; int t;
        public ElytraGuard() { super(HydrinoAddon.CATEGORY, "elytra-guard", "Warns when your elytra is about to break."); }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || t++ % 20 != 0) return;
            ItemStack s = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            if (!s.isOf(Items.ELYTRA)) return;
            if (pct(s) <= below.get()) { if (!warned) { warned = true; warning("Elytra at %d%%!", pct(s)); } } else warned = false;
        }
    }

    public static class ArmorAlert extends Module {
        Setting<Integer> below = num(settings.getDefaultGroup(), "percent", 15, 1, 100);
        Set<EquipmentSlot> warned = new HashSet<>(); int t;
        public ArmorAlert() { super(HydrinoAddon.CATEGORY, "armor-alert", "Warns when armor durability is low."); }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || t++ % 20 != 0) return;
            for (EquipmentSlot sl : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack s = mc.player.getEquippedStack(sl);
                if (s.isEmpty() || !s.isDamageable()) continue;
                if (pct(s) <= below.get()) { if (warned.add(sl)) warning("%s at %d%%!", s.getName().getString(), pct(s)); } else warned.remove(sl);
            }
        }
    }

    public static class TotemAlert extends Module {
        Setting<Integer> below = num(settings.getDefaultGroup(), "min-totems", 3, 1, 36);
        boolean warned; int t;
        public TotemAlert() { super(HydrinoAddon.CATEGORY, "totem-alert", "Warns when you are low on totems."); }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || t++ % 20 != 0) return;
            int n = InvUtils.find(Items.TOTEM_OF_UNDYING).count();
            if (n < below.get()) { if (!warned) { warned = true; warning("Only %d totem(s) left!", n); } } else warned = false;
        }
    }

    // ---------- 14. Highway Walker ----------
    public static class HighwayWalker extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Boolean> snap = bool(g, "snap-yaw-45", true), sprint = bool(g, "sprint", true), jump = bool(g, "auto-jump", true);
        Setting<Double> stopNear = dbl(g, "stop-near-player", 0, 0, 128);
        public HighwayWalker() { super(HydrinoAddon.CATEGORY, "highway-walker", "Walks straight along a highway (yaw snapped to 45 degrees)."); }
        @EventHandler private void onTick(TickEvent.Pre e) {
            if (!ready()) return;
            if (stopNear.get() > 0) for (PlayerEntity p : mc.world.getPlayers()) if (enemy(p) && mc.player.distanceTo(p) <= stopNear.get()) { info("Player nearby - stopped."); toggle(); return; }
            if (snap.get()) mc.player.setYaw(Math.round(mc.player.getYaw() / 45f) * 45f);
            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(sprint.get());
            mc.options.jumpKey.setPressed(jump.get() && mc.player.horizontalCollision && mc.player.isOnGround());
        }
        @Override public void onDeactivate() { mc.options.forwardKey.setPressed(false); mc.options.sprintKey.setPressed(false); mc.options.jumpKey.setPressed(false); }
    }

    // ---------- 15. Path Trail (your own breadcrumbs) ----------
    public static class PathTrail extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Integer> max = num(g, "max-points", 2000, 50, 10000);
        Setting<Double> gap = dbl(g, "spacing", 3, 1, 20);
        Setting<SettingColor> col = color(g, "color", 80, 255, 120);
        ArrayList<double[]> pts = new ArrayList<>();
        public PathTrail() { super(HydrinoAddon.CATEGORY, "path-trail", "Draws the path you walked so you can find your way back."); }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready()) return;
            double[] l = pts.isEmpty() ? null : pts.get(pts.size() - 1);
            if (l == null || Math.hypot(Math.hypot(mc.player.getX() - l[0], mc.player.getY() - l[1]), mc.player.getZ() - l[2]) >= gap.get()) {
                pts.add(new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
                while (pts.size() > max.get()) pts.remove(0);
            }
        }
        @EventHandler private void onRender(Render3DEvent e) {
            for (int i = 1; i < pts.size(); i++) e.renderer.line(pts.get(i - 1)[0], pts.get(i - 1)[1] + 0.1, pts.get(i - 1)[2], pts.get(i)[0], pts.get(i)[1] + 0.1, pts.get(i)[2], col.get());
        }
        @EventHandler private void onJoin(GameJoinedEvent e) { pts.clear(); }
        @Override public void onDeactivate() { pts.clear(); }
    }

    // ---------- 16. Coord Tracer ----------
    public static class CoordTracer extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Integer> x = g.add(new IntSetting.Builder().name("x").defaultValue(0).range(-30000000, 30000000).sliderRange(-5000, 5000).build()),
            y = g.add(new IntSetting.Builder().name("y").defaultValue(64).range(-64, 320).sliderRange(-64, 320).build()),
            z = g.add(new IntSetting.Builder().name("z").defaultValue(0).range(-30000000, 30000000).sliderRange(-5000, 5000).build());
        Setting<Integer> every = num(g, "distance-print-seconds", 10, 0, 120);
        Setting<SettingColor> col = color(g, "color", 255, 0, 255);
        int t;
        public CoordTracer() { super(HydrinoAddon.CATEGORY, "coord-tracer", "Draws a tracer and beam to any coordinates and prints the distance."); }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || every.get() == 0 || t++ % (every.get() * 20) != 0) return;
            info("%d blocks to %d, %d", (int) Math.hypot(x.get() - mc.player.getX(), z.get() - mc.player.getZ()), x.get(), z.get());
        }
        @EventHandler private void onRender(Render3DEvent e) {
            double bot = mc.world.getBottomY(), top = mc.world.getBottomY() + mc.world.getHeight();
            e.renderer.line(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), x.get() + 0.5, y.get() + 0.5, z.get() + 0.5, col.get());
            e.renderer.box(x.get(), bot, z.get(), x.get() + 1, top, z.get() + 1, new SettingColor(col.get().r, col.get().g, col.get().b, 40), col.get(), ShapeMode.Both, 0);
        }
    }

    // ---------- 17. Chunk Grid ----------
    public static class ChunkGrid extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<Integer> radius = num(g, "chunk-radius", 1, 0, 4), height = num(g, "height", 24, 4, 128);
        Setting<SettingColor> col = color(g, "color", 255, 255, 255);
        public ChunkGrid() { super(HydrinoAddon.CATEGORY, "chunk-grid", "Shows chunk borders around you."); }
        @EventHandler private void onRender(Render3DEvent e) {
            int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4, r = radius.get();
            double y = mc.player.getY();
            for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++)
                e.renderer.box(x * 16, y - height.get(), z * 16, x * 16 + 16, y + height.get(), z * 16 + 16, col.get(), col.get(), ShapeMode.Lines, 0);
        }
    }

    // ---------- 18. Coord Converter ----------
    public static class CoordConverter extends Module {
        public CoordConverter() { super(HydrinoAddon.CATEGORY, "coord-converter", "Bind a key: prints your Overworld and Nether coordinates."); }
        @Override public void onActivate() {
            if (ready()) {
                int x = mc.player.getBlockX(), z = mc.player.getBlockZ();
                if (dim().equals("the_nether")) info("Nether %d, %d = Overworld %d, %d", x, z, x * 8, z * 8);
                else info("Overworld %d, %d = Nether %d, %d", x, z, x / 8, z / 8);
            }
            toggle();
        }
    }

    // ---------- 19. Dimension Logger ----------
    public static class DimensionLogger extends Module {
        String last = "";
        public DimensionLogger() { super(HydrinoAddon.CATEGORY, "dimension-logger", "Logs dimension changes with coordinates."); }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready()) return;
            String d = dim();
            if (!d.equals(last)) {
                if (!last.isEmpty()) { info("Changed dimension: %s -> %s at %d, %d", last, d, mc.player.getBlockX(), mc.player.getBlockZ()); log("dimensions.txt", LocalDateTime.now().withNano(0) + " | " + last + " -> " + d + " | " + mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ()); }
                last = d;
            }
        }
        @Override public void onActivate() { last = ""; }
    }

    // ---------- 20. Lag Alert ----------
    public static class LagAlert extends Module {
        Setting<Integer> ms = num(settings.getDefaultGroup(), "threshold-ms", 3000, 500, 30000);
        long last = System.currentTimeMillis(); boolean alerted;
        public LagAlert() { super(HydrinoAddon.CATEGORY, "lag-alert", "Warns when the server stops sending packets."); }
        @Override public void onActivate() { last = System.currentTimeMillis(); alerted = false; }
        @EventHandler private void onPacket(PacketEvent.Receive e) { last = System.currentTimeMillis(); if (alerted) { alerted = false; info("Server is responding again."); } }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (ready() && !alerted && System.currentTimeMillis() - last > ms.get()) { alerted = true; warning("No server packets for %d ms (lagging?).", System.currentTimeMillis() - last); }
        }
    }

    // ---------- 21. Queue Notifier ----------
    public static class QueueNotifier extends Module {
        Setting<Integer> at = num(settings.getDefaultGroup(), "alert-at-position", 10, 1, 500);
        static final Pattern Q = Pattern.compile("(?i)(?:position|queue)[^0-9]{0,24}(\\d{1,5})");
        int lastPos = -1;
        public QueueNotifier() { super(HydrinoAddon.CATEGORY, "queue-notifier", "Alerts when your queue position gets low."); }
        @EventHandler private void onMsg(ReceiveMessageEvent e) {
            Matcher m = Q.matcher(e.getMessage().getString());
            if (!m.find()) return;
            int n = Integer.parseInt(m.group(1));
            if (n <= at.get() && n != lastPos) warning("Queue position: %d - get ready!", n);
            lastPos = n;
        }
    }

    // ---------- 22. Travel Stats ----------
    public static class TravelStats extends Module {
        Setting<Integer> secs = num(settings.getDefaultGroup(), "print-seconds", 10, 1, 120);
        double lx, lz, total, window; int t; boolean init;
        public TravelStats() { super(HydrinoAddon.CATEGORY, "travel-stats", "Prints your speed and distance traveled."); }
        @Override public void onActivate() { init = false; total = 0; window = 0; t = 0; }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready()) return;
            double x = mc.player.getX(), z = mc.player.getZ();
            if (init) { double d = Math.hypot(x - lx, z - lz); if (d < 100) { total += d; window += d; } }
            lx = x; lz = z; init = true;
            if (++t >= secs.get() * 20) { info("%.1f blocks/s | %.0f blocks total", window / secs.get(), total); window = 0; t = 0; }
        }
    }

    // ---------- 23. Player Tracker ----------
    public static class PlayerTracker extends Module {
        Setting<Integer> secs = num(settings.getDefaultGroup(), "print-seconds", 5, 1, 60);
        int t;
        public PlayerTracker() { super(HydrinoAddon.CATEGORY, "player-tracker", "Prints the nearest non-friend's distance and coords."); }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || ++t < secs.get() * 20) return;
            t = 0;
            PlayerEntity best = null;
            for (PlayerEntity p : mc.world.getPlayers()) if (enemy(p) && (best == null || mc.player.distanceTo(p) < mc.player.distanceTo(best))) best = p;
            if (best != null) info("%s: %d blocks at %d, %d, %d", best.getName().getString(), (int) mc.player.distanceTo(best), (int) best.getX(), (int) best.getY(), (int) best.getZ());
        }
    }

    // ---------- 24. Item Finder ----------
    public static class ItemFinder extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<List<Item>> items = g.add(new ItemListSetting.Builder().name("items").defaultValue(Items.ELYTRA, Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE, Items.NETHERITE_INGOT).build());
        Setting<Boolean> tracers = bool(g, "tracers", true);
        Setting<SettingColor> col = color(g, "color", 255, 215, 0);
        public ItemFinder() { super(HydrinoAddon.CATEGORY, "item-finder", "Highlights valuable dropped items."); }
        @EventHandler private void onRender(Render3DEvent e) {
            int n = 0;
            for (Entity en : mc.world.getEntities()) {
                if (!(en instanceof ItemEntity ie) || !items.get().contains(ie.getStack().getItem())) continue;
                double x = ie.getX(), y = ie.getY(), z = ie.getZ();
                e.renderer.box(x - 0.25, y, z - 0.25, x + 0.25, y + 0.5, z + 0.25, new SettingColor(col.get().r, col.get().g, col.get().b, 50), col.get(), ShapeMode.Both, 0);
                if (tracers.get()) e.renderer.line(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), x, y + 0.25, z, col.get());
                if (++n > 200) break;
            }
        }
    }

    // ---------- 25. Supply Alert ----------
    public static class SupplyAlert extends Module {
        SettingGroup g = settings.getDefaultGroup();
        Setting<List<Item>> items = g.add(new ItemListSetting.Builder().name("items").defaultValue(Items.ENCHANTED_GOLDEN_APPLE, Items.FIREWORK_ROCKET, Items.EXPERIENCE_BOTTLE).build());
        Setting<Integer> below = num(g, "warn-below", 8, 1, 64);
        Set<Item> warned = new HashSet<>(); int t;
        public SupplyAlert() { super(HydrinoAddon.CATEGORY, "supply-alert", "Warns when chosen items run low in your inventory."); }
        @EventHandler private void onTick(TickEvent.Post e) {
            if (!ready() || t++ % 20 != 0) return;
            for (Item it : items.get()) {
                int n = InvUtils.find(it).count();
                if (n < below.get()) { if (warned.add(it)) warning("Low on %s: %d left", it.getName().getString(), n); } else warned.remove(it);
            }
        }
    }
}
