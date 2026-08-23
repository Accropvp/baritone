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

import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;

/**
 * Dummy entity getter that returns no entities.
 */
class DummyEntityGetter implements net.minecraft.world.level.entity.LevelEntityGetter<Entity> {

    @Override
    public Entity get(int id) {
        return null;
    }

    @Override
    public Entity get(java.util.UUID uuid) {
        return null;
    }

    @Override
    public Iterable<Entity> getAll() {
        return java.util.Collections.emptyList();
    }

    @Override
    public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AbortableIterationConsumer<U> consumer) {
        // No entities to iterate over
    }

    @Override
    public void get(net.minecraft.world.phys.AABB bounds, java.util.function.Consumer<Entity> action) {
        // No entities to iterate over
    }

    @Override
    public <U extends Entity> void get(net.minecraft.world.level.entity.EntityTypeTest<Entity, U> entityTypeTest,
                                       net.minecraft.world.phys.AABB bounds,
                                       net.minecraft.util.AbortableIterationConsumer<U> consumer) {
        // No entities to iterate over
    }
}
