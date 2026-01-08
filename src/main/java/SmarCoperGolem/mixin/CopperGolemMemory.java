package SmarCoperGolem.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

@Mixin(CopperGolem.class)
public abstract class CopperGolemMemory implements CopperGolemMemoryAccess {
    @Unique
    private final Deque<BlockPos> smart_recentChests = new ArrayDeque<>(10);

    @Unique
    public void smartRecordChestVisit(Level level, BlockPos pos) {
        if (pos == null) return;
        BlockPos p = pos.immutable();
        // move to most-recent position
        smart_recentChests.remove(p);
        smart_recentChests.addLast(p);
        while (smart_recentChests.size() > 10) {
            smart_recentChests.removeFirst();
        }
    }

    @Unique
    public Optional<BlockPos> smartFindFirstEmpty(Level level) {
        if (!(level instanceof ServerLevel)) return Optional.empty();
        Iterator<BlockPos> it = smart_recentChests.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ChestBlockEntity) {
                ChestBlockEntity chest = (ChestBlockEntity) be;
                boolean empty = true;
                int size = chest.getContainerSize();
                for (int i = 0; i < size; i++) {
                    ItemStack stack = chest.getItem(i);
                    if (stack != null && !stack.isEmpty()) { empty = false; break; }
                }
                if (empty) return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    @Unique
    public Optional<BlockPos> smartChooseChestForItem(Level level, ItemStack held) {
        if (held == null || held.isEmpty()) return Optional.empty();
        String heldCategory = getCategoryId(held);

        // First pass: category match with diversity check
        Iterator<BlockPos> it = smart_recentChests.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof ChestBlockEntity)) continue;
            ChestBlockEntity chest = (ChestBlockEntity) be;

            int size = chest.getContainerSize();
            int totalNonEmpty = 0;
            int categoryMatches = 0;
            Set<String> distinctBlocks = new HashSet<>();

            for (int i = 0; i < size; i++) {
                ItemStack stack = chest.getItem(i);
                if (stack == null || stack.isEmpty()) continue;
                totalNonEmpty++;
                String cat = getCategoryId(stack);
                if (heldCategory != null && heldCategory.equals(cat)) categoryMatches++;

                if (stack.getItem() instanceof BlockItem) {
                    BlockItem bi = (BlockItem) stack.getItem();
                    Block block = bi.getBlock();
                    if (block != null) distinctBlocks.add(block.getClass().getName());
                }
            }

            if (categoryMatches > 0) {
                // if more than 3 different block types, skip even if category matches
                if (distinctBlocks.size() > 3) continue;
                return Optional.of(pos);
            }
        }

        // Second pass: check for ~70% raw-material matches (interpreted as same item type)
        it = smart_recentChests.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof ChestBlockEntity)) continue;
            ChestBlockEntity chest = (ChestBlockEntity) be;

            int size = chest.getContainerSize();
            int totalNonEmpty = 0;
            int sameItemCount = 0;

            for (int i = 0; i < size; i++) {
                ItemStack stack = chest.getItem(i);
                if (stack == null || stack.isEmpty()) continue;
                totalNonEmpty++;
                if (stack.getItem().getClass().equals(held.getItem().getClass())) sameItemCount++;
            }
            if (totalNonEmpty > 0) {
                double ratio = (double) sameItemCount / (double) totalNonEmpty;
                if (ratio >= 0.7) return Optional.of(pos);
            }
        }

        return Optional.empty();
    }

    @Unique
    private String getCategoryId(ItemStack stack) {
        if (stack == null) return null;
        try {
            Object item = stack.getItem();
            if (item == null) return null;

            // Try to get creative tab / item group via reflection (best-effort)
            try {
                java.lang.reflect.Method mg = item.getClass().getMethod("getItemCategory");
                mg.setAccessible(true);
                Object grp = mg.invoke(item);
                if (grp != null) return grp.getClass().getSimpleName();
            } catch (Throwable ignored) {
            }

            try {
                java.lang.reflect.Method mg = item.getClass().getMethod("getGroup");
                mg.setAccessible(true);
                Object grp = mg.invoke(item);
                if (grp != null) return grp.getClass().getSimpleName();
            } catch (Throwable ignored) {
            }

            // Fallback: use item class name without 'Item' suffix
            String cls = item.getClass().getSimpleName();
            int idx = cls.indexOf("Item");
            if (idx > 0) return cls.substring(0, idx);
            return cls;
        } catch (Throwable t) {
            return null;
        }
    }
}
