/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package su.terrafirmagreg.core.common.blockentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.dries007.tfc.client.TFCSounds;
import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.blockentities.BarrelInventoryCallback;
import net.dries007.tfc.common.blockentities.IRecipeTimer;
import net.dries007.tfc.common.blockentities.InventoryBlockEntity;
import net.dries007.tfc.common.blockentities.TickableInventoryBlockEntity;
import net.dries007.tfc.common.capabilities.Capabilities;
import net.dries007.tfc.common.capabilities.DelegateFluidHandler;
import net.dries007.tfc.common.capabilities.DelegateItemHandler;
import net.dries007.tfc.common.capabilities.FluidTankCallback;
import net.dries007.tfc.common.capabilities.InventoryFluidTank;
import net.dries007.tfc.common.capabilities.InventoryItemHandler;
import net.dries007.tfc.common.capabilities.food.FoodCapability;
import net.dries007.tfc.common.capabilities.food.FoodTraits;
import net.dries007.tfc.common.capabilities.size.IItemSize;
import net.dries007.tfc.common.capabilities.size.ItemSizeManager;
import net.dries007.tfc.common.capabilities.size.Size;
import net.dries007.tfc.common.capabilities.size.Weight;
import net.dries007.tfc.common.fluids.FluidHelpers;
import net.dries007.tfc.common.recipes.BarrelRecipe;
import net.dries007.tfc.common.recipes.SealedBarrelRecipe;
import net.dries007.tfc.common.recipes.TFCRecipeTypes;
import net.dries007.tfc.common.recipes.inventory.EmptyInventory;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.calendar.CalendarTransaction;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.ICalendarTickable;

import su.terrafirmagreg.core.TFGCore;
import su.terrafirmagreg.core.common.block.LargeJugBlock;
import su.terrafirmagreg.core.common.container.LargeJugContainer;
import su.terrafirmagreg.core.common.data.TFGBlockEntities;
import su.terrafirmagreg.core.config.TFGConfig;

public class LargeJugBlockEntity extends TickableInventoryBlockEntity<LargeJugBlockEntity.JugInventory> implements ICalendarTickable, BarrelInventoryCallback, IRecipeTimer
{
    public static final int SLOT_FLUID_CONTAINER_IN = 0;
    public static final int SLOT_FLUID_CONTAINER_OUT = 1;
    public static final int SLOT_ITEM = 2;
    public static final int SLOTS = 3;

    private static final Component NAME = Component.translatable(TFGCore.MOD_ID + ".block_entity.large_jug");

