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

public enum BlockPlacementBehavior {
    /**
     * Block faces the player (like furnaces, dispensers, observers).
     * The FACING property points toward the player who placed it.
     */
    FACES_PLAYER,

    /**
     * Block faces away from the player (like pistons, some modded blocks).
     * The FACING property points away from the player who placed it.
     */
    FACES_AWAY_FROM_PLAYER,

    /**
     * Block faces the same way of the face that was clicked (like vines , end rod).
     * Orientation depends on which face of an existing block was clicked.
     */
    FACES_SAME_AS_HIT_FACE,

    /**
     * Block uses the face that was clicked (like IDK).
     * Orientation depends on which face of an existing block was clicked.
     */
    FACES_OPPOSITE_FROM_HIT_FACE,

    /**
     * Block connects to adjacent similar blocks (like fences, walls, glass panes).
     * Properties change based on neighboring blocks of the same type.
     */
    CONNECTS_TO_NEIGHBORS,

    /**
     * Block has complex custom placement logic that doesn't fit other categories.
     * Requires individual analysis to understand behavior.
     */
    CUSTOM_LOGIC,

    /**
     * Unable to determine placement behavior (error case).
     */
    UNKNOWN;

    /**
     * Returns a human-readable description of this placement behavior.
     */
    public String getDescription() {
        return switch (this) {
            case FACES_PLAYER -> "Block faces toward the player who placed it";
            case FACES_AWAY_FROM_PLAYER -> "Block faces away from the player who placed it";
            case FACES_SAME_AS_HIT_FACE -> "Block faces the same way of the face that was clicked";
            case FACES_OPPOSITE_FROM_HIT_FACE -> "Block faces the opposite way from the face that was clicked";
            case CONNECTS_TO_NEIGHBORS -> "Block connects to adjacent blocks";
            case CUSTOM_LOGIC -> "Block has custom placement logic";
            case UNKNOWN -> "Placement behavior could not be determined";
        };
    }
}
