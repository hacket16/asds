package com.hydrino;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public class HydrinoAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Hydrino");
    public static final Path FOLDER = FabricLoader.getInstance().getGameDir().resolve("hydrino");
    public static final Path SCHEMATICS = FOLDER.resolve("schematics");

    @Override
    public void onInitialize() {
        try { Files.createDirectories(SCHEMATICS); } catch (IOException e) { e.printStackTrace(); }

        Modules.get().add(new SchematicBuilder());
        Modules.get().add(new HydrinoAutoFarm());
        Modules.get().add(new SixB6TAutoLogin());
        Modules.get().add(new SixB6TChatFilter());
        Modules.get().add(new Features.ChunkFinder());
        Modules.get().add(new Features.BlockFinder());
        Modules.get().add(new Features.PlayerTrails());
        Modules.get().add(new Features.LogoutSpots());
        Modules.get().add(new Features.PlayerAlert());
        Modules.get().add(new Features.PlayerLog());
        Modules.get().add(new Features.SavePos());
        Modules.get().add(new Features.DeathPos());
        Modules.get().add(new Features.CoordSniffer());
        Modules.get().add(new Features.AutoReply());
        Modules.get().add(new Features.ElytraGuard());
        Modules.get().add(new Features.ArmorAlert());
        Modules.get().add(new Features.TotemAlert());
        Modules.get().add(new Features.HighwayWalker());
        Modules.get().add(new Features.PathTrail());
        Modules.get().add(new Features.CoordTracer());
        Modules.get().add(new Features.ChunkGrid());
        Modules.get().add(new Features.CoordConverter());
        Modules.get().add(new Features.DimensionLogger());
        Modules.get().add(new Features.LagAlert());
        Modules.get().add(new Features.QueueNotifier());
        Modules.get().add(new Features.TravelStats());
        Modules.get().add(new Features.PlayerTracker());
        Modules.get().add(new Features.ItemFinder());
        Modules.get().add(new Features.SupplyAlert());
        Modules.get().add(new OldChunks());
        Modules.get().add(new BaseTools.AreaMiner());
        Modules.get().add(new BaseTools.AutoTunnel());
        Modules.get().add(new BaseTools.AreaFiller());
        Modules.get().add(new BaseTools.AutoLight());
        Modules.get().add(new BaseTools.PortalBuilder());
        Modules.get().add(new BaseTools.AutoBridge());
        Modules.get().add(new ElytraBot());
    }

    @Override public void onRegisterCategories() { Modules.registerCategory(CATEGORY); }
    @Override public String getPackage() { return "com.hydrino"; }
    @Override public GithubRepo getRepo() { return new GithubRepo("YourName", "hydrino"); }

    public static class Aim {
        /** Looks at pos then runs action. Silent = server-side rotation only, camera untouched. */
        public static void at(BlockPos pos, boolean silent, Runnable action) {
            Vec3d v = Vec3d.ofCenter(pos);
            double yaw = Rotations.getYaw(v), pitch = Rotations.getPitch(v);
            if (silent) Rotations.rotate(yaw, pitch, 50, action);
            else {
                mc.player.setYaw((float) yaw);
                mc.player.setPitch((float) pitch);
                action.run();
            }
        }
    }

    /** Reads Sponge .schem (v2 and v3). Block properties (facing etc.) are ignored for now. */
    public static class SchematicLoader {
        /** Litematica .litematic support (block properties ignored). Origin = min corner of the whole schematic. */
        private static Map<BlockPos, Block> loadLitematic(NbtCompound root) {
            NbtCompound regions = root.getCompoundOrEmpty("Regions");
            List<int[]> raw = new ArrayList<>();
            List<Block> rawBlocks = new ArrayList<>();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;

            for (String name : regions.getKeys()) {
                NbtCompound r = regions.getCompoundOrEmpty(name);
                NbtCompound pos = r.getCompoundOrEmpty("Position"), size = r.getCompoundOrEmpty("Size");
                int sx = size.getInt("x", 0), sy = size.getInt("y", 0), sz = size.getInt("z", 0);
                int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
                if (ax == 0 || ay == 0 || az == 0) continue;
                int ox = pos.getInt("x", 0) + (sx < 0 ? sx + 1 : 0);
                int oy = pos.getInt("y", 0) + (sy < 0 ? sy + 1 : 0);
                int oz = pos.getInt("z", 0) + (sz < 0 ? sz + 1 : 0);

                NbtList pal = r.getListOrEmpty("BlockStatePalette");
                Block[] blocks = new Block[pal.size()];
                for (int i = 0; i < blocks.length; i++) {
                    NbtCompound e = pal.get(i).asCompound().orElse(new NbtCompound());
                    Identifier id = Identifier.tryParse(e.getString("Name", "minecraft:air"));
                    blocks[i] = id == null ? Blocks.AIR : Registries.BLOCK.get(id);
                }

                long[] arr = r.getLongArray("BlockStates").orElse(new long[0]);
                int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, blocks.length - 1)));
                long mask = (1L << bits) - 1;
                long total = (long) ax * ay * az;

                for (int i = 0; i < total; i++) {
                    long start = (long) i * bits;
                    int si = (int) (start >> 6), ei = (int) (((long) (i + 1) * bits - 1) >> 6), so = (int) (start & 63);
                    if (ei >= arr.length) break;
                    long v = si == ei ? (arr[si] >>> so) & mask : ((arr[si] >>> so) | (arr[ei] << (64 - so))) & mask;
                    if (v >= blocks.length) continue;
                    Block b = blocks[(int) v];
                    if (b.getDefaultState().isAir()) continue;
                    int x = ox + i % ax, z = oz + (i / ax) % az, y = oy + i / (ax * az);
                    raw.add(new int[]{x, y, z});
                    rawBlocks.add(b);
                    minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                }
            }

            Map<BlockPos, Block> out = new LinkedHashMap<>();
            for (int i = 0; i < raw.size(); i++) {
                int[] c = raw.get(i);
                out.put(new BlockPos(c[0] - minX, c[1] - minY, c[2] - minZ), rawBlocks.get(i));
            }
            return out;
        }

        public static Map<BlockPos, Block> load(Path file) throws IOException {
            NbtCompound root;
            try (InputStream in = Files.newInputStream(file)) {
                root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
            }
            if (file.toString().endsWith(".litematic")) return loadLitematic(root);
        if (root.contains("Schematic")) root = root.getCompoundOrEmpty("Schematic");

            int w = root.getShort("Width", (short) 0) & 0xFFFF;
            int h = root.getShort("Height", (short) 0) & 0xFFFF;
            int l = root.getShort("Length", (short) 0) & 0xFFFF;

            NbtCompound paletteTag; byte[] data;
            if (root.contains("Blocks")) { // v3
                NbtCompound b = root.getCompoundOrEmpty("Blocks");
                paletteTag = b.getCompoundOrEmpty("Palette");
                data = b.getByteArray("Data").orElse(new byte[0]);
            } else { // v2
                paletteTag = root.getCompoundOrEmpty("Palette");
                data = root.getByteArray("BlockData").orElse(new byte[0]);
            }

            Map<Integer, Block> palette = new HashMap<>();
            for (String key : paletteTag.getKeys()) {
                String id = key.contains("[") ? key.substring(0, key.indexOf('[')) : key;
                Identifier ident = Identifier.tryParse(id);
                Block block = ident == null ? Blocks.AIR : Registries.BLOCK.get(ident);
                palette.put(paletteTag.getInt(key, 0), block);
            }

            Map<BlockPos, Block> out = new LinkedHashMap<>();
            int i = 0, idx = 0;
            while (i < data.length && idx < w * h * l) {
                int value = 0, shift = 0, b;
                do { b = data[i++]; value |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0 && i < data.length);
                Block block = palette.getOrDefault(value, Blocks.AIR);
                if (!block.getDefaultState().isAir()) {
                    int x = idx % w, z = (idx / w) % l, y = idx / (w * l);
                    out.put(new BlockPos(x, y, z), block);
                }
                idx++;
            }
            return out;
        }
    }

    public static class SchematicBuilder extends Module {
        private final SettingGroup sgGeneral = settings.getDefaultGroup();
        private final SettingGroup sgPlacement = settings.createGroup("Placement");
        private final SettingGroup sgRender = settings.createGroup("Ghost Preview");

        private final Setting<String> schematic = sgGeneral.add(new ProvidedStringSetting.Builder()
            .name("schematic").description("File from .minecraft/hydrino/schematics (.schem or .litematic).")
            .supplier(SchematicBuilder::listFiles).defaultValue("").build());
        private final Setting<Integer> offX = sgGeneral.add(new IntSetting.Builder().name("offset-x").defaultValue(0).sliderRange(-32, 32).build());
        private final Setting<Integer> offY = sgGeneral.add(new IntSetting.Builder().name("offset-y").defaultValue(0).sliderRange(-32, 32).build());
        private final Setting<Integer> offZ = sgGeneral.add(new IntSetting.Builder().name("offset-z").defaultValue(0).sliderRange(-32, 32).build());
        private final Setting<Boolean> build = sgGeneral.add(new BoolSetting.Builder().name("build").description("Off = preview only.").defaultValue(true).build());

        private final Setting<Double> range = sgPlacement.add(new DoubleSetting.Builder().name("range").defaultValue(4.5).range(1, 6).sliderRange(1, 6).build());
        private final Setting<Integer> perTick = sgPlacement.add(new IntSetting.Builder().name("blocks-per-tick").description("Multi-place: blocks placed per batch.").defaultValue(4).range(1, 20).sliderRange(1, 20).build());
        private final Setting<Integer> delay = sgPlacement.add(new IntSetting.Builder().name("delay").description("Ticks between batches.").defaultValue(1).range(0, 20).sliderRange(0, 20).build());
        private final Setting<Boolean> rotate = sgPlacement.add(new BoolSetting.Builder().name("rotate").defaultValue(true).build());
        private final Setting<Boolean> silent = sgPlacement.add(new BoolSetting.Builder().name("silent-aim").defaultValue(true).visible(rotate::get).build());
        private final Setting<Boolean> autoWalk = sgPlacement.add(new BoolSetting.Builder().name("auto-walk").description("Walks toward the next block to build.").defaultValue(true).build());
        private final Setting<Boolean> walkJump = sgPlacement.add(new BoolSetting.Builder().name("auto-jump").description("Jumps over obstacles while walking.").defaultValue(true).visible(autoWalk::get).build());

        private final Setting<Boolean> ghost = sgRender.add(new BoolSetting.Builder().name("ghost").defaultValue(true).build());
        private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").defaultValue(ShapeMode.Both).build());
        private final Setting<SettingColor> side = sgRender.add(new ColorSetting.Builder().name("side-color").defaultValue(new SettingColor(90, 170, 255, 40)).build());
        private final Setting<SettingColor> line = sgRender.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(90, 170, 255, 200)).build());
        private final Setting<Integer> maxGhost = sgRender.add(new IntSetting.Builder().name("max-ghost-blocks").defaultValue(1500).sliderRange(100, 5000).build());

        private Map<BlockPos, Block> blocks = new HashMap<>();
        private BlockPos origin;
        private int timer;

        public SchematicBuilder() {
            super(HydrinoAddon.CATEGORY, "schematic-builder", "Builds .schem files with a ghost preview. Origin = where you enable it.");
        }

        private static String[] listFiles() {
            try (Stream<Path> s = Files.list(HydrinoAddon.SCHEMATICS)) {
                return s.map(p -> p.getFileName().toString()).filter(n -> (n.endsWith(".schem") || n.endsWith(".litematic"))).sorted().toArray(String[]::new);
            } catch (IOException e) { return new String[0]; }
        }

        @Override
        public void onActivate() {
            blocks.clear();
            try {
                blocks = SchematicLoader.load(HydrinoAddon.SCHEMATICS.resolve(schematic.get()));
                origin = mc.player.getBlockPos();
                info("Loaded %d blocks.", blocks.size());
            } catch (Exception e) {
                error("Couldn't load '%s' (put it in hydrino/schematics).", schematic.get());
                toggle();
            }
        }

        private BlockPos world(BlockPos rel) { return origin.add(rel).add(offX.get(), offY.get(), offZ.get()); }

        @EventHandler
        private void onRender(Render3DEvent event) {
            if (!ghost.get() || origin == null) return;
            int n = 0;
            for (BlockPos rel : blocks.keySet()) {
                BlockPos p = world(rel);
                if (mc.world.getBlockState(p).getBlock() == blocks.get(rel)) continue;
                event.renderer.box(p, side.get(), line.get(), shape.get(), 0);
                if (++n >= maxGhost.get()) break;
            }
        }

        @EventHandler
        private void onTick(TickEvent.Pre event) {
            if (!build.get() || origin == null) { stopWalk(); return; }
            if (timer-- > 0) return;
            timer = delay.get();

            double r2 = range.get() * range.get();
            List<BlockPos> todo = new ArrayList<>();
            for (var e : blocks.entrySet()) {
                BlockPos p = world(e.getKey());
                if (p.getSquaredDistance(mc.player.getEyePos()) > r2) continue;
                if (!mc.world.getBlockState(p).isReplaceable()) continue;
                todo.add(p);
            }
            todo.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> p.getSquaredDistance(mc.player.getEyePos())));

            int placed = 0;
            for (BlockPos p : todo) {
                if (placed >= perTick.get()) break;
                Block want = blocks.get(p.subtract(origin).subtract(new BlockPos(offX.get(), offY.get(), offZ.get())));
                if (want == null) continue;
                FindItemResult item = InvUtils.find(want.asItem());
                if (!item.found()) continue;
                if (rotate.get()) Aim.at(p, silent.get(), () -> BlockUtils.place(p, item, false, 0));
                else BlockUtils.place(p, item, false, 0);
                placed++;
            }

            if (!autoWalk.get()) { stopWalk(); return; }

            // nearest block we still need to place and have items for
            Map<Block, Boolean> have = new HashMap<>();
            BlockPos target = null;
            double best = Double.MAX_VALUE;
            for (var e : blocks.entrySet()) {
                BlockPos p = world(e.getKey());
                if (!mc.world.getBlockState(p).isReplaceable()) continue;
                if (!have.computeIfAbsent(e.getValue(), b -> InvUtils.find(b.asItem()).found())) continue;
                double d = p.getSquaredDistance(mc.player.getEyePos());
                if (d < best) { best = d; target = p; }
            }

            double reach = Math.max(1.0, range.get() - 0.7);
            if (target == null || best <= reach * reach) { stopWalk(); return; }

            mc.player.setYaw((float) Rotations.getYaw(Vec3d.ofCenter(target)));
            mc.options.forwardKey.setPressed(true);
            mc.options.jumpKey.setPressed(walkJump.get() && mc.player.horizontalCollision && mc.player.isOnGround());
        }

        private void stopWalk() {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }

        @Override
        public void onDeactivate() { stopWalk(); }
    }

    public static class HydrinoAutoFarm extends Module {
        private final SettingGroup sgGeneral = settings.getDefaultGroup();
        private final SettingGroup sgRender = settings.createGroup("Render");

        private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder().name("range").defaultValue(4.5).range(1, 6).sliderRange(1, 6).build());
        private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder().name("actions-per-tick").defaultValue(2).range(1, 10).sliderRange(1, 10).build());
        private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder().name("delay").defaultValue(0).range(0, 20).sliderRange(0, 20).build());
        private final Setting<Boolean> replant = sgGeneral.add(new BoolSetting.Builder().name("replant").defaultValue(true).build());
        private final Setting<Boolean> wart = sgGeneral.add(new BoolSetting.Builder().name("nether-wart").defaultValue(true).build());
        private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder().name("rotate").defaultValue(true).build());
        private final Setting<Boolean> silent = sgGeneral.add(new BoolSetting.Builder().name("silent-aim").defaultValue(true).visible(rotate::get).build());

        private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder().name("highlight").defaultValue(true).build());
        private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").defaultValue(ShapeMode.Both).build());
        private final Setting<SettingColor> side = sgRender.add(new ColorSetting.Builder().name("side-color").defaultValue(new SettingColor(0, 255, 0, 40)).build());
        private final Setting<SettingColor> line = sgRender.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(0, 255, 0, 220)).build());

        private final List<BlockPos> ready = new ArrayList<>();
        private final Map<BlockPos, Item> pending = new LinkedHashMap<>();
        private int timer;

        public HydrinoAutoFarm() {
            super(HydrinoAddon.CATEGORY, "auto-farm", "Boxes grown crops, breaks them and replants.");
        }

        private boolean mature(BlockState s) {
            Block b = s.getBlock();
            if (b instanceof CropBlock c) return c.isMature(s);
            return wart.get() && b instanceof NetherWartBlock && s.get(NetherWartBlock.AGE) >= 3;
        }

        private Item seed(Block b) {
            if (b == Blocks.WHEAT) return Items.WHEAT_SEEDS;
            if (b == Blocks.CARROTS) return Items.CARROT;
            if (b == Blocks.POTATOES) return Items.POTATO;
            if (b == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
            if (b == Blocks.NETHER_WART) return Items.NETHER_WART;
            return null;
        }

        private void act(BlockPos p, Runnable r) {
            if (rotate.get()) Aim.at(p, silent.get(), r); else r.run();
        }

        @EventHandler
        private void onTick(TickEvent.Pre event) {
            ready.clear();
            int r = (int) Math.ceil(range.get());
            double r2 = range.get() * range.get();
            BlockPos c = mc.player.getBlockPos();
            for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                BlockPos p = c.add(x, y, z);
                if (p.getSquaredDistance(mc.player.getEyePos()) > r2) continue;
                if (mature(mc.world.getBlockState(p))) ready.add(p);
            }
            ready.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(mc.player.getEyePos())));

            if (timer-- > 0) return;
            timer = delay.get();
            int acts = 0;

            // replant first
            Iterator<Map.Entry<BlockPos, Item>> it = pending.entrySet().iterator();
            while (it.hasNext() && acts < perTick.get()) {
                var e = it.next(); BlockPos p = e.getKey();
                BlockState s = mc.world.getBlockState(p);
                if (s.isAir()) {
                    FindItemResult f = InvUtils.find(e.getValue());
                    it.remove();
                    if (f.found()) { act(p, () -> BlockUtils.place(p, f, false, 0)); acts++; }
                } else if (!(s.getBlock() instanceof CropBlock || s.getBlock() instanceof NetherWartBlock)) it.remove();
            }

            for (BlockPos p : ready) {
                if (acts >= perTick.get()) break;
                Item seed = seed(mc.world.getBlockState(p).getBlock());
                act(p, () -> BlockUtils.breakBlock(p, true));
                if (replant.get() && seed != null) pending.put(p, seed);
                acts++;
            }
        }

        @EventHandler
        private void onRender(Render3DEvent event) {
            if (!render.get()) return;
            for (BlockPos p : ready) event.renderer.box(p, side.get(), line.get(), shape.get(), 0);
        }

        @Override public void onDeactivate() { ready.clear(); pending.clear(); }
    }

    public static class SixB6TAutoLogin extends Module {
        private final SettingGroup sg = settings.getDefaultGroup();
        private final Setting<String> password = sg.add(new StringSetting.Builder().name("password").defaultValue("").build());
        private final Setting<Boolean> register = sg.add(new BoolSetting.Builder().name("use-register").description("Send /register pw pw instead of /login.").defaultValue(false).build());
        private final Setting<Integer> delay = sg.add(new IntSetting.Builder().name("delay-ticks").defaultValue(60).range(0, 400).sliderRange(0, 200).build());
        private final Setting<Boolean> only6b6t = sg.add(new BoolSetting.Builder().name("only-on-6b6t").defaultValue(true).build());

        private int countdown = -1;

        public SixB6TAutoLogin() { super(HydrinoAddon.CATEGORY, "6b6t-auto-login", "Logs in automatically when you join."); }

        @EventHandler
        private void onJoin(GameJoinedEvent e) {
            if (only6b6t.get() && (mc.getCurrentServerEntry() == null || !mc.getCurrentServerEntry().address.contains("6b6t"))) return;
            countdown = delay.get();
        }

        @EventHandler
        private void onTick(TickEvent.Post e) {
            if (countdown < 0 || --countdown >= 0 || password.get().isEmpty()) return;
            ChatUtils.sendPlayerMsg(register.get() ? "/register " + password.get() + " " + password.get() : "/login " + password.get());
            countdown = -1;
        }
    }

    public static class SixB6TChatFilter extends Module {
        private final SettingGroup sg = settings.getDefaultGroup();
        private final Setting<List<String>> words = sg.add(new StringListSetting.Builder().name("blocked-words").description("Messages containing these are hidden.").defaultValue(List.of("discord.gg", "www.", "http")).build());
        private final Setting<Boolean> caseSensitive = sg.add(new BoolSetting.Builder().name("case-sensitive").defaultValue(false).build());

        public SixB6TChatFilter() { super(HydrinoAddon.CATEGORY, "6b6t-chat-filter", "Hides spam and ads in chat."); }

        @EventHandler
        private void onMsg(ReceiveMessageEvent e) {
            String m = e.getMessage().getString();
            if (!caseSensitive.get()) m = m.toLowerCase();
            for (String w : words.get()) {
                if (w.isEmpty()) continue;
                if (m.contains(caseSensitive.get() ? w : w.toLowerCase())) { e.cancel(); return; }
            }
        }
    }
}
