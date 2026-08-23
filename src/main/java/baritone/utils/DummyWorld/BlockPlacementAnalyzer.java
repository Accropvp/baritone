package baritone.utils.DummyWorld;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;


public class BlockPlacementAnalyzer {

    public static DummyWorld dummyWorld;
    private static DummyPlayer dummyPlayer;
    public static Map<Block, BlockPlacementBehavior> blockPlacementBehaviorMap = new HashMap<>();

    /**
     * Analyzes a block and returns its placement behavior type.
     *
     * @param state The blockState of the block to analyze
     * @return The placement behavior enum value
     */
    public static BlockPlacementBehavior analyzeBlockState(BlockState state) {
        try {
            Map<String, String> properties = mapProperties(state);

            String facing = properties.getOrDefault("facing", "");
            if (!facing.isEmpty()){
                return getFacingProperty(state.getBlock());
            }
            String axis = properties.getOrDefault("axis", "");
            if (!axis.isEmpty()){
                return getAxisProperty(state.getBlock());
            }
            String west = properties.getOrDefault("west", "");
            if (!west.isEmpty()){
                return getDirectionProperty(state.getBlock());
            }
            return BlockPlacementBehavior.CUSTOM_LOGIC;

        } catch (Exception e) {
            // If analysis fails, return UNKNOWN
            return BlockPlacementBehavior.UNKNOWN;
        }
    }

    /**
     * Analyzes a block and returns its placement behavior type.
     *
     * @param block The block to analyze
     * @return The placement behavior enum value
     */
    public static BlockPlacementBehavior analyzeBlock(Block block) {
        return analyzeBlockState(block.defaultBlockState());
    }


    /**
     * Create the context to place block in the dummy world
     */
    private static void makeWorldSetup(){
        getTestWorld().setBlock(new BetterBlockPos(2, 65, 1), Blocks.STONE.defaultBlockState(), 0);
        getTestWorld().setBlock(new BetterBlockPos(2, 65, -1), Blocks.STONE.defaultBlockState(), 0);
        getTestWorld().setBlock(new BetterBlockPos(2, 64, 0), Blocks.STONE.defaultBlockState(), 0);
        getTestWorld().setBlock(new BetterBlockPos(0, 63, 0), Blocks.STONE.defaultBlockState(), 0);
        getDummyPlayer(new Vec3(0.5, 64, 0.5), Direction.EAST);
    }

    /**
     * clean the dummy world of it's context
     */
    private static void cleanWorldSetup(){
        getTestWorld().clearBlocks();
    }


    /**
     * Analyzes a block with facing property and returns its placement behavior type.
     *
     * @param block The block to analyze
     * @return The placement behavior enum value
     */
    public static BlockPlacementBehavior getFacingProperty(Block block){
        return blockPlacementBehaviorMap.computeIfAbsent(block, BlockPlacementAnalyzer::getFacingPropertyWithoutCache);
    }

