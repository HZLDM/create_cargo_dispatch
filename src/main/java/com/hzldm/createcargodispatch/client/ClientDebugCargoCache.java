package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.network.SyncDebugCargoPayload;

import java.util.List;

/**
 * 调试货箱编辑页客户端缓存（整表替换，服务端权威）。编辑对象为主手调试物品。
 */
public final class ClientDebugCargoCache {

    private static volatile String orderId = "";
    private static volatile String sourceTypeId = "generic";
    private static volatile String cargoItemId = "";
    private static volatile com.hzldm.createcargodispatch.cargo.CargoDimensions dims =
            com.hzldm.createcargodispatch.cargo.CargoDimensions.DEFAULT;
    private static volatile int selectedTarget = -1;
    private static volatile List<SyncDebugCargoPayload.TargetEntry> targets = List.of();

    private ClientDebugCargoCache() {
    }

    public static void update(SyncDebugCargoPayload payload) {
        orderId = payload.orderId() == null ? "" : payload.orderId();
        sourceTypeId = payload.sourceTypeId() == null ? "generic" : payload.sourceTypeId();
        cargoItemId = payload.cargoItemId() == null ? "" : payload.cargoItemId();
        dims = new com.hzldm.createcargodispatch.cargo.CargoDimensions(
                payload.dimW(), payload.dimH(), payload.dimL());
        selectedTarget = payload.selectedTarget();
        targets = payload.targets() == null ? List.of() : List.copyOf(payload.targets());
    }

    public static com.hzldm.createcargodispatch.cargo.CargoDimensions getDims() { return dims; }

    public static void setDims(com.hzldm.createcargodispatch.cargo.CargoDimensions d) {
        dims = d != null ? d : com.hzldm.createcargodispatch.cargo.CargoDimensions.DEFAULT;
    }

    public static String getOrderId() { return orderId; }
    public static String getSourceTypeId() { return sourceTypeId; }
    public static String getCargoItemId() { return cargoItemId; }
    public static int getSelectedTarget() { return selectedTarget; }
    public static List<SyncDebugCargoPayload.TargetEntry> getTargets() { return targets; }

    public static void setSelectedTarget(int index) {
        selectedTarget = index;
    }

    public static void setSelection(String sourceTypeId, String cargoItemId) {
        if (sourceTypeId != null) ClientDebugCargoCache.sourceTypeId = sourceTypeId;
        if (cargoItemId != null) ClientDebugCargoCache.cargoItemId = cargoItemId;
    }
}
