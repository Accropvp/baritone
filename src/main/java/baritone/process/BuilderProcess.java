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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.schematic.SubstituteSchematic;
import baritone.api.schematic.RotatedSchematic;
import baritone.api.schematic.MirroredSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.DummyWorld.BlockPlacementAnalyzer;
import baritone.utils.DummyWorld.BlockPlacementBehavior;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            );

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    public int stopAtHeight = 0;

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
    }

    public void resume() {
        paused = false;
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (format.isEmpty()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.getA());
                build(schematic.getA().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        double reach = ctx.playerController().getBlockReachDistance();
        int ceilReach = (int) Math.ceil(reach);
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        for (int dx = -ceilReach; dx <= ceilReach; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= ceilReach; dy++) {
                for (int dz = -ceilReach; dz <= ceilReach; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false) && shouldBreakByProperty(curr, desired)) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            return Optional.of(new Tuple<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean shouldBreakByProperty(BlockState current, BlockState desired){
        if (current.getBlock() != desired.getBlock()){ return true; }
        Optional<SlabType> desiredSlabType = desired.getOptionalValue(BlockStateProperties.SLAB_TYPE);
        Optional<SlabType> currentSlabType = current.getOptionalValue(BlockStateProperties.SLAB_TYPE);

        if (desiredSlabType.isPresent()){
            if (currentSlabType.isEmpty()) { return true; }
            if (desiredSlabType.get() == SlabType.DOUBLE) { return false; }
            return desiredSlabType.get() != currentSlabType.get();
        }
        Map<String, String> desiredProperties = BlockPlacementAnalyzer.mapProperties(desired);
        Map<Direction, Boolean> desiredDirectionPropertyLinker = createDirectionPropertyLinker(desiredProperties);

        if (!desiredDirectionPropertyLinker.isEmpty()){
            BlockPlacementBehavior blockBehavior = BlockPlacementAnalyzer.getDirectionProperty(desired.getBlock());
            if (blockBehavior == BlockPlacementBehavior.CONNECTS_TO_NEIGHBORS){
                return false;
            } else if (blockBehavior == BlockPlacementBehavior.FACES_OPPOSITE_FROM_HIT_FACE){
                Map<String, String> currentProperties = BlockPlacementAnalyzer.mapProperties(current);
                Map<Direction, Boolean> currentDirectionPropertyLinker = createDirectionPropertyLinker(currentProperties);
                for (Map.Entry<Direction, Boolean> currentDirectionProperty : currentDirectionPropertyLinker.entrySet()){
                    if (currentDirectionProperty.getValue() && !desiredDirectionPropertyLinker.get(currentDirectionProperty.getKey())) { return true; }
                }
                return false;
            }
        }
        return true;
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
        }
    }

    private Optional<Placement> searchForBlockToPlaceNow(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        //TODO: center may not be right
        BetterBlockPos center = ctx.viewerPos();
        double reach = ctx.playerController().getBlockReachDistance();
        int ceilReach = (int) Math.ceil(reach);
        int maxDy = ceilReach;
        if (Baritone.settings().buildInLayers.value){
            maxDy = layer + origin.getY() - center.getY();
        }
        for (int dx = -ceilReach; dx <= ceilReach; dx++) {
            for (int dy = -ceilReach; dy <= maxDy; dy++) {
                for (int dz = -ceilReach; dz <= ceilReach; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);

                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false) && shouldPlaceByProperty(curr, desired)) {
                        desirableOnHotbar.add(desired);
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            return opt;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean shouldPlaceByProperty(BlockState current, BlockState desired){
        //TODO: direction properties is not handled properly

        if (current.isAir()) { return true; }
        if (desired.getProperties().isEmpty()){ return true; }

        if (current.getBlock() != desired.getBlock()) { return false; }

        Optional<SlabType> desiredSlabType = desired.getOptionalValue(BlockStateProperties.SLAB_TYPE);
        if (desiredSlabType.isPresent()){
            return desiredSlabType.get() == SlabType.DOUBLE;
        }
        Map<String, String> desiredProperties = BlockPlacementAnalyzer.mapProperties(desired);
        Map<Direction, Boolean> desiredDirectionPropertyLinker = createDirectionPropertyLinker(desiredProperties);

        if (!desiredDirectionPropertyLinker.isEmpty()){
            switch (BlockPlacementAnalyzer.getDirectionProperty(desired.getBlock())){
                case FACES_OPPOSITE_FROM_HIT_FACE -> {
                    Map<String, String> currentProperties = BlockPlacementAnalyzer.mapProperties(current);
                    Map<Direction, Boolean> currentDirectionPropertyLinker = createDirectionPropertyLinker(currentProperties);
                    //boolean needMore = false;
                    for (Map.Entry<Direction, Boolean> desiredDirectionProperty : desiredDirectionPropertyLinker.entrySet()){
                        //if (!desiredDirectionProperty.getValue() && currentDirectionPropertyLinker.get(desiredDirectionProperty.getKey())) { return false; }
                        if (desiredDirectionProperty.getValue() && !currentDirectionPropertyLinker.get(desiredDirectionProperty.getKey())) { return true; }
                    }
                    return false;
                }
                case CONNECTS_TO_NEIGHBORS -> {
                    return false;
                }
                default -> throw new IllegalStateException("this placement property shouldn't be with the direction property");
            }
        }
        return true;
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BetterBlockPos(x, y, z))) {
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                continue;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot, bsi.get0(x, y, z));
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot, BlockState current) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            // the state depends on the facing of the player sometimes
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    (BlockHitResult) result
            ) {}); // that {} gives us access to a protected constructor lmfao
            BlockState wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(meme);
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            if (wouldBePlaced == null) {
                continue;
            }
            if (!meme.canPlace()) {
                continue;
            }
            if (sameBlockstate(wouldBePlaced, current)){
                return OptionalInt.empty();
            }
            if (!desired.getProperties().isEmpty() && approxValid(wouldBePlaced, desired, true)){
                return OptionalInt.of(i);
            }
            if (valid(wouldBePlaced, desired, true)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        approxPlaceable = approxPlaceable(36);
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (Baritone.settings().buildInLayers.value) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (!recalc(bcc)) {
            if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            origin = new BlockPos(origin).offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        if (Baritone.settings().distanceTrim.value) {
            trim();
        }

        Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (toBreak.isPresent() && isSafeToCancel && ctx.player().isOnGround()) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().getB();
            BetterBlockPos pos = toBreak.get().getA();
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
            if (ctx.player().isCrouching()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlaceOption = searchForBlockToPlaceNow(bcc, desirableOnHotbar);
        if (toPlaceOption.isPresent() && isSafeToCancel && ctx.player().isOnGround() && ticks <= 0) {
            Placement toPlace = toPlaceOption.get();
            Rotation rot = toPlace.rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().selected = toPlace.hotbarSelection;
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if ((ctx.isLookingAt(toPlace.placeAgainst) && ((BlockHitResult) ctx.objectMouseOver()).getDirection().equals(toPlace.side)) || ctx.playerRotations().isReallyCloseTo(rot)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (itemApproxValid(approxPlaceable.get(i), desired, true)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (itemApproxValid(approxPlaceable.get(i), desired, true)) {
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        break outer;
                    }
                }
            }
        }

        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal != null){
            return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
        }
        goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
        if (goal != null){
            return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
        }
        if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
            logDirect("Skipping layer that I cannot construct! Layer #" + layer);
            layer++;
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
        paused = true;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (valid(bcc.bsi.get0(x, y, z), desired, false)) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<Pair<BetterBlockPos, BlockState>> specialPlaceable = new ArrayList<>();
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        Stream<BetterBlockPos> blockToAttribute;
        if (Baritone.settings().buildInLayers.value){
            blockToAttribute = incorrectPositions.stream().filter(pos -> pos.getY() <= layer + origin.getY());
        } else {
            blockToAttribute = incorrectPositions.stream();
        }
        blockToAttribute.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (containsBlockState(approxPlaceable, desired)) {
                    placeable.add(pos);
                } else if (approxPlaceableContainsBlockType(approxPlaceable, desired.getBlock()) && !desired.getProperties().isEmpty()) {
                    specialPlaceable.add(new Pair<>(pos, desired));
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        List<Goal> toPlace = new ArrayList<>();
        placeable.forEach(pos -> {
            if (!placeable.contains(pos.below()) && !placeable.contains(pos.below(2))) {
                toPlace.add(placementGoal(pos, bcc));
            }
        });
        List<Goal> specialToPlace = new ArrayList<>();
        for (Pair<BetterBlockPos, BlockState> special : specialPlaceable){
            Goal goal = specialPlacementGoal(bcc, special.second(), special.first());
            if (goal != null){
                specialToPlace.add(goal);
            }
        }
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));

        if (!specialToPlace.isEmpty()){
            return new JankyGoalComposite(new GoalComposite(specialToPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    private Map<Direction, BlockState> mapBlocksAround(BlockStateInterface bsi, BetterBlockPos pos){
        Map<Direction, BlockState> blocksAround = new HashMap<>();
        blocksAround.put(Direction.UP, bsi.get0(pos.above()));
        blocksAround.put(Direction.DOWN, bsi.get0(pos.below()));
        blocksAround.put(Direction.EAST, bsi.get0(pos.east()));
        blocksAround.put(Direction.WEST, bsi.get0(pos.west()));
        blocksAround.put(Direction.SOUTH, bsi.get0(pos.south()));
        blocksAround.put(Direction.NORTH, bsi.get0(pos.north()));
        return blocksAround;
    }

    private Goal specialPlacementGoal(BuilderCalculationContext bcc ,BlockState state, BetterBlockPos pos){
        Map<Direction, BlockState> blocksAround = mapBlocksAround(bcc.bsi, pos);
        Map<String, String> properties = BlockPlacementAnalyzer.mapProperties(state);

        Goal shortestSpecialGoal = getSpecialGoalFromFacingAndHalfProp(blocksAround, state, pos, bcc, properties);
        if (shortestSpecialGoal != null) {
            return shortestSpecialGoal;
        }
        shortestSpecialGoal = getSpecialGoalFromSlabType(blocksAround, state, pos, bcc.bsi, properties.getOrDefault("type", ""));
        if (shortestSpecialGoal != null){
            return shortestSpecialGoal;
        }
        shortestSpecialGoal = getSpecialGoalFromAxisProp(blocksAround, state, pos, bcc, properties.getOrDefault("axis", ""));
        if (shortestSpecialGoal != null) {
            return shortestSpecialGoal;
        }
        shortestSpecialGoal = getSpecialGoalFromDirProp(blocksAround, state, pos, bcc, properties);
        return shortestSpecialGoal;
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        return placementGoal(pos, bcc, Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP);
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc, Direction[] facings) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        for (Direction facing : facings) {
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && placementPlausible(pos, bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current))) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        return new GoalPlace(pos);
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    private Direction[] getDirectionsFromAxis(Direction.Axis axis){
        switch (axis){
            case X -> {
                return new Direction[] {Direction.EAST, Direction.WEST};
            }
            case Y -> {
                return new Direction[] {Direction.UP, Direction.DOWN};
            }
            case Z -> {
                return new Direction[] {Direction.NORTH, Direction.SOUTH};
            }
            default -> throw new Error("Invalid Axis");
        }
    }

    private Set<BetterBlockPos> getPossiblePosToBe(BlockStateInterface bsi, Map<Direction, Vec3> centerToAim, @Nullable Direction playerFacing, BetterBlockPos origin){
        // get possible position for the player to be for posing a special block (with the head as origin)
        Set<BetterBlockPos> possibleBlocksToBe = new LinkedHashSet<>();
        for (Map.Entry<Direction, Vec3> posToAim : centerToAim.entrySet()){
            ProjectedToPlaceHemisphere toPlace = new ProjectedToPlaceHemisphere(bsi, playerFacing, posToAim.getKey().getOpposite(), posToAim.getValue(), (float) ctx.playerController().getBlockReachDistance() - 0.5f);
            possibleBlocksToBe.addAll(toPlace.createShape());
        }
        possibleBlocksToBe.remove(origin); // do not add the position of the block to place
        return possibleBlocksToBe;
    }

    private Goal getShortestGoal(Set<BetterBlockPos> possibleBlocksToBe){
        // get the shortest goal for the player to be for posing a special block
        Goal shortestGoal = null;
        double bestHeuristic = Double.MAX_VALUE;
        for (BetterBlockPos blockToBe : possibleBlocksToBe){
            Goal goalToBe = new GoalBlock(blockToBe.below());
            if (goalToBe.heuristic() < bestHeuristic){
                shortestGoal = goalToBe;
                bestHeuristic = goalToBe.heuristic();
            }
        }
        return shortestGoal;
    }

    private Goal getSpecialGoalFromFacingAndHalfProp(Map<Direction, BlockState> blocksAround, BlockState state, BetterBlockPos pos, BuilderCalculationContext bcc, Map<String, String> properties){
        Pair<Map<Direction, Vec3>, Direction> specialPoint = getSpecialPointFromFacingAndHalfProp(blocksAround, state, pos, properties);
        if (specialPoint.first().isEmpty()){
            return null;
        }
        return getShortestGoal(getPossiblePosToBe(bcc.bsi, specialPoint.first(), specialPoint.second(), pos));
    }

    private static Map<Direction, Boolean> createDirectionPropertyLinker(Map<String, String> properties){
        Map<Direction, Boolean> directionPropertyLinker = new LinkedHashMap<>();
        for (Direction dir : Direction.values()){
            if (properties.containsKey(dir.toString())){
                directionPropertyLinker.put(dir, Boolean.parseBoolean(properties.get(dir.toString())));
            }
        }
        return directionPropertyLinker;
    }

    private Goal getSpecialGoalFromAxisProp(Map<Direction, BlockState> blocksAround, BlockState state, BetterBlockPos pos, BuilderCalculationContext bcc, String axisStr){
        if (axisStr.isEmpty()){ return null; }

        Direction.Axis axis =  Arrays.stream(Direction.Axis.values())
                .filter(h -> h.getSerializedName().equalsIgnoreCase(axisStr))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid axis: " + axisStr));

        BlockPlacementBehavior facingMode = BlockPlacementAnalyzer.getAxisProperty(state.getBlock());
        Map<Direction, Vec3> specialPoints = getSpecialPointFromAxisProp(blocksAround, facingMode, pos, axis);
        Set<BetterBlockPos> possibleBlockToBe = new LinkedHashSet<>();

        switch (facingMode){
            case FACES_PLAYER -> {
                for (Direction dir : getDirectionsFromAxis(axis)){
                    possibleBlockToBe.addAll(getPossiblePosToBe(bcc.bsi, specialPoints, dir, pos));
                }
            }
            case FACES_SAME_AS_HIT_FACE -> possibleBlockToBe.addAll(getPossiblePosToBe(bcc.bsi, specialPoints, null, pos));
        }
        return getShortestGoal(possibleBlockToBe);
    }

    private Goal getSpecialGoalFromSlabType(Map<Direction, BlockState> blocksAround, BlockState state, BetterBlockPos pos, BlockStateInterface bsi, String typeStr){
        Map<Direction, Vec3> specialPoints = getSpecialPointFromSlabType(blocksAround, state, pos, typeStr);
        if (specialPoints.isEmpty()){
            return null;
        }
        return getShortestGoal(getPossiblePosToBe(bsi, specialPoints, null, pos));
    }

    private Goal getSpecialGoalFromDirProp(Map<Direction, BlockState> blocksAround, BlockState state, BetterBlockPos pos, BuilderCalculationContext bcc, Map<String, String> props){

        Map<Direction, Boolean> directionPropertyLinker = createDirectionPropertyLinker(props);
        if (directionPropertyLinker.isEmpty()){
            return null;
        }
        BlockPlacementBehavior facingMode = BlockPlacementAnalyzer.getDirectionProperty(state.getBlock());

        Map<Direction, Vec3> centerToPlace = new LinkedHashMap<>();



        switch (facingMode){
            case FACES_OPPOSITE_FROM_HIT_FACE -> {
                for (Map.Entry<Direction, Boolean> link : directionPropertyLinker.entrySet()){
                    Direction dir = link.getKey();
                    if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()){
                        continue;
                    }
                    if (link.getValue()){
                        centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)));
                    }
                }
            }
            case CONNECTS_TO_NEIGHBORS -> {
                if (bcc.bsi.get0(pos).getBlock().equals(state.getBlock())){
                    return null;
                }
                return placementGoal(pos, bcc, new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST});
            }
            default -> throw new Error("(getSpecialGoalVecFromAxisProp) facingMode value is invalid, block: " + state.getBlock().toString() + " at: " + pos.toString() + " facingMode is : " + facingMode);
        }

        return getShortestGoal(getPossiblePosToBe(bcc.bsi, centerToPlace, null, pos));
    }

    private Pair<Map<Direction, Vec3>, Direction> getSpecialPointFromFacingAndHalfProp(Map<Direction, BlockState> blocksAround, BlockState state, BetterBlockPos pos, Map<String, String> properties){
        // determine a goal to place the player where he can pos the block depending on its facing and half properties
        String facingStr = properties.getOrDefault("facing", "");
        String halfStr = properties.getOrDefault("half", "");

        if (facingStr.isEmpty() && halfStr.isEmpty()){
            return new Pair<>(new LinkedHashMap<>(), null);
        }
        Direction playerFacing = null;
        Map<Direction, Vec3> centerToPlace = new LinkedHashMap<>();

        if (!facingStr.isEmpty()){
            Direction facing = Direction.byName(facingStr);
            assert facing != null;
            BlockPlacementBehavior facingMode = BlockPlacementAnalyzer.getFacingProperty(state.getBlock());
            switch (facingMode){
                case FACES_SAME_AS_HIT_FACE -> centerToPlace.put(facing, pos.getCenter().add(new Vec3(facing.getNormal().getX(), facing.getNormal().getY(), facing.getNormal().getZ()).scale(0.51)));
                case FACES_PLAYER -> {
                    for (Direction dir : Direction.values()){
                        if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()){
                            continue;
                        }
                        centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)));
                    }
                    centerToPlace.remove(facing);
                    playerFacing = facing.getOpposite();
                }
                case FACES_AWAY_FROM_PLAYER -> {
                    for (Direction dir : Direction.values()){
                        if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()){
                            continue;
                        }
                        centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)));
                    }
                    centerToPlace.remove(facing.getOpposite());
                    playerFacing = facing;
                }
                default -> throw new Error("(specialPlacementGoal) Block facing invalid");
            }
        } else {
            for (Direction dir : Direction.Plane.HORIZONTAL){
                if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()){
                    continue;
                }
                centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)));
            }

        }
        centerToPlace = modSpecialGoalVecFromHalfProp(centerToPlace, halfStr);
        return new Pair<>(centerToPlace, playerFacing);
    }

    private Map<Direction, Vec3> modSpecialGoalVecFromHalfProp(Map<Direction, Vec3> centerToAim, String halfStr){
        if (halfStr.isEmpty()){ return centerToAim; }

        Map<Direction, Vec3> centerToPlace = new HashMap<>(centerToAim);

        switch (halfStr){
            case "top" -> {
                centerToPlace.remove(Direction.DOWN);
                for (Direction dir : Direction.Plane.HORIZONTAL){
                    if (centerToPlace.containsKey(dir)){
                        centerToPlace.replace(dir, centerToPlace.get(dir).add(0, 0.25, 0));
                    }
                }
            }
            case "bottom" -> {
                centerToPlace.remove(Direction.UP);
                for (Direction dir : Direction.Plane.HORIZONTAL){
                    if (centerToPlace.containsKey(dir)){
                        centerToPlace.replace(dir, centerToPlace.get(dir).add(0, -0.25, 0));
                    }
                }
            }
        }
        return centerToPlace;
    }

    private Map<Direction, Vec3> getSpecialPointFromAxisProp(Map<Direction, BlockState> blocksAround, BlockPlacementBehavior facingMode, BetterBlockPos pos, Direction.Axis axis){
        // determine a goal to place the player where he can pos the block depending on its axis property

        Map<Direction, Vec3> centerToPlace = new LinkedHashMap<>();
        switch (facingMode){
            case FACES_PLAYER -> {
                for (Direction dir : Direction.values()){
                    if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()){
                        continue;
                    }
                    centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)));
                }
            }
            case FACES_SAME_AS_HIT_FACE -> {
                for (Direction dir : axis.getPlane()){
                    if (!axis.equals(dir.getAxis()) || blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()){
                        continue;
                    }
                    centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)));
                }
            }
            default -> throw new Error("(getSpecialGoalVecFromAxisProp) facingMode value is invalid");
        }
        return centerToPlace;
    }

    private Map<Direction, Vec3> getSpecialPointFromSlabType(Map<Direction, BlockState> blocksAround, BlockState state, BetterBlockPos pos, String typeStr){
        // determine a goal to place the player where he can pos the block depending on its directional (north, east, south, west, up, down) properties
        Map<Direction, Vec3> centerToPlace = new LinkedHashMap<>();
        if (typeStr.isEmpty()){ return centerToPlace; }

        switch (typeStr){
            case "top" -> {
                for (Direction dir : Direction.values()){
                    if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()) { continue; }
                    centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)).add(0,0.25,0));
                }
                centerToPlace.remove(Direction.DOWN);
            }
            case "bottom" -> {
                for (Direction dir : Direction.values()){
                    if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()) { continue; }
                    centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)).add(0,-0.25,0));
                }
                centerToPlace.remove(Direction.UP);
            }
            case "double" -> {
                Optional<SlabType> actualType = state.getOptionalValue(BlockStateProperties.SLAB_TYPE);
                if (actualType.isEmpty()){
                    for (Direction dir : Direction.Plane.HORIZONTAL){
                        if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()) { continue; }
                        centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)).add(0,-0.25,0));
                    }
                } else {
                    switch (actualType.get()){
                        case TOP -> {
                            for (Direction dir : Direction.Plane.HORIZONTAL){
                                if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()) { continue; }
                                centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)).add(0,-0.25,0));
                            }
                            Direction dir = Direction.UP;
                            centerToPlace.put(dir, pos.getCenter());
                        }
                        case BOTTOM -> {
                            for (Direction dir : Direction.Plane.HORIZONTAL){
                                if (blocksAround.get(dir).isAir() || blocksAround.get(dir).getMaterial().isLiquid()) { continue; }
                                centerToPlace.put(dir, pos.getCenter().add(new Vec3(dir.getNormal().getX(), dir.getNormal().getY(), dir.getNormal().getZ()).scale(0.51)).add(0,0.25,0));
                            }
                            Direction dir = Direction.DOWN;
                            centerToPlace.put(dir, pos.getCenter());
                        }
                        default -> throw new Error("invalid current slab type");
                    }
                }
            }
        }
        return centerToPlace;
    }

    private static class ProjectedToPlaceHemisphere{
        BlockStateInterface bsi;
        private final float radius;
        private final Vec3 center;
        private final Direction directionToFaceAsPlayer;
        private final Direction directionOfBlockFaceToHit;
        private final BetterBlockPos blockToHitPos;
        private final List<BetterBlockPos> blocksInSphere = new ArrayList<>();

        /**
         * Create an instance of this class
         * <p>
         * make a projected hemisphere of where the player can be to place a block.
         * <p/>
         *
         *
         * @param bsi                           the block state interface for real world data
         * @param directionToFace               The direction the player has to face
         * @param directionOfBlockFaceToHit     The face the ray has to hit
         * @param PointToHit                    Position of the point to hit as the player
         * @param radius                        The length of the player look ray
         */
        ProjectedToPlaceHemisphere(BlockStateInterface bsi, @Nullable Direction directionToFace, @Nullable Direction directionOfBlockFaceToHit, Vec3 PointToHit, float radius){
            // the center need to be in the block to hit ideally close to the face to hit
            this.bsi = bsi;
            this.radius = radius;
            this.directionToFaceAsPlayer = directionToFace;
            this.directionOfBlockFaceToHit = directionOfBlockFaceToHit;
            this.blockToHitPos = new BetterBlockPos(PointToHit.x, PointToHit.y, PointToHit.z);
            this.center = PointToHit;
        }

        public List<BetterBlockPos> createShape(){
            // should be slower but keeping it because I'm not sure
            float minX = -radius;
            float minY = -radius;
            float minZ = -radius;
            float maxX = radius;
            float maxY = radius;
            //float maxY = Baritone.settings().buildInLayers.value ? Math.min(layer + origin.getY() - blockToHitPos.getY() + 2, radius) : radius;
            float maxZ = radius;

            if (directionOfBlockFaceToHit != null){
                switch (directionOfBlockFaceToHit){
                    case UP -> minY = 0;
                    case DOWN -> maxY = 0;
                    case EAST -> minX = 0;
                    case WEST -> maxX = 0;
                    case SOUTH -> minZ = 0;
                    case NORTH -> maxZ = 0;
                    default -> throw new Error("(ProjectedHemisphere.createShape) Invalid direction");
                }
            }

            Function<Vec3, Boolean> isFacingOK;
            if (directionToFaceAsPlayer == null){
                isFacingOK = vec -> true;
            } else {
                switch (directionToFaceAsPlayer){
                    case UP -> maxY = 0;
                    case DOWN -> minY = 0;
                    case EAST -> maxX = 0;
                    case WEST -> minX = 0;
                    case SOUTH -> maxZ = 0;
                    case NORTH -> minZ = 0;
                }

                Direction.Axis axisAsPlayer = directionToFaceAsPlayer.getAxis();
                switch (axisAsPlayer){
                    case X -> isFacingOK = vec -> Math.abs(vec.x) > Math.max(Math.abs(vec.y), Math.abs(vec.z));
                    case Y -> isFacingOK = vec -> Math.abs(vec.y) > Math.max(Math.abs(vec.x), Math.abs(vec.z));
                    case Z -> isFacingOK = vec -> Math.abs(vec.z) > Math.max(Math.abs(vec.x), Math.abs(vec.y));
                    default -> throw new IllegalStateException("(ProjectedHemisphere.createShape) Invalid Axis");
                }
            }

            for (float x = minX; x <= maxX; x++) {
                double WorldX = x + center.x;
                for (float y = minY; y <= maxY; y++) {
                    double WorldY = y + center.y;
                    for (float z = minZ; z <= maxZ; z++) {
                        double WorldZ = z + center.z;
                        if (center.distanceToSqr(WorldX, WorldY, WorldZ) > radius * radius){
                            continue;
                        }
                        BetterBlockPos pos = new BetterBlockPos(WorldX, WorldY, WorldZ);
                        Vec3 blockToCenter = center.add(pos.getCenter().reverse());
                        if (!isFacingOK.apply(blockToCenter)){
                            continue;
                        }
                        BlockHitResult hit = new BlockRaycast(bsi ,pos.getCenter(), blockToCenter, 0.001).rayCastFirstOccurrence(true);
                        if (hit == null){
                            throw new Error("no block hit, start : " + pos.getCenter() + ", end : " + center);
                        }
                        if (hit.getDirection().equals(directionOfBlockFaceToHit) && hit.getBlockPos().equals(blockToHitPos)){
                            blocksInSphere.add(pos);
                        }

                    }
                }
            }
            return blocksInSphere;
        }

        private List<BetterBlockPos> shapeWithoutRayCast(){
            List<BetterBlockPos> result = new ArrayList<>();
            float minX = -radius;
            float minY = -radius;
            float minZ = -radius;
            float maxX = radius;
            float maxY = radius;
            float maxZ = radius;
            Function<Vec3, Boolean> isFacingOK;

            if (directionOfBlockFaceToHit != null){
                switch (directionOfBlockFaceToHit){
                    case UP -> minY = 0;
                    case DOWN -> maxY = 0;
                    case EAST -> minX = 0;
                    case WEST -> maxX = 0;
                    case SOUTH -> minZ = 0;
                    case NORTH -> maxZ = 0;
                    default -> throw new Error("(ProjectedHemisphere.shapeWithoutRayCast) Invalid direction");
                }
            }

            if (directionToFaceAsPlayer == null){
                isFacingOK = vec -> true;
            } else {
                switch (directionToFaceAsPlayer){
                    case UP -> maxY = 0;
                    case DOWN -> minY = 0;
                    case EAST -> maxX = 0;
                    case WEST -> minX = 0;
                    case SOUTH -> maxZ = 0;
                    case NORTH -> minZ = 0;
                }

                Direction.Axis axisAsPlayer = directionToFaceAsPlayer.getAxis();
                switch (axisAsPlayer){
                    case X -> isFacingOK = vec -> Math.abs(vec.x) > Math.max(Math.abs(vec.y), Math.abs(vec.z));
                    case Y -> isFacingOK = vec -> Math.abs(vec.y) > Math.max(Math.abs(vec.x), Math.abs(vec.z));
                    case Z -> isFacingOK = vec -> Math.abs(vec.z) > Math.max(Math.abs(vec.x), Math.abs(vec.y));
                    default -> throw new IllegalStateException("(ProjectedHemisphere.createShape) Invalid Axis");
                }
            }

            for (float x = minX; x <= maxX; x++) {
                double WorldX = x + center.x;
                for (float y = minY; y <= maxY; y++) {
                    double WorldY = y + center.y;
                    for (float z = minZ; z <= maxZ; z++) {
                        double WorldZ = z + center.z;
                        if (center.distanceToSqr(WorldX, WorldY, WorldZ) > radius * radius){
                            continue;
                        }
                        Vec3 blockToCenter = new Vec3(center.x - WorldX, center.y - WorldY, center.z - WorldZ);
                        if (!isFacingOK.apply(blockToCenter)){
                            continue;
                        }
                        result.add(new BetterBlockPos(WorldX, WorldY, WorldZ));
                    }
                }
            }
            return result;
        }

        public List<BetterBlockPos> getBlocksInSphere() {
            return blocksInSphere;
        }

        public float getRadius() {
            return radius;
        }

        public Vec3 getCenter() {
            return center;
        }
    }

    private static class BlockRaycast{
        BlockStateInterface bsi;
        public double stepSize;
        private final Vec3 start;
        private final Vec3 normalRay;
        private final double sqrLength;

        BlockRaycast(BlockStateInterface bsi, Vec3 start, Vec3 ray, double stepSize, double length){
            this.bsi = bsi;
            this.stepSize = stepSize;
            this.start = start;
            this.normalRay = ray.normalize();
            this.sqrLength = length * length;
        }

        BlockRaycast(BlockStateInterface bsi, Vec3 start, Vec3 ray, double stepSize){
            this.bsi = bsi;
            this.stepSize = stepSize;
            this.start = start;
            this.normalRay = ray.normalize();
            this.sqrLength = ray.lengthSqr();
        }

        BlockRaycast(BlockStateInterface bsi, Vec3 start, Vec3 ray){
            this.bsi = bsi;
            this.stepSize = 0.1;
            this.start = start;
            this.normalRay = ray.normalize();
            this.sqrLength = ray.lengthSqr();
        }

        private static BlockHitResult getBlockHitResult(Vec3 collisionPoint, BetterBlockPos blockPos, double precision){
            Direction direction;

            Vec3 blockCenter = blockPos.getCenter();
            Vec3 centerToStep = collisionPoint.add(blockCenter.reverse());
            double[] absoluteVectorValues = {Math.abs(centerToStep.x) , Math.abs(centerToStep.y), Math.abs(centerToStep.z)};
            Tuple<Double, Integer> greatestValue = new Tuple<>(0.0,-1);
            for (int i = 0; i < 3; i++) {
                if (absoluteVectorValues[i] > greatestValue.getA()){
                    greatestValue = new Tuple<>(absoluteVectorValues[i], i);
                }
            }
            direction = switch (greatestValue.getB()){
                case -1 -> Direction.DOWN; // normally impossible
                case 0 -> (centerToStep.x < 0) ? Direction.WEST : Direction.EAST;
                case 1 -> (centerToStep.y < 0) ? Direction.DOWN : Direction.UP;
                case 2 -> (centerToStep.z < 0) ? Direction.NORTH : Direction.SOUTH;
                default -> throw new Error("Impossible case in switch statement");
            };
            boolean isInside = greatestValue.getA() < 0.5 - precision;

            return new BlockHitResult(collisionPoint, direction, blockPos, isInside);
        }

        public BlockHitResult rayCastFirstOccurrence(boolean ignoreLiquid){
            return rayCastFirstOccurrence(this, ignoreLiquid);
        }

        public static BlockHitResult rayCastFirstOccurrence(BlockRaycast raycast, boolean ignoreLiquid){
            double step = 0;
            Vec3 normalizedRay = raycast.normalRay;

            boolean isXPositive = normalizedRay.x > 0;
            boolean isYPositive = normalizedRay.y > 0;
            boolean isZPositive = normalizedRay.z > 0;

            boolean isXNegative = normalizedRay.x < 0;
            boolean isYNegative = normalizedRay.y < 0;
            boolean isZNegative = normalizedRay.z < 0;

            int recursion = 0;
            while (step*step < raycast.sqrLength && recursion < 10000){
                recursion++;

                Vec3 currentPoint = raycast.start.add(normalizedRay.scale(step));
                double nextXStep = Double.MAX_VALUE;
                double nextYStep = Double.MAX_VALUE;
                double nextZStep = Double.MAX_VALUE;

                if (isXPositive){
                    nextXStep = (Math.ceil(currentPoint.x) - raycast.start.x)/normalizedRay.x;
                } else if (isXNegative) {
                    nextXStep = (Math.floor(currentPoint.x) - raycast.start.x)/normalizedRay.x;
                }
                if (isYPositive){
                    nextYStep = (Math.ceil(currentPoint.y) - raycast.start.y)/normalizedRay.y;
                } else if (isYNegative) {
                    nextYStep = (Math.floor(currentPoint.y) - raycast.start.y)/normalizedRay.y;
                }
                if (isZPositive){
                    nextZStep = (Math.ceil(currentPoint.z) - raycast.start.z)/normalizedRay.z;
                } else if (isZNegative) {
                    nextZStep = (Math.floor(currentPoint.z) - raycast.start.z)/normalizedRay.z;
                }
                double smallestNextStep = Math.min(Math.min(nextXStep, nextYStep), nextZStep);

                if (smallestNextStep > step){
                    step = smallestNextStep + raycast.stepSize;
                } else {
                    throw new ArithmeticException("(optimizedRayCastFirstOccurrence) Next step is smaller than current step");
                }
                BetterBlockPos currentBlockPos = new BetterBlockPos(currentPoint.x, currentPoint.y, currentPoint.z);
                BlockState currentBlock = raycast.bsi.get0(currentBlockPos);
                if (currentBlock.isAir()){
                    continue;
                }
                Optional<SlabType> type = currentBlock.getOptionalValue(BlockStateProperties.SLAB_TYPE);
                if (type.isPresent()){
                    Vec3 BlockCenter = currentBlockPos.getCenter();
                    switch (type.get()){
                        case TOP -> { if (currentPoint.y() < BlockCenter.y() && raycast.start.add(normalizedRay.scale(smallestNextStep)).y() < BlockCenter.y()) { continue; } }
                        case BOTTOM -> { if (currentPoint.y() > BlockCenter.y() && raycast.start.add(normalizedRay.scale(smallestNextStep)).y() > BlockCenter.y()) { continue; } }
                    }
                }
                if (currentBlock.is(Blocks.WATER) || currentBlock.is(Blocks.LAVA)){
                    if (ignoreLiquid){
                        continue;
                    } else {
                        return getBlockHitResult(currentPoint, currentBlockPos, raycast.stepSize*2);
                    }
                }
                return getBlockHitResult(currentPoint, currentBlockPos, raycast.stepSize*2);
            }
            return null;
        }

        public Tuple<Optional<BlockHitResult>, List<BetterBlockPos>[]> customRayCast(Set<BetterBlockPos> blocksToIgnore, boolean ignoreLiquid){
            return customRayCast(this, blocksToIgnore, ignoreLiquid);
        }

        public static Tuple<Optional<BlockHitResult>, List<BetterBlockPos>[]> customRayCast(BlockRaycast raycast,
                                                                                            Set<BetterBlockPos> blocksToIgnore,
                                                                                            boolean ignoreLiquid){
            // returns a Tuple of 2 element The first one is the blockHitResult of the first block that was hit
            // and the second is an array of 2 lists the first is the blocks before the hit and the second is the blocks after the hit

            Vec3 normalizedRay = raycast.normalRay;
            List<BetterBlockPos> blocksBeforeFirstOccurrence = new ArrayList<>();
            BlockHitResult blockHit = null;
            List<BetterBlockPos> blocksAfterFirstOccurrence = new ArrayList<>();

            double step = 0;

            boolean isXPositive = normalizedRay.x > 0;
            boolean isYPositive = normalizedRay.y > 0;
            boolean isZPositive = normalizedRay.z > 0;

            boolean isXNegative = normalizedRay.x < 0;
            boolean isYNegative = normalizedRay.y < 0;
            boolean isZNegative = normalizedRay.z < 0;

            int recursion = 0;
            while (step*step < raycast.sqrLength && recursion < 10000){
                recursion++;
                Vec3 currentRelativePoint = normalizedRay.scale(step);
                Vec3 currentPoint = raycast.start.add(currentRelativePoint);
                double nextXStep = Double.MAX_VALUE;
                double nextYStep = Double.MAX_VALUE;
                double nextZStep = Double.MAX_VALUE;

                if (isXPositive){
                    nextXStep = (Math.ceil(currentPoint.x) - raycast.start.x)/normalizedRay.x;
                } else if (isXNegative) {
                    nextXStep = (Math.floor(currentPoint.x) - raycast.start.x)/normalizedRay.x;
                }
                if (isYPositive){
                    nextYStep = (Math.ceil(currentPoint.y) - raycast.start.y)/normalizedRay.y;
                } else if (isYNegative) {
                    nextYStep = (Math.floor(currentPoint.y) - raycast.start.y)/normalizedRay.y;
                }
                if (isZPositive){
                    nextZStep = (Math.ceil(currentPoint.z) - raycast.start.z)/normalizedRay.z;
                } else if (isZNegative) {
                    nextZStep = (Math.floor(currentPoint.z) - raycast.start.z)/normalizedRay.z;
                }
                double smallestNextStep = Math.min(Math.min(nextXStep, nextYStep), nextZStep);

                if (smallestNextStep > step){
                    step = smallestNextStep + raycast.stepSize;
                } else {
                    throw new ArithmeticException("(optimizedRayCastFirstOccurence) Next step is smaller than current step");
                }
                BetterBlockPos currentBlockPos = new BetterBlockPos(currentPoint.x, currentPoint.y, currentPoint.z);
                if (blocksToIgnore.contains(currentBlockPos)){
                    continue;
                }
                if (blockHit != null) {
                    blocksAfterFirstOccurrence.add(currentBlockPos);
                    continue;
                }
                BlockState currentBlock = raycast.bsi.get0(currentBlockPos);
                if (currentBlock.isAir()){
                    blocksBeforeFirstOccurrence.add(currentBlockPos);
                    continue;
                }
                Optional<SlabType> type = currentBlock.getOptionalValue(BlockStateProperties.SLAB_TYPE);
                if (type.isPresent()){
                    Vec3 BlockCenter = currentBlockPos.getCenter();
                    switch (type.get()){
                        case TOP -> { if (currentPoint.y() < BlockCenter.y() && normalizedRay.scale(smallestNextStep).y() < BlockCenter.y()) {
                            blocksBeforeFirstOccurrence.add(currentBlockPos);
                            continue;
                        } }
                        case BOTTOM -> { if (currentPoint.y() > BlockCenter.y() && normalizedRay.scale(smallestNextStep).y() > BlockCenter.y()) {
                            blocksBeforeFirstOccurrence.add(currentBlockPos);
                            continue;
                        } }
                    }
                }
                if (currentBlock.is(Blocks.WATER) || currentBlock.is(Blocks.LAVA)){
                    if (ignoreLiquid){
                        blocksBeforeFirstOccurrence.add(currentBlockPos);
                        continue;
                    }
                }
                blockHit = getBlockHitResult(currentPoint, currentBlockPos, raycast.stepSize*2);
            }
            return new Tuple<>(Optional.ofNullable(blockHit), new List[]{blocksBeforeFirstOccurrence, blocksAfterFirstOccurrence});
        }

        public Tuple<Optional<BlockHitResult>, List<BetterBlockPos>[]> rayCastMultipleOccurrenceWithAir(BlockStateInterface bsi, boolean ignoreLiquid){
            // returns a Tuple of 2 element The first one is the blockHitResult of the first block that was hit
            // and the second is an array of 2 lists the first is the blocks before the hit and the second is the blocks after the hit

            return customRayCast(new HashSet<>(), ignoreLiquid);
        }
    }

    @Override
    public void onLostControl() {
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            BlockState itemDefaultState = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
            if (!itemDefaultState.getProperties().isEmpty()){
                result.add(itemDefaultState);
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                result.add(Blocks.AIR.defaultBlockState());
            }
            // </toxic cloud>
        }
        return result;
    }

    private static boolean approxPlaceableContainsBlockType(Collection<BlockState> states, Block block){
        for (BlockState state : states) {
            if (block.equals(state.getBlock())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        if (!ignoreDirection && ignoredProps.isEmpty()) {
            return first.equals(second); // early return if no properties are being ignored
        }
        ImmutableMap<Property<?>, Comparable<?>> map1 = first.getValues();
        ImmutableMap<Property<?>, Comparable<?>> map2 = second.getValues();
        for (Property<?> prop : map1.keySet()) {
            if (map1.get(prop) != map2.get(prop)
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        if (current.getBlock().equals(desired.getBlock())){
            Map<String, String> currProperties = BlockPlacementAnalyzer.mapProperties(current);
            if (currProperties.isEmpty()){
                return sameBlockstate(current, desired);
            }
            Map<Direction, Boolean> linker = createDirectionPropertyLinker(currProperties);
            if (linker.isEmpty()){
                return sameBlockstate(current,desired);
            }
            BlockPlacementBehavior placementBehavior = BlockPlacementAnalyzer.getDirectionProperty(current.getBlock());
            if (placementBehavior == BlockPlacementBehavior.CONNECTS_TO_NEIGHBORS){
                return true;
            }
        }

        return sameBlockstate(current, desired);
    }

    private static boolean itemApproxValid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        return current.getBlock().equals(desired.getBlock());
    }

    private static boolean approxValid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        Map<String, String> desiredProperties = BlockPlacementAnalyzer.mapProperties(desired);
        if (!desiredProperties.isEmpty() && current.getBlock().equals(desired.getBlock())){
            Map<Direction, Boolean> desiredLinker = createDirectionPropertyLinker(desiredProperties);
            if (!desiredLinker.isEmpty()){
                Map<String, String> currentProperties = BlockPlacementAnalyzer.mapProperties(current);
                Map<Direction, Boolean> currentLinker = createDirectionPropertyLinker(currentProperties);
                for (Map.Entry<Direction, Boolean> desLink : desiredLinker.entrySet()){
                    if (currentLinker.get(desLink.getKey()) && !desLink.getValue()){
                        return false;
                    }
                }
                return true;
            }
        }
        return sameBlockstate(current, desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
