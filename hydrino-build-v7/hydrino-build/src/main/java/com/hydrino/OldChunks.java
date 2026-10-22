package com.hydrino;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Old-chunk finder (chunk trails). Chunks that were already loaded before you arrived (other players walked/flew there)
 * show up as OLD; freshly generated ones as NEW. Detection is fluid based: a chunk that arrives with flowing water/lava
 * was already ticked by the server (old); a chunk whose fluids start flowing after you load it was just generated (new).
 */
public class OldChunks extends Module {
    SettingGroup g = settings.getDefaultGroup();
    Setting<Boolean> showOld = Features.bool(g, "show-old", true), showNew = Features.bool(g, "show-new", false);
    Setting<Integer> range = Features.num(g, "render-range-chunks", 48, 8, 128), maxDraw = Features.num(g, "max-drawn", 3000, 200, 10000);
    Setting<SettingColor> oldCol = Features.color(g, "old-color", 0, 255, 90), newCol = Features.color(g, "new-color", 255, 60, 60);

    final Set<Long> old = ConcurrentHashMap.newKeySet();
    final Set<Long> neu = ConcurrentHashMap.newKeySet();
    final Queue<int[]> queue = new ConcurrentLinkedQueue<>();
    String dimKey = "";

    public OldChunks() { super(HydrinoAddon.CATEGORY, "old-chunks", "Finds chunks that were loaded before (player trails/bases) vs brand-new chunks."); }

    static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xffffffffL); }

    @Override public void onDeactivate() { old.clear(); neu.clear(); queue.clear(); }
    @EventHandler private void onJoin(GameJoinedEvent e) { old.clear(); neu.clear(); queue.clear(); dimKey = ""; }

    @EventHandler private void onPacket(PacketEvent.Receive e) {
        if (e.packet instanceof ChunkDataS2CPacket p) queue.add(new int[]{p.getChunkX(), p.getChunkZ(), 0});
        else if (e.packet instanceof BlockUpdateS2CPacket p) markNew(p.getPos(), p.getState());
        else if (e.packet instanceof ChunkDeltaUpdateS2CPacket p) p.visitUpdates(this::markNew);
    }

    private void markNew(BlockPos pos, BlockState s) {
        FluidState f = s.getFluidState();
        if (f.isEmpty() || f.isStill()) return;
        long k = key(pos.getX() >> 4, pos.getZ() >> 4);
        if (!old.contains(k)) neu.add(k);
    }

    @EventHandler private void onTick(TickEvent.Post e) {
        if (!Features.ready()) return;
        String d = Features.dim();
        if (!d.equals(dimKey)) { dimKey = d; old.clear(); neu.clear(); queue.clear(); }

        int n = 0; int[] c;
        while (n++ < 4 && (c = queue.poll()) != null) {
            WorldChunk ch = mc.world.getChunkManager().getWorldChunk(c[0], c[1]);
            if (ch == null) { if (c[2]++ < 40) queue.add(c); continue; }
            long k = key(c[0], c[1]);
            if (old.contains(k) || neu.contains(k)) continue;
            scan(ch, k);
        }
    }

    private void scan(WorldChunk ch, long k) {
        for (ChunkSection sec : ch.getSectionArray()) {
            if (sec == null || sec.isEmpty() || !sec.hasAny(s -> !s.getFluidState().isEmpty())) continue;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                FluidState f = sec.getFluidState(x, y, z);
                if (!f.isEmpty() && !f.isStill()) { old.add(k); return; }
            }
        }
    }

    @EventHandler private void onRender(Render3DEvent e) {
        if (!Features.ready()) return;
        int px = mc.player.getBlockX() >> 4, pz = mc.player.getBlockZ() >> 4, r = range.get(), drawn = 0;
        double y = mc.player.getY() - 1;
        if (showOld.get()) for (long k : old) { if (drawn > maxDraw.get()) break; drawn += draw(e, k, px, pz, r, y, oldCol.get()); }
        if (showNew.get()) for (long k : neu) { if (drawn > maxDraw.get()) break; drawn += draw(e, k, px, pz, r, y, newCol.get()); }
    }

    private int draw(Render3DEvent e, long k, int px, int pz, int r, double y, SettingColor c) {
        int cx = (int) (k >> 32), cz = (int) k;
        if (Math.abs(cx - px) > r || Math.abs(cz - pz) > r) return 0;
        e.renderer.box(cx * 16, y, cz * 16, cx * 16 + 16, y + 0.1, cz * 16 + 16, new SettingColor(c.r, c.g, c.b, 45), c, ShapeMode.Both, 0);
        return 1;
    }
}
