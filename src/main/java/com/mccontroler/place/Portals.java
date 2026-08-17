package com.mccontroler.place;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Finds the nearest nether portal in the loaded world.
 *
 * <p>Scanning every block in range would be millions of lookups. Instead each chunk section
 * carries a palette of the block states it contains, so {@code maybeHas} rejects the vast
 * majority of sections outright and only the handful that might hold portal blocks are searched.
 */
public final class Portals {

    /** How far out to look, in chunks. Beyond the render distance nothing is loaded anyway. */
    private static final int DEFAULT_CHUNK_RADIUS = 12;

    private Portals() {
    }

    public static BlockPos findNearest() {
        return findNearest(DEFAULT_CHUNK_RADIUS);
    }

    /** Nearest portal block to the player, or null if none is loaded within the radius. */
    public static BlockPos findNearest(int chunkRadius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }

        BlockPos origin = mc.player.blockPosition();
        // Chunk coordinates are just block coordinates shifted by four.
        int centreX = origin.getX() >> 4;
        int centreZ = origin.getZ() >> 4;
        int minY = mc.level.getMinY();

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        // Spiral outward so a near hit ends the search before distant chunks are touched.
        for (int radius = 0; radius <= chunkRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;   // only the ring at this radius
                    }
                    ChunkAccess chunk = mc.level.getChunkSource()
                            .getChunk(centreX + dx, centreZ + dz, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;   // not loaded
                    }

                    LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        LevelChunkSection section = sections[i];
                        if (section == null || section.hasOnlyAir()
                                || !section.maybeHas(state -> state.is(Blocks.NETHER_PORTAL))) {
                            continue;
                        }

                        int baseX = (centreX + dx) << 4;
                        int baseZ = (centreZ + dz) << 4;
                        int baseY = minY + (i << 4);

                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    if (!section.getBlockState(x, y, z).is(Blocks.NETHER_PORTAL)) {
                                        continue;
                                    }
                                    BlockPos at = new BlockPos(baseX + x, baseY + y, baseZ + z);
                                    double distance = at.distSqr(origin);
                                    if (distance < bestDistance) {
                                        bestDistance = distance;
                                        best = at;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // A portal found in this ring cannot be beaten by anything further out.
            if (best != null) {
                return best;
            }
        }
        return best;
    }
}
