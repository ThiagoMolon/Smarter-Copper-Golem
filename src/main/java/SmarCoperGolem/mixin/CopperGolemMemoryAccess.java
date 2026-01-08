package SmarCoperGolem.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;

public interface CopperGolemMemoryAccess {
    void smartRecordChestVisit(Level level, BlockPos pos);
    java.util.Optional<BlockPos> smartFindFirstEmpty(Level level);
    java.util.Optional<BlockPos> smartChooseChestForItem(Level level, ItemStack held);
}
