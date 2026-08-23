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
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

/**
 * Minimal dummy player for placement context testing.
 */
class DummyPlayer extends Player {
    private final Vec3 position;
    private final Direction facing;


    public DummyPlayer(Level level, Vec3 position, Direction facing) {
        super(level, BlockPos.containing(position), 0.0f,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "DummyPlayer"));
        this.position = position;
        this.facing = facing;
    }

    @Override
    public @NotNull Vec3 position() { return position; }

    @Override
    public @NotNull Direction getDirection() { return facing; }

    @Override
    public float getYRot() {
        if (facing == null){
            return 180.0f;
        }
        return switch (facing) {
            case NORTH -> 180.0f;
            case SOUTH -> 0.0f;
            case EAST -> 270.0f;
            case WEST -> 90.0f;
            default -> 0.0f;
        };
    }

    @Override
    public float getXRot() { return 0.0f; }

    // Required implementations
    @Override public boolean isSpectator() { return false; }
    @Override public boolean isCreative() { return true; }
    @Override public net.minecraft.world.item.@NotNull ItemStack getItemInHand(net.minecraft.world.InteractionHand hand) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
