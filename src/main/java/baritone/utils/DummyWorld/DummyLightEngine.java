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

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;

/**
 * Dummy light engine that does nothing - no lighting calculations needed for placement logic.
 */
public class DummyLightEngine extends LevelLightEngine {

    public DummyLightEngine() {
        super(new LightChunkGetter() {
            @Override
            public @Nullable BlockGetter getChunkForLighting(int chunkX, int chunkZ) {
                return null;
            }

            @Override
            public BlockGetter getLevel() {
                return null;
            }
        }, false, false); // No chunk source, no lighting
    }

    // Override all lighting methods to do nothing
    @Override
    public void checkBlock(BlockPos pos) {}

    @Override
    public void onBlockEmissionIncrease(BlockPos pos, int level) {}

    @Override
    public boolean hasLightWork() { return false; }

    @Override
    public int runUpdates(int maxUpdates, boolean updateSkyLight, boolean updateBlockLight) { return 0; }
}
