package SmarCoperGolem.mixin;

import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiConsumer;
import java.util.Optional;

@Mixin(TransportItemsBetweenContainers.class)
public class TransportItemsBetweenContainersMixin {

    @Inject(method = "onReachedInteraction", at = @At("RETURN"), cancellable = true)
    private static void onReachedInteraction(TransportItemsBetweenContainers.ContainerInteractionState state, CallbackInfoReturnable<BiConsumer<PathfinderMob, Container>> cir) {
        BiConsumer<PathfinderMob, Container> original = cir.getReturnValue();
        BiConsumer<PathfinderMob, Container> wrapped = (mob, container) -> {
            try {
                original.accept(mob, container);
            } catch (Throwable t) {
                // swallow to avoid breaking vanilla logic
            }

            if (!(mob instanceof CopperGolem)) return;
            CopperGolem golem = (CopperGolem) mob;

            ItemStack held = ItemStack.EMPTY;
            // try to get the carried item via reflection (compat with mappings/visibility)
            try {
                java.lang.reflect.Method gm = golem.getClass().getMethod("getCarried");
                gm.setAccessible(true);
                Object res = gm.invoke(golem);
                if (res instanceof ItemStack) held = (ItemStack) res;
            } catch (Throwable ignored) {
            }

            // record if target was a block entity
            if (container instanceof BlockEntity) {
                BlockEntity be = (BlockEntity) container;
                BlockPos pos = be.getBlockPos();
                try {
                    ((CopperGolemMemoryAccess) (Object) golem).smartRecordChestVisit(be.getLevel(), pos);
                } catch (Throwable ignored) {
                }

                // if still holding item, try fallback using memory + selection rules (use chest level)
                if (held != null && !held.isEmpty()) {
                    try {
                        Optional<BlockPos> opt = ((CopperGolemMemoryAccess) (Object) golem).smartChooseChestForItem(be.getLevel(), held);
                        if (opt.isPresent()) {
                            BlockPos target = opt.get();
                            BlockEntity be2 = be.getLevel().getBlockEntity(target);
                            if (be2 instanceof ChestBlockEntity) {
                                ChestBlockEntity chest = (ChestBlockEntity) be2;
                                int size = chest.getContainerSize();
                                int remaining = held.getCount();
                                // First try to merge into existing stacks of same item
                                for (int i = 0; i < size && remaining > 0; i++) {
                                    ItemStack slot = chest.getItem(i);
                                    if (slot != null && !slot.isEmpty() && slot.getItem().equals(held.getItem())) {
                                        int space = slot.getMaxStackSize() - slot.getCount();
                                        if (space > 0) {
                                            int toMove = Math.min(space, remaining);
                                            slot.setCount(slot.getCount() + toMove);
                                            chest.setItem(i, slot);
                                            remaining -= toMove;
                                        }
                                    }
                                }
                                // Then place into empty slots
                                for (int i = 0; i < size && remaining > 0; i++) {
                                    ItemStack slot = chest.getItem(i);
                                    if (slot == null || slot.isEmpty()) {
                                        int toPlace = Math.min(held.getMaxStackSize(), remaining);
                                        chest.setItem(i, new ItemStack(held.getItem(), toPlace));
                                        remaining -= toPlace;
                                    }
                                }

                                // update carried item on golem
                                try {
                                    java.lang.reflect.Method sm = golem.getClass().getMethod("setCarried", ItemStack.class);
                                    sm.setAccessible(true);
                                    if (remaining <= 0) {
                                        sm.invoke(golem, ItemStack.EMPTY);
                                    } else {
                                        sm.invoke(golem, new ItemStack(held.getItem(), remaining));
                                    }
                                } catch (Throwable ignored) {
                                }

                                chest.setChanged();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        };

        cir.setReturnValue(wrapped);
    }
}
