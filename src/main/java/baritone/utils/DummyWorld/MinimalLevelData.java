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

import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/**
 * Minimal level data - only provides what's needed for block placement logic.
 */

public class MinimalLevelData implements WritableLevelData {
    private final GameRules gameRules;

    public MinimalLevelData() {
        this.gameRules = new GameRules();
    }

    // === ESSENTIAL DATA FOR PLACEMENT LOGIC ===
    @Override
    public Difficulty getDifficulty() { return Difficulty.PEACEFUL; }

    @Override
    public GameRules getGameRules() { return gameRules; }

    // === MINIMAL IMPLEMENTATIONS ===
    @Override public int getXSpawn() { return 0; }
    @Override public int getYSpawn() { return 64; }
    @Override public int getZSpawn() { return 0; }
    @Override public float getSpawnAngle() { return 0.0f; }
    @Override public long getGameTime() { return 0; }
    @Override public long getDayTime() { return 6000; }
    @Override public boolean isThundering() { return false; }
    @Override public boolean isRaining() { return false; }
    @Override public boolean isHardcore() { return false; }
    @Override public boolean isDifficultyLocked() { return false; }

    // === NO-OP SETTERS ===
    @Override public void setXSpawn(int x) {}
    @Override public void setYSpawn(int y) {}
    @Override public void setZSpawn(int z) {}
    @Override public void setSpawnAngle(float angle) {}
    @Override public void setSpawn(BlockPos spawnPoint, float spawnAngle) {}
    @Override public void setRaining(boolean raining) {}
}