    public static void serverTick(Level level, BlockPos pos, BlockState state, LargeJugBlockEntity jug)
    {
        // Must run before checkForCalendarUpdate(), as this sets the current recipe.
        if (jug.recipeName != null)
        {
            jug.recipe = level.getRecipeManager().byKey(jug.recipeName)
                .map(b -> b instanceof SealedBarrelRecipe r ? r : null)
                .orElse(null);
            jug.recipeName = null;
        }

        jug.checkForLastTickSync();
        jug.checkForCalendarUpdate();

        if (level.getGameTime() % 5 == 0)
        {
            jug.updateFluidIOSlots();
        }

        final List<ItemStack> excess = jug.inventory.excess;
        if (!excess.isEmpty() && jug.inventory.getStackInSlot(SLOT_ITEM).isEmpty())
        {
            jug.inventory.setStackInSlot(SLOT_ITEM, excess.remove(0));
        }

        final SealedBarrelRecipe recipe = jug.recipe;
        final boolean sealed = state.getValue(LargeJugBlock.SEALED);
        if (recipe != null && sealed)
        {
            final int durationSealed = (int) (Calendars.SERVER.getTicks() - jug.recipeTick);
            if (!recipe.isInfinite() && durationSealed > recipe.getDuration())
            {
                if (recipe.matches(jug.inventory, level))
                {
                    // Recipe completed, so fill outputs
                    recipe.assembleOutputs(jug.inventory);
                    Helpers.playSound(level, jug.getBlockPos(), recipe.getCompleteSound());
                }

                // In both cases, update the recipe and sync
                jug.updateRecipe();
                jug.markForSync();

                // Re-check the recipe. If we have an invalid or infinite recipe, then exit simulation. Otherwise, jump forward to the next recipe completion
                // This handles the case where multiple sequential recipes, such as brining -> pickling -> vinegar preservation would've occurred.
                final SealedBarrelRecipe knownRecipe = jug.recipe;
                if (knownRecipe != null)
                {
                    knownRecipe.onSealed(jug.inventory); // We're in a sequential recipe, so apply sealed affects to the new recipe
                }
            }
        }

        if (jug.needsInstantRecipeUpdate)
        {
            jug.needsInstantRecipeUpdate = false;
            if (jug.inventory.excess.isEmpty()) // Excess must be empty for instant recipes to apply
            {
                final RecipeManager recipeManager = level.getRecipeManager();
                Optional.<BarrelRecipe>empty() // For type erasure
                    .or(() -> recipeManager.getRecipeFor(TFCRecipeTypes.BARREL_INSTANT.get(), jug.inventory, level))
                    .or(() -> recipeManager.getRecipeFor(TFCRecipeTypes.BARREL_INSTANT_FLUID.get(), jug.inventory, level))
                    .ifPresent(instantRecipe -> {
                        instantRecipe.assembleOutputs(jug.inventory);
                        if (jug.soundCooldownTicks == 0)
                        {
                            Helpers.playSound(level, jug.getBlockPos(), instantRecipe.getCompleteSound());
                            jug.soundCooldownTicks = 5;
                        }
                    });
                jug.markForSync();
            }
        }

        if (jug.soundCooldownTicks > 0)
        {
            jug.soundCooldownTicks--;
        }
    }

    @Nullable private ResourceLocation recipeName;
    @Nullable private SealedBarrelRecipe recipe;
    private long lastUpdateTick = Integer.MIN_VALUE; // The last tick this jug was updated in serverTick()
    private long sealedTick; // The tick this jug was sealed
    private long recipeTick; // The tick this jug started working on the current recipe
    private int soundCooldownTicks = 0;

    private boolean needsInstantRecipeUpdate; // If the instant recipe needs to be checked again

    public LargeJugBlockEntity(BlockPos pos, BlockState state)
    {
        super(TFGBlockEntities.LARGE_JUG.get(), pos, state, JugInventory::new, NAME);
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player)
    {
        return LargeJugContainer.create(this, player.getInventory(), containerId);
    }

    @Override
    public void setAndUpdateSlots(int slot)
    {
        super.setAndUpdateSlots(slot);
        needsInstantRecipeUpdate = true;
        updateRecipe();
    }

    @Override
    public void fluidTankChanged()
    {
        needsInstantRecipeUpdate = true;
        updateRecipe();
        setChanged();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack)
    {
        return switch (slot)
            {
                case SLOT_FLUID_CONTAINER_IN -> Helpers.mightHaveCapability(stack, Capabilities.FLUID_ITEM);
                case SLOT_ITEM -> ItemSizeManager.get(stack).getSize(stack).isEqualOrSmallerThan(TFCConfig.SERVER.chestMaximumItemSize.get());
                default -> true;
            };
    }

