/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package su.terrafirmagreg.core.common.container;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import net.dries007.tfc.common.blocks.devices.SealableDeviceBlock;
import net.dries007.tfc.common.capabilities.Capabilities;
import net.dries007.tfc.common.container.BlockEntityContainer;
import net.dries007.tfc.common.container.ButtonHandlerContainer;
import net.dries007.tfc.common.container.CallbackSlot;

import su.terrafirmagreg.core.common.block.LargeJugBlock;
import su.terrafirmagreg.core.common.blockentity.LargeJugBlockEntity;
import su.terrafirmagreg.core.common.data.TFGContainers;

public class LargeJugContainer extends BlockEntityContainer<LargeJugBlockEntity> implements ButtonHandlerContainer
{
    public static LargeJugContainer create(LargeJugBlockEntity jug, Inventory playerInv, int windowId)
    {
        return new LargeJugContainer(windowId, jug).init(playerInv, 12);
    }

    private LargeJugContainer(int windowId, LargeJugBlockEntity jug)
    {
        super(TFGContainers.LARGE_JUG.get(), windowId, jug);
    }

    @Override
    public void clicked(int slot, int button, ClickType clickType, Player player)
    {
        if (slot >= 0 && slot < LargeJugBlockEntity.SLOTS && blockEntity.getBlockState().getValue(SealableDeviceBlock.SEALED))
        {
            return;
        }
        super.clicked(slot, button, clickType, player);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onButtonPress(int buttonID, @Nullable CompoundTag extraNBT)
    {
        Level level = blockEntity.getLevel();
        if (level != null)
        {
            LargeJugBlock.toggleSeal(level, blockEntity.getBlockPos(), blockEntity.getBlockState(), (BlockEntityType<? extends LargeJugBlockEntity>) blockEntity.getType());
        }
    }

    @Override
    protected void addContainerSlots()
    {
        blockEntity.getCapability(Capabilities.ITEM).ifPresent(inventory -> {
            addSlot(new CallbackSlot(blockEntity, inventory, LargeJugBlockEntity.SLOT_FLUID_CONTAINER_IN, 35, 20));
            addSlot(new CallbackSlot(blockEntity, inventory, LargeJugBlockEntity.SLOT_FLUID_CONTAINER_OUT, 35, 54));
            addSlot(new CallbackSlot(blockEntity, inventory, LargeJugBlockEntity.SLOT_ITEM, 89, 37));
        });
    }

    @Override
    protected boolean moveStack(ItemStack stack, int slotIndex)
    {
        if (blockEntity.getBlockState().getValue(LargeJugBlock.SEALED))
        {
            return true;
        }

        final int containerSlot = stack.getCapability(Capabilities.FLUID_ITEM).isPresent() ? LargeJugBlockEntity.SLOT_FLUID_CONTAINER_IN : LargeJugBlockEntity.SLOT_ITEM;

        return switch (typeOf(slotIndex))
            {
                case MAIN_INVENTORY, HOTBAR -> !moveItemStackTo(stack, containerSlot, containerSlot + 1, false);
                case CONTAINER -> !moveItemStackTo(stack, containerSlots, slots.size(), false);
            };
    }
}
