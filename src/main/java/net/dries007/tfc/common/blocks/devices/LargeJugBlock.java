/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.blocks.devices;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.Tags;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

import net.dries007.tfc.common.blockentities.LargeJugBlockEntity;
import net.dries007.tfc.common.blockentities.TFCBlockEntities;
import net.dries007.tfc.common.blocks.ExtendedProperties;
import net.dries007.tfc.common.capabilities.Capabilities;
import net.dries007.tfc.common.fluids.FluidHelpers;
import net.dries007.tfc.config.TFCConfig;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.Tooltips;

public class LargeJugBlock extends SealableDeviceBlock
{
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final VoxelShape SHAPE = Shapes.or(
        box(4, 0, 4, 12, 1, 12),
        box(3, 1, 3, 13, 6, 13),
        box(4, 6, 4, 12, 7, 12),
        box(6, 7, 6, 10, 14, 10)
    );

    public static void toggleSeal(Level level, BlockPos pos, BlockState state)
    {
        level.getBlockEntity(pos, TFCBlockEntities.LARGE_JUG.get()).ifPresent(jug -> {
            final boolean previousSealed = state.getValue(SEALED);
            level.setBlockAndUpdate(pos, state.setValue(SEALED, !previousSealed));

            if (previousSealed)
            {
                jug.onUnseal();
            }
            else
            {
                jug.onSeal();
            }
        });
    }

    private static final int[] IMAGE_TOOLTIP = {1, 1, 2, 2};

    public LargeJugBlock(ExtendedProperties properties)
    {
        super(properties);
        registerDefaultState(getStateDefinition().any()
            .setValue(SEALED, false)
            .setValue(FACING, Direction.NORTH)
            .setValue(POWERED, false));
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)
    {
        final LargeJugBlockEntity jug = level.getBlockEntity(pos, TFCBlockEntities.LARGE_JUG.get()).orElse(null);
        if (jug != null)
        {
            final ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() && player.isShiftKeyDown())
            {
                toggleSeal(level, pos, state);
                level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0f, 0.85f);
                return InteractionResult.SUCCESS;
            }
            else if (FluidHelpers.transferBetweenBlockEntityAndItem(stack, jug, player, hand))
            {
                return InteractionResult.SUCCESS;
            }
            else if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer)
            {
                Helpers.openScreen(serverPlayer, jug, jug.getBlockPos());
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void addExtraInfo(List<Component> tooltip, CompoundTag inventoryTag)
    {
        final FluidTank tank = new FluidTank(TFCConfig.SERVER.largeJugCapacity.get());
        tank.readFromNBT(inventoryTag.getCompound("tank"));
        if (!tank.isEmpty())
        {
            tooltip.add(Tooltips.fluidUnitsOf(tank.getFluid()));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level, BlockPos pos, BlockPos facingPos)
    {
        // Require a supporting block below.
        if (facing == Direction.DOWN && !level.getBlockState(facingPos).isFaceSturdy(level, facingPos, Direction.UP, SupportType.CENTER))
        {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    public int[] getImageTooltipParameters()
    {
        return IMAGE_TOOLTIP;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context)
    {
        BlockState state = super.getStateForPlacement(context);
        if (state != null)
        {
            state = state.setValue(FACING, context.getHorizontalDirection().getOpposite());

            final Level level = context.getLevel();
            final BlockPos pos = context.getClickedPos();

            // Require a supporting block below.
            if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP, SupportType.CENTER))
            {
                return null;
            }
        }
        return state;
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rot)
    {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror)
    {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        // Single collision shape, does not depend on FACING.
        return SHAPE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        super.createBlockStateDefinition(builder.add(FACING));
    }
}