    @Override
    public void onCalendarUpdate(long ticks)
    {
        assert level != null;

        try (CalendarTransaction tr = Calendars.SERVER.transaction())
        {
            tr.add(-ticks); // Perform the recipe update in the past
            updateRecipe();
        }
        if (!getBlockState().getValue(LargeJugBlock.SEALED) || recipe == null || recipe.isInfinite())
        {
            return; // No simulation occurs if we were not sealed, or if we had no recipe, or if we had an infinite recipe.
        }

        // Otherwise, begin simulation by jumping to the end tick of the current recipe. If that was in the past, we simulate and retry.
        final long currentTick = Calendars.SERVER.getTicks();
        long lastKnownTick = recipeTick + recipe.getDuration();
        while (lastKnownTick < currentTick)
        {
            // Need to run the recipe completion, as it occurred in the past
            final long offset = currentTick - lastKnownTick;
            assert offset >= 0; // This event should be in the past

            try (CalendarTransaction tr = Calendars.SERVER.transaction())
            {
                tr.add(-offset);

                final BarrelRecipe recipe = this.recipe;
                if (recipe.matches(inventory, null))
                {
                    recipe.assembleOutputs(inventory);
                }
                updateRecipe();
                markForSync();
            }

            // Re-check the recipe. If we have an invalid or infinite recipe, then exit simulation. Otherwise, jump forward to the next recipe completion
            // This handles the case where multiple sequential recipes, such as brining -> pickling -> vinegar preservation would've occurred.
            final SealedBarrelRecipe knownRecipe = recipe;
            if (knownRecipe == null)
            {
                return;
            }
            knownRecipe.onSealed(inventory); // We're in a sequential recipe, so apply sealed affects to the new recipe
            if (knownRecipe.isInfinite())
            {
                return; // No more simulation can occur
            }
            lastKnownTick += recipe.getDuration();
        }
    }

    @Override
    public void saveAdditional(CompoundTag nbt)
    {
        nbt.putLong("lastUpdateTick", lastUpdateTick);
        nbt.putLong("sealedTick", sealedTick);
        nbt.putLong("recipeTick", recipeTick);
        if (recipe != null)
        {
            // Recipe saved to sync to client
            nbt.putString("recipe", recipe.getId().toString());
        }
        else if (recipeName != null)
        {
            nbt.putString("recipeName", recipeName.toString());
        }
        super.saveAdditional(nbt);
    }

    @Override
    public void loadAdditional(CompoundTag nbt)
    {
        lastUpdateTick = nbt.getLong("lastUpdateTick");
        sealedTick = nbt.getLong("sealedTick");
        recipeTick = nbt.getLong("recipeTick");

        recipe = null;
        recipeName = null;
        if (nbt.contains("recipe", Tag.TAG_STRING))
        {
            recipeName = Helpers.resourceLocation(nbt.getString("recipe"));
            if (level != null)
            {
                recipe = level.getRecipeManager().byKey(recipeName)
                    .map(b -> b instanceof SealedBarrelRecipe r ? r : null)
                    .orElse(null);
            }
        }
        super.loadAdditional(nbt);
    }

    @Override
    @Deprecated
    public long getLastCalendarUpdateTick()
    {
        return lastUpdateTick;
    }

    @Override
    @Deprecated
    public void setLastCalendarUpdateTick(long tick)
    {
        lastUpdateTick = tick;
    }

    @Override
    public int getRecipeDuration()
    {
        if (level == null)
            return 0;

        @Nullable SealedBarrelRecipe recipe = level.getRecipeManager().getRecipeFor(TFCRecipeTypes.BARREL_SEALED.get(), inventory, level).orElse(null);
        return recipe != null ? recipe.getDuration() : 0;
    }

    @Override
    public long getRemainingTime()
    {
        return getRemainingTicks();
    }

    @Override
    public void ejectInventory()
    {
        super.ejectInventory();
        assert level != null;
        inventory.excess.stream().filter(item -> !item.isEmpty()).forEach(item -> Helpers.spawnItem(level, worldPosition, item));
    }

