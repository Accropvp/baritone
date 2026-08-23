/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.DummyWorld;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;

/**
 * Minimal chunk source - provides empty chunks with no lighting calculations.
 */
public class MinimalChunkSource extends ChunkSource {
    private final Level level;
    private final DummyLightEngine lightEngine;

    public MinimalChunkSource(Level level) {
        this.level = level;
        this.lightEngine = new DummyLightEngine(); // Minimal light engine
    }

    @Override
    @Nullable
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus requiredStatus, boolean load) {
        // Return empty chunk - all air blocks
        return new LevelChunk(level, new net.minecraft.world.level.ChunkPos(chunkX, chunkZ));
    }

    @Override
    public void tick(BooleanSupplier hasTimeLeft, boolean tickChunks) {
        // No ticking needed
    }

    @Override
    public String gatherStats() {
        return "MinimalChunkSource: Logic-only chunks";
    }

    @Override
    public int getLoadedChunksCount() {
        return 0;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return lightEngine;
    }

    @Override
    public net.minecraft.world.level.BlockGetter getLevel() {
        return level;
    }
}