    public static BlockPlacementBehavior getFacingPropertyWithoutCache(Block block){
        cleanWorldSetup(); // safety first
        makeWorldSetup();

        // Create a fake placement context using Forge's BlockPlaceContext (protected constructor workaround)
        BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                getTestWorld(),
                getDummyPlayer(new Vec3(0.5, 64, 0.5), Direction.EAST),
                InteractionHand.MAIN_HAND,
                new ItemStack(block),
                new BlockHitResult(new Vec3(2.5, 65.5, 0), Direction.SOUTH, new BetterBlockPos(2, 65, -1), false)
        ) {});

        BlockState state = block.getStateForPlacement(context);

        cleanWorldSetup();

        if (state == null) throw new AssertionError("Block : " + block.toString() + " couldn't be tested (placed) on dummy world");


        Map<String, String> props = mapProperties(state);

        //props.forEach((k, v) -> System.out.println(k + " = " + v));

        String facing = props.get("facing");

        return switch (facing){
            case "west" ->BlockPlacementBehavior.FACES_PLAYER;
            case "east" -> BlockPlacementBehavior.FACES_AWAY_FROM_PLAYER;
            case "south" -> BlockPlacementBehavior.FACES_SAME_AS_HIT_FACE;
            case "north" -> BlockPlacementBehavior.FACES_OPPOSITE_FROM_HIT_FACE;
            default -> BlockPlacementBehavior.UNKNOWN;
        };
    }

    public static Map<String, String> mapProperties(BlockState state){
        Map<String, String> properties = new HashMap<>();
        for (Property<?> prop : state.getProperties()){
            String name = prop.getName();
            String value = state.getValue(prop).toString();
            properties.put(name, value);
        }
        return properties;
    }

    /**
     * Analyzes a block with axis property and returns its placement behavior type.
     *
     * @param block The block to analyze
     * @return The placement behavior enum value
     */
    public static BlockPlacementBehavior getAxisProperty(Block block){
        return blockPlacementBehaviorMap.computeIfAbsent(block, BlockPlacementAnalyzer::getAxisPropertyWithoutCache);
    }

    public static BlockPlacementBehavior getAxisPropertyWithoutCache(Block block){
        cleanWorldSetup(); // safety first
        makeWorldSetup();

        // Create a fake placement context using Forge's BlockPlaceContext (protected constructor workaround)
        BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                getTestWorld(),
                getDummyPlayer(new Vec3(0.5, 64, 0.5), Direction.EAST),
                InteractionHand.MAIN_HAND,
                new ItemStack(block),
                new BlockHitResult(new Vec3(2.5, 65.5, 0), Direction.SOUTH, new BetterBlockPos(2, 65, -1), false)
        ) {});

        BlockState state = block.getStateForPlacement(context);

        cleanWorldSetup();

        if (state == null) throw new AssertionError("Block : " + block.toString() + " couldn't be tested (placed) on dummy world");

        Map<String, String> props = mapProperties(state);

        //props.forEach((k, v) -> System.out.println(k + " = " + v));

        return switch (props.get("axis")){
            case "x" -> BlockPlacementBehavior.FACES_PLAYER;
            case "z" -> BlockPlacementBehavior.FACES_SAME_AS_HIT_FACE;
            default -> BlockPlacementBehavior.UNKNOWN;
        };
    }

    /**
     * Analyzes a block with east, west, ... property and returns its placement behavior type.
     *
     * @param block The block to analyze
     * @return The placement behavior enum value
     */
    public static BlockPlacementBehavior getDirectionProperty(Block block){
        return blockPlacementBehaviorMap.computeIfAbsent(block, BlockPlacementAnalyzer::getDirectionPropertyWithoutCache);
    }

    public static BlockPlacementBehavior getDirectionPropertyWithoutCache(Block block){
        cleanWorldSetup(); // safety first
        makeWorldSetup();

        // Create a fake placement context using Forge's BlockPlaceContext (protected constructor workaround)
        BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                getTestWorld(),
                getDummyPlayer(new Vec3(0.5, 64, 0.5), Direction.EAST),
                InteractionHand.MAIN_HAND,
                new ItemStack(block),
                new BlockHitResult(new Vec3(2.5, 65.5, 0), Direction.SOUTH, new BetterBlockPos(2, 65, -1), false)
        ) {});

        BlockState state = block.getStateForPlacement(context);

        cleanWorldSetup();

        if (state == null) throw new AssertionError("Block : " + block.toString() + " couldn't be tested (placed) on dummy world");

        Map<String, String> props = mapProperties(state);

        //props.forEach((k, v) -> System.out.println(k + " = " + v));

        boolean north = Boolean.parseBoolean(props.get("north"));
        boolean south = Boolean.parseBoolean(props.get("south"));
        boolean east = Boolean.parseBoolean(props.get("east"));
        boolean west = Boolean.parseBoolean(props.get("west"));

        if (east || west){
            return BlockPlacementBehavior.UNKNOWN;
        } else if (north) {
            if (south){
                return BlockPlacementBehavior.CONNECTS_TO_NEIGHBORS;
            }
            return BlockPlacementBehavior.FACES_OPPOSITE_FROM_HIT_FACE;
        } else {
            return BlockPlacementBehavior.UNKNOWN;
        }
    }

    /**
     * Gets a test world instance (minimal implementation).
     */
    private static DummyWorld getTestWorld() {
        if (dummyWorld == null){
            dummyWorld = new DummyWorld();
        }
        return dummyWorld;
    }

    /**
     * Gets a test world instance (minimal implementation).
     */
    private static DummyPlayer getDummyPlayer(Vec3 pos, Direction face) {
        if (dummyPlayer == null || dummyPlayer.getDirection() != face || dummyPlayer.position() != pos){
            dummyPlayer = new DummyPlayer(getTestWorld(), pos, face);
        }
        return dummyPlayer;
    }


    public static class TestOfDummy{
        public static boolean testBlocksPos(){
            cleanWorldSetup();
            makeWorldSetup();

            boolean right = getTestWorld().hasBlockAt(new BetterBlockPos(2, 65, -1));
            boolean left = getTestWorld().hasBlockAt(new BetterBlockPos(2, 65, 1));
            boolean down = getTestWorld().hasBlockAt(new BetterBlockPos(2, 64, 0));
            boolean playerDown = getTestWorld().hasBlockAt(new BetterBlockPos(0, 63, 0));

            cleanWorldSetup();

            return right && left && down && playerDown;
        }

        public static void testBlocksType(){
            cleanWorldSetup();
            makeWorldSetup();

            boolean right = getTestWorld().getBlockState(new BetterBlockPos(2, 65, -1)).getBlock() == Blocks.STONE;
            boolean left = getTestWorld().getBlockState(new BetterBlockPos(2, 65, 1)).getBlock() == Blocks.STONE;
            boolean down = getTestWorld().getBlockState(new BetterBlockPos(2, 64, 0)).getBlock() == Blocks.STONE;
            boolean playerDown = getTestWorld().getBlockState(new BetterBlockPos(0, 63, 0)).getBlock() == Blocks.STONE;

            cleanWorldSetup();

//            if (right && left && down && playerDown){
//                Baritone.LOGGER.error("block pos is correct");
//            } else {
//                Baritone.LOGGER.error("block pos is incorrect");
//            }
        }

        public static void testFacing(){
//            Baritone.LOGGER.error("Testing Facing on dummy level");
            cleanWorldSetup();
            makeWorldSetup();

            BlockPlacementBehavior chest = getFacingProperty(Blocks.CHEST);
            BlockPlacementBehavior stairs = getFacingProperty(Blocks.OAK_STAIRS);
            BlockPlacementBehavior endRod = getFacingProperty(Blocks.END_ROD);

//            if (chest != BlockPlacementBehavior.FACES_PLAYER){
//                Baritone.LOGGER.error("chest facing is incorrect, behavior expected: FACES_PLAYER, behavior: {}", chest.toString());
//            }
//            if (stairs != BlockPlacementBehavior.FACES_AWAY_FROM_PLAYER){
//                Baritone.LOGGER.error("stairs facing is incorrect, behavior expected: FACES_AWAY_FROM_PLAYER, behavior: {}", stairs.toString());
//            }
//            if (endRod != BlockPlacementBehavior.FACES_SAME_AS_HIT_FACE){
//                Baritone.LOGGER.error("end_rod facing is incorrect, behavior expected: FACES_SAME_AS_HIT_FACE, behavior: {}", endRod.toString());
//            }
//            if (chest == BlockPlacementBehavior.FACES_PLAYER && stairs == BlockPlacementBehavior.FACES_AWAY_FROM_PLAYER && endRod == BlockPlacementBehavior.FACES_SAME_AS_HIT_FACE){
//                Baritone.LOGGER.error("All facing are correct");
//            }
        }

        public static void testAxis(){
//            Baritone.LOGGER.error("Testing Axis on dummy level");

            cleanWorldSetup();
            makeWorldSetup();

            BlockPlacementBehavior log = getAxisProperty(Blocks.OAK_LOG);

//            if (log != BlockPlacementBehavior.FACES_SAME_AS_HIT_FACE){
//                Baritone.LOGGER.error("log axis is incorrect, behavior expected: FACES_SAME_AS_HIT_FACE, behavior: {}", log.toString());
//            } else {
//                Baritone.LOGGER.error("All axis are correct");
//            }
        }

        public static void testDirection(){
//            Baritone.LOGGER.error("Testing Direction on dummy level");

            cleanWorldSetup();
            makeWorldSetup();

            BlockPlacementBehavior vine = getDirectionProperty(Blocks.VINE);
            BlockPlacementBehavior fence = getDirectionProperty(Blocks.DARK_OAK_FENCE);

//            if (vine != BlockPlacementBehavior.FACES_SAME_AS_HIT_FACE){
//                Baritone.LOGGER.error("vine direction is incorrect, behavior expected: FACES_SAME_AS_HIT_FACE, behavior: {}", vine.toString());
//            }
//            if (fence != BlockPlacementBehavior.CONNECTS_TO_NEIGHBORS){
//                Baritone.LOGGER.error("fence direction is incorrect, behavior expected: CONNECTS_TO_NEIGHBORS, behavior: {}", fence.toString());
//            } else if (vine == BlockPlacementBehavior.FACES_SAME_AS_HIT_FACE){
//                Baritone.LOGGER.error("All direction are valid");
//            }
        }

    }
}