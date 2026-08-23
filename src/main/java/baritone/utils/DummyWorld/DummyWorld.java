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

import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Minimal dummy world for block placement logic testing only.
 * No rendering, no entities, no sounds, no lighting - just pure block placement logic.
 */

public class DummyWorld extends Level {
    private final MinimalChunkSource chunkSource;
    private final RegistryAccess registryAccess;
    // Add block storage
    private final Map<BetterBlockPos, BlockState> placedBlocks = new HashMap<>();

    public DummyWorld() {
        super(
                new MinimalLevelData(),
                Level.OVERWORLD,
                Minecraft.getInstance().level.registryAccess(),
                Minecraft.getInstance().level.dimensionTypeRegistration(),
                () -> Minecraft.getInstance().getProfiler(),
                false, // isClientSide
                false, // isDebug
                0L, // biomeZoomSeed (irrelevant)
                1000000 // maxChainedNeighborUpdates
        );

        this.registryAccess = Minecraft.getInstance().level.registryAccess();
        this.chunkSource = new MinimalChunkSource(this);
    }

    @Override
    public ChunkSource getChunkSource() {
        return chunkSource;
    }

    // === LOGIC-CRITICAL OVERRIDES ===
    // These are essential for block placement logic to work correctly

    // Override setBlock to actually store blocks
    @Override
    public boolean setBlock(@NotNull BlockPos pos, @NotNull BlockState state, int flags) {
        return setBlock(new BetterBlockPos(pos), state, flags);
    }

    public boolean setBlock(@NotNull BetterBlockPos pos, @NotNull BlockState state, int flags) {
        placedBlocks.put(pos, state);
        return true;
    }

    // Override getBlockState to return stored blocks
    @Override
    public @NotNull BlockState getBlockState(@NotNull BlockPos pos) {
        return getBlockState(new BetterBlockPos(pos));
    }

    public @NotNull BlockState getBlockState(@NotNull BetterBlockPos pos) {
        return placedBlocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    // Add utility methods
    public void clearBlocks() {
        placedBlocks.clear();
    }

    public boolean hasBlockAt(BetterBlockPos pos) {
        return placedBlocks.containsKey(pos);
    }

    public Set<BlockPos> getPlacedPositions() {
        return new HashSet<>(placedBlocks.keySet());
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return true; // Always "loaded" for placement logic
    }

    @Override
    public boolean hasChunkAt(BlockPos pos) {
        return true; // Always have chunks for placement logic
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return null;
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return true; // Always have chunks for placement logic
    }

    @Override
    public @NotNull Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        // Return plains biome for any biome-dependent placement logic
        return registryAccess.registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getHolderOrThrow(Biomes.PLAINS);
    }

    // === NO-OP OVERRIDES ===
    // These are not needed for placement logic, so we make them do nothing

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        // No-op: No rendering updates needed
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public void playSound(@Nullable Player player, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch) {
        // No-op: No sounds needed
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
        // No-op: No sounds needed
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public void playSeededSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) {
        // No-op: No sounds needed
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {
        // No-op: No sounds needed
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public void gameEvent(@Nullable Entity entity, GameEvent event, BlockPos pos) {
        // No-op: No game events needed for placement logic
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public void gameEvent(GameEvent event, BlockPos pos, GameEvent.Context context) {
        // No-op: No game events needed for placement logic
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public void gameEvent(GameEvent event, Vec3 position, GameEvent.Context context) {
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
        // No-op: No level events needed for placement logic
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public @NotNull List<Entity> getEntities(@Nullable Entity except, AABB area, java.util.function.Predicate<? super Entity> predicate) {
        return Collections.emptyList(); // No entities
    }

    @Override
    public <T extends Entity> @NotNull List<T> getEntities(EntityTypeTest<Entity, T> entityTypeTest, AABB area, java.util.function.Predicate<? super T> predicate) {
        return Collections.emptyList(); // No entities
    }

    @Override
    public @NotNull List<? extends Player> players() {
        return Collections.emptyList(); // No players
    }

    @Override
    public float getShade(net.minecraft.core.Direction direction, boolean shaded) {
        return 1.0f; // Full brightness (not used for placement logic)
    }

    @Override
    public @NotNull LevelLightEngine getLightEngine() {
        return chunkSource.getLightEngine();
    }

    @Override
    public @NotNull WorldBorder getWorldBorder() {
        return new WorldBorder(); // Default border
    }

    @Override
    public @NotNull Scoreboard getScoreboard() {
        return new Scoreboard(); // Empty scoreboard
    }

    // Additional abstract methods that need implementation in 1.19.4


    @Override
    public net.minecraft.world.ticks.@NotNull LevelTickAccess<Block> getBlockTicks() {
        return new DummyTickAccess<>(); // No block ticking needed
    }

    @Override
    public net.minecraft.world.ticks.@NotNull LevelTickAccess<Fluid> getFluidTicks() {
        return new DummyTickAccess<>(); // No fluid ticking needed
    }

    @Override
    public @NotNull String gatherChunkSourceStats() {
        return chunkSource.gatherStats();
    }

    @Override
    @Nullable
    public Entity getEntity(int id) {
        return null; // No entities in dummy world
    }

    @Override
    public net.minecraft.world.level.saveddata.maps.MapItemSavedData getMapData(String mapName) {
        return null; // No map data needed
    }

    @Override
    public void setMapData(String mapName, net.minecraft.world.level.saveddata.maps.MapItemSavedData data) {
        // No-op: No map data storage needed
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public int getFreeMapId() {
        return 0; // No maps in dummy world
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
        // No-op: No block breaking animation needed
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public net.minecraft.world.item.crafting.@NotNull RecipeManager getRecipeManager() {
        return Minecraft.getInstance().level.getRecipeManager(); // Use real recipe manager
    }

    @Override
    protected net.minecraft.world.level.entity.@NotNull LevelEntityGetter<Entity> getEntities() {
        // Return empty entity getter
        return new DummyEntityGetter();
    }
}

/**
 * Dummy tick access implementation - no ticking needed for placement logic.
 */
class DummyTickAccess<T> implements net.minecraft.world.ticks.LevelTickAccess<T> {

    @Override
    public boolean hasScheduledTick(BlockPos pos, T object) {
        return false;
    }

    @Override
    public void schedule(net.minecraft.world.ticks.ScheduledTick<T> scheduledTick) {
        // No-op: No scheduling needed
        throw new UnsupportedOperationException("Not needed in DummyLevel");
    }

    @Override
    public boolean willTickThisTick(BlockPos pos, T object) {
        return false;
    }

    @Override
    public int count() {
        return 0;
    }
}