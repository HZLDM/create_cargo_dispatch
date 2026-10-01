package com.hzldm.createcargodispatch.item;

import com.hzldm.createcargodispatch.cargo.CargoDimensions;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 调试货箱（不可放置的纯物品）。
 *
 * 工作流：右键打开编辑页设定货物内容/目标站/源类型/订单号 → 「生成货箱」后变为可放置的
 * CargoBlockItem（携带 BLOCK_ENTITY_DATA，放置即配置好的货箱）。配置持久化在物品组件：
 *  - CONTAINER：9 格货物内容
 *  - CUSTOM_DATA：订单号/源类型/目标坐标等（tag 结构与 {@link CargoData} 对齐）
 */
public class DebugCargoItem extends Item {

    public static final String TAG_ORDER_ID = "OrderId";
    public static final String TAG_SRC_TYPE = "SrcType";
    public static final String TAG_HAS_TARGET = "HasTarget";
    public static final String TAG_TGT_X = "TgtX";
    public static final String TAG_TGT_Y = "TgtY";
    public static final String TAG_TGT_Z = "TgtZ";
    public static final String TAG_TGT_TYPE = "TgtType";
    public static final String TAG_TGT_ID = "TgtId";
    /** 自动货物的物品 id（命名空间:路径），属于所选源类型货物池 */
    public static final String TAG_CARGO_ITEM = "CargoItem";
    /** 货箱尺寸（奇数格），缺省标准 3×3×9 */
    public static final String TAG_DIM_W = "DimW", TAG_DIM_H = "DimH", TAG_DIM_L = "DimL";

    public DebugCargoItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new com.hzldm.createcargodispatch.menu.DebugCargoMenu(id, inv),
                    Component.translatable("create_cargo_dispatch.debug_cargo.title")));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    // ===== 组件读取 =====

    public static CompoundTag dataTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : new CompoundTag();
    }

    public static String getOrderId(ItemStack stack) {
        return dataTag(stack).getString(TAG_ORDER_ID);
    }

    public static String getSourceTypeId(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        return tag.contains(TAG_SRC_TYPE) ? tag.getString(TAG_SRC_TYPE) : StationType.GENERIC.getId();
    }

    public static boolean hasTarget(ItemStack stack) {
        return dataTag(stack).getBoolean(TAG_HAS_TARGET);
    }

    public static BlockPos getTargetPos(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        return new BlockPos(tag.getInt(TAG_TGT_X), tag.getInt(TAG_TGT_Y), tag.getInt(TAG_TGT_Z));
    }

    public static String getTargetTypeId(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        return tag.contains(TAG_TGT_TYPE) ? tag.getString(TAG_TGT_TYPE) : StationType.GENERIC.getId();
    }

    public static String getTargetStationId(ItemStack stack) {
        return dataTag(stack).getString(TAG_TGT_ID);
    }

    public static String getCargoItemId(ItemStack stack) {
        return dataTag(stack).getString(TAG_CARGO_ITEM);
    }

    /** 读取货箱尺寸；旧物品缺字段→标准默认 */
    public static CargoDimensions getDimensions(ItemStack stack) {
        CompoundTag tag = dataTag(stack);
        if (!tag.contains(TAG_DIM_W)) return CargoDimensions.DEFAULT;
        return new CargoDimensions(tag.getInt(TAG_DIM_W), tag.getInt(TAG_DIM_H), tag.getInt(TAG_DIM_L));
    }

    /** 写入货箱尺寸 */
    public static void writeDimensions(ItemStack stack, CargoDimensions dims) {
        final CargoDimensions d = dims != null ? dims : CargoDimensions.DEFAULT;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putInt(TAG_DIM_W, d.width());
            tag.putInt(TAG_DIM_H, d.height());
            tag.putInt(TAG_DIM_L, d.length());
        });
    }

    // ===== 组件写入 =====

    public static void writeFields(ItemStack stack, String orderId, String sourceTypeId, String cargoItemId) {
        if (orderId != null) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(TAG_ORDER_ID, orderId.trim()));
        }
        if (sourceTypeId != null) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(TAG_SRC_TYPE, sourceTypeId));
        }
        if (cargoItemId != null) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(TAG_CARGO_ITEM, cargoItemId));
        }
    }

    /** 选择目标（index<0 或 entry=null 表示清除目标） */
    public static void writeTarget(ItemStack stack,
                                   com.hzldm.createcargodispatch.network.SyncDebugCargoPayload.TargetEntry target) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (target == null) {
                tag.putBoolean(TAG_HAS_TARGET, false);
                tag.remove(TAG_TGT_X);
                tag.remove(TAG_TGT_Y);
                tag.remove(TAG_TGT_Z);
                tag.remove(TAG_TGT_TYPE);
                tag.remove(TAG_TGT_ID);
            } else {
                tag.putBoolean(TAG_HAS_TARGET, true);
                tag.putInt(TAG_TGT_X, target.x());
                tag.putInt(TAG_TGT_Y, target.y());
                tag.putInt(TAG_TGT_Z, target.z());
                tag.putString(TAG_TGT_TYPE, target.typeId());
                tag.putString(TAG_TGT_ID, target.stationId() == null ? "" : target.stationId());
            }
        });
    }

    // ===== tooltip =====

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String sourceId = getSourceTypeId(stack);
        tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.source",
                Component.translatable(StationType.byId(sourceId).getTranslationKey()))
                .withStyle(ChatFormatting.GOLD));
        String cargoItemId = getCargoItemId(stack);
        Item cargoItem = cargoItemId.isEmpty() ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(ResourceLocation.tryParse(cargoItemId));
        if (cargoItem != null) {
            tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.cargo",
                    new ItemStack(cargoItem).getHoverName()).withStyle(ChatFormatting.YELLOW));
        }
        if (hasTarget(stack)) {
            BlockPos pos = getTargetPos(stack);
            tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.target",
                    Component.translatable("create_cargo_dispatch.station_short." + getTargetTypeId(stack)),
                    pos.getX(), pos.getY(), pos.getZ())
                    .withStyle(ChatFormatting.GREEN));
        } else {
            tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.no_target")
                    .withStyle(ChatFormatting.GRAY));
        }
        String orderId = getOrderId(stack);
        tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.order",
                orderId.isEmpty() ? "-" : orderId).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