    public void onSeal()
    {
        assert level != null;
        if (!level.isClientSide())
        {
            // Drop container items, but allow the main slot to be filled
            for (int slot : new int[] {SLOT_FLUID_CONTAINER_IN, SLOT_FLUID_CONTAINER_OUT})
            {
                Helpers.spawnItem(level, worldPosition, inventory.getStackInSlot(slot));
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }

        // Apply the preserved trait to the item slot.
        final ItemStack itemStack = inventory.getStackInSlot(SLOT_ITEM);
        if (!itemStack.isEmpty())
        {
            inventory.setStackInSlot(SLOT_ITEM, FoodCapability.applyTrait(itemStack.copy(), FoodTraits.PRESERVED));
        }

        sealedTick = Calendars.get(level).getTicks();
        updateRecipe();
        if (recipe != null)
        {
            recipe.onSealed(inventory);
            recipeTick = sealedTick;
        }
        markForSync();
        Helpers.playSound(level, worldPosition, TFCSounds.CLOSE_VESSEL.get());
    }

    public void onUnseal()
    {
        assert level != null;

        // Remove the preserved trait from the item slot.
        final ItemStack itemStack = inventory.getStackInSlot(SLOT_ITEM);
        if (!itemStack.isEmpty())
        {
            inventory.setStackInSlot(SLOT_ITEM, FoodCapability.removeTrait(itemStack.copy(), FoodTraits.PRESERVED));
        }

        sealedTick = recipeTick = 0;
        if (recipe != null)
        {
            recipe.onUnsealed(inventory);
        }
        updateRecipe();
        markForSync();
        Helpers.playSound(level, worldPosition, TFCSounds.OPEN_VESSEL.get());
    }

    @Override
    public boolean canModify()
    {
        return !getBlockState().getValue(LargeJugBlock.SEALED);
    }

    protected void updateRecipe()
    {
        assert level != null;

        final SealedBarrelRecipe oldRecipe = recipe;
        if (inventory.excess.isEmpty())
        {
            // Will only work on a recipe as long as the 'excess' is empty
            recipe = level.getRecipeManager().getRecipeFor(TFCRecipeTypes.BARREL_SEALED.get(), inventory, level).orElse(null);
            if (recipe != null && oldRecipe != recipe && (oldRecipe == null || !oldRecipe.getId().equals(recipe.getId())))
            {
                // The recipe has changed to a new one, so update the recipe ticks
                recipeTick = Calendars.get(level).getTicks();
                markForSync();
            }
        }
    }

    private void updateFluidIOSlots()
    {
        assert level != null;
        final ItemStack input = inventory.getStackInSlot(SLOT_FLUID_CONTAINER_IN);
        if (!input.isEmpty() && inventory.getStackInSlot(SLOT_FLUID_CONTAINER_OUT).isEmpty())
        {
            FluidHelpers.transferBetweenBlockEntityAndItem(input, this, level, worldPosition, (newOriginalStack, newContainerStack) -> {
                if (newContainerStack.isEmpty())
                {
                    // No new container was produced, so shove the first stack in the output, and clear the input
                    inventory.setStackInSlot(SLOT_FLUID_CONTAINER_IN, ItemStack.EMPTY);
                    inventory.setStackInSlot(SLOT_FLUID_CONTAINER_OUT, newOriginalStack);
                }
                else
                {
                    // We produced a new container - this will be the 'filled', so we need to shove *that* in the output
                    inventory.setStackInSlot(SLOT_FLUID_CONTAINER_IN, newOriginalStack);
                    inventory.setStackInSlot(SLOT_FLUID_CONTAINER_OUT, newContainerStack);
                }
            });
        }
    }

    @Nullable
    public BarrelRecipe getRecipe()
    {
        return recipe;
    }

    public long getSealedTick()
    {
        return sealedTick;
    }

    public long getRecipeTick()
    {
        return recipeTick;
    }

    // Client-side
    public long getRemainingTicks()
    {
        assert level != null;
        if (level.isClientSide)
        {
            if (recipe == null)
            {
                recipe = level.getRecipeManager().getRecipeFor(TFCRecipeTypes.BARREL_SEALED.get(), inventory, level).orElse(null);
            }
            if (recipe != null)
            {
                return recipe.getDuration() - (Calendars.get(level).getTicks() - recipeTick);
            }
        }
        return 0;
    }

    public static class JugInventory implements DelegateItemHandler, DelegateFluidHandler, INBTSerializable<CompoundTag>, EmptyInventory, FluidTankCallback, net.dries007.tfc.common.recipes.inventory.BarrelInventory
    {
        private final BarrelInventoryCallback callback;
        private final InventoryItemHandler inventory;
        private final List<ItemStack> excess;
        private final InventoryFluidTank tank;
        private boolean mutable; // If the inventory is pretending to be mutable, despite the jug being sealed and preventing extractions / insertions

        JugInventory(InventoryBlockEntity<?> entity)
        {
            this((BarrelInventoryCallback) entity);
        }

        public JugInventory(BarrelInventoryCallback callback)
        {
            this.callback = callback;
            inventory = new InventoryItemHandler(callback, SLOTS);
            excess = new ArrayList<>();
            tank = new InventoryFluidTank(Helpers.getValueOrDefault(TFGConfig.SERVER.largeJugCapacity), stack -> Helpers.isFluid(stack.getFluid(), TFCTags.Fluids.USABLE_IN_BARREL), this);
        }

        @Override
        public void whileMutable(Runnable action)
        {
            try
            {
                mutable = true;
                action.run();
            }
            finally
            {
                mutable = false;
            }
        }

        public boolean isInventoryEmpty()
        {
            return tank.getFluid().isEmpty() && excess.isEmpty() && Helpers.isEmpty(inventory);
        }

        @Override
        public void insertItemWithOverflow(ItemStack stack)
        {
            final ItemStack remainder = inventory.insertItem(SLOT_ITEM, stack, false);
            if (!remainder.isEmpty())
            {
                excess.add(remainder);
            }
        }

        @Override
        public IItemHandlerModifiable getItemHandler()
        {
            return inventory;
        }

        @Override
        public IFluidHandler getFluidHandler()
        {
            return tank;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action)
        {
            return canModify() ? tank.fill(resource, action) : 0;
        }

        @NotNull
        @Override
        public FluidStack drain(FluidStack resource, FluidAction action)
        {
            return canModify() ? tank.drain(resource, action) : FluidStack.EMPTY;
        }

        @NotNull
        @Override
        public FluidStack drain(int maxDrain, FluidAction action)
        {
            return canModify() ? tank.drain(maxDrain, action) : FluidStack.EMPTY;
        }

        @NotNull
        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate)
        {
            return canModify() ? inventory.insertItem(slot, stack, simulate) : stack;
        }

        @NotNull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate)
        {
            return canModify() ? inventory.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack)
        {
            return canModify() && DelegateItemHandler.super.isItemValid(slot, stack);
        }

        @Override
        public CompoundTag serializeNBT()
        {
            final CompoundTag nbt = new CompoundTag();
            nbt.put("inventory", inventory.serializeNBT());
            nbt.put("tank", tank.writeToNBT(new CompoundTag()));

            if (!excess.isEmpty())
            {
                final ListTag excessNbt = new ListTag();
                for (ItemStack stack : excess)
                {
                    excessNbt.add(stack.save(new CompoundTag()));
                }
                nbt.put("excess", excessNbt);
            }

            return nbt;
        }

        @Override
        public void deserializeNBT(CompoundTag nbt)
        {
            inventory.deserializeNBT(nbt.getCompound("inventory"));
            tank.readFromNBT(nbt.getCompound("tank"));

            excess.clear();
            if (nbt.contains("excess"))
            {
                final ListTag excessNbt = nbt.getList("excess", Tag.TAG_COMPOUND);
                for (int i = 0; i < excessNbt.size(); i++)
                {
                    excess.add(ItemStack.of(excessNbt.getCompound(i)));
                }
            }
        }

        @Override
        public void fluidTankChanged()
        {
            callback.fluidTankChanged();
        }

        private boolean canModify()
        {
            return mutable || callback.canModify();
        }
    }
}
