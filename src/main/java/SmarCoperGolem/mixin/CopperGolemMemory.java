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
    private Object getItemGroupObject(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            Object item = stack.getItem();
            if (item == null) return null;

            try {
                java.lang.reflect.Method mg = item.getClass().getMethod("getItemCategory");
                mg.setAccessible(true);
                Object grp = mg.invoke(item);
                if (grp != null) return grp;
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Method mg = item.getClass().getMethod("getGroup");
                mg.setAccessible(true);
                Object grp = mg.invoke(item);
                if (grp != null) return grp;
            } catch (Throwable ignored) {}

            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Unique
    private boolean groupEquals(Object a, Object b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        try {
            String an = a.getClass().getSimpleName();
            String bn = b.getClass().getSimpleName();
            if (an != null && bn != null && an.equalsIgnoreCase(bn)) return true;
            String as = a.toString();
            String bs = b.toString();
            if (as != null && bs != null && as.equalsIgnoreCase(bs)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    @Unique
    public Optional<BlockPos> smartChooseChestForItem(Level level, ItemStack held) {
        if (held == null || held.isEmpty()) return Optional.empty();
        Object heldGroup = getItemGroupObject(held);

        // First pass: category match with diversity check
        Iterator<BlockPos> it = smart_recentChests.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof ChestBlockEntity)) continue;
            ChestBlockEntity chest = (ChestBlockEntity) be;

            try {
                if (chest.getCustomName() != null) {
                    String cname = chest.getCustomName().getString().trim();
                    if (!cname.isEmpty()) {
                        if (!chestNameAccepts(held, cname, level)) continue;
                    }
                }
            } catch (Throwable ignored) {}

            int size = chest.getContainerSize();
            int totalNonEmpty = 0;
            int groupMatches = 0;
            int sameItemCount = 0;
            Set<String> distinctItemClasses = new HashSet<>();

            for (int i = 0; i < size; i++) {
                ItemStack stack = chest.getItem(i);
                if (stack == null || stack.isEmpty()) continue;
                totalNonEmpty++;

                // exact same item class
                if (stack.getItem().getClass().equals(held.getItem().getClass())) sameItemCount++;
                distinctItemClasses.add(stack.getItem().getClass().getName());

                Object g = getItemGroupObject(stack);
                if (g != null && heldGroup != null && groupEquals(g, heldGroup)) groupMatches++;
            }

            // If chest already contains the exact item, prefer it
            if (sameItemCount > 0) return Optional.of(pos);

            // If there are no items in the chest, it's acceptable
            if (totalNonEmpty == 0) return Optional.of(pos);

            // If majority of items match the held item's group, accept (avoid highly mixed chests)
            if (groupMatches > 0) {
                double ratio = (double) groupMatches / (double) totalNonEmpty;
                if (ratio >= 0.7 || distinctItemClasses.size() <= 1) {
                    return Optional.of(pos);
                }
                // otherwise skip this chest because it's too mixed
                continue;
            }
        }

        // Second pass: check for ~70% raw-material matches (interpreted as same item type)
        it = smart_recentChests.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof ChestBlockEntity)) continue;
            ChestBlockEntity chest = (ChestBlockEntity) be;

            try {
                if (chest.getCustomName() != null) {
                    String cname = chest.getCustomName().getString().trim();
                    if (!cname.isEmpty()) {
                        if (!chestNameAccepts(held, cname, level)) continue;
                    }
                }
            } catch (Throwable ignored) {}

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

    @Unique
    private boolean chestNameAccepts(ItemStack held, String name, Level level) {
        if (held == null || held.isEmpty()) return true;
        try {
            String raw = name.trim();
            if (raw.isEmpty()) return true;

            boolean isAllUpper = raw.equals(raw.toUpperCase());
            boolean isAllLower = raw.equals(raw.toLowerCase());

            String path = raw;
            if (raw.contains(":")) {
                String[] parts = raw.split(":", 2);
                path = parts[1];
            }
            String pathLower = path.toLowerCase();

            net.minecraft.resources.ResourceLocation id = net.minecraft.core.Registry.ITEM.getKey(held.getItem());
            if (id == null) return false;
            String itemPath = id.getPath();

            if (isAllUpper) {
                // match exato: "STONE" -> item.path == "stone"
                if (raw.contains(":")) {
                    return raw.equalsIgnoreCase(id.toString());
                } else {
                    return pathLower.equals(itemPath.toLowerCase());
                }
            } else if (isAllLower) {
                // derivados (substring): "stone" aceita itens cujo id contém "stone"
                if (itemPath.toLowerCase().contains(pathLower)) return true;
                // também checar bloco se for BlockItem
                try {
                    if (held.getItem() instanceof BlockItem) {
                        BlockItem bi = (BlockItem) held.getItem();
                        net.minecraft.resources.ResourceLocation bid = net.minecraft.core.Registry.BLOCK.getKey(bi.getBlock());
                        if (bid != null && bid.getPath().toLowerCase().contains(pathLower)) return true;
                    }
                } catch (Throwable ignored) {}
                return false;
            }
            // mixed-case ou sem regra: não filtrar
            return true;
        } catch (Throwable t) {
            return true;
        }
    }
}
