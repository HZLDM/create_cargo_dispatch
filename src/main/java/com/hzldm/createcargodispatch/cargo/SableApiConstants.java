package com.hzldm.createcargodispatch.cargo;

/**
 * Sable 反射 API 的常量集合（单一职责：集中维护所有类名/方法名字符串，避免跨类重复字面量）。
 * <p>
 * 集中管理的好处：升级 Sable 版本时只需修改此一处，且能保证 SubLevelScanner / CargoPhysicsHelper
 * 使用完全一致的目标类/方法名，不再出现 A 类改了 B 类仍用旧名的维护隐患。
 */
public final class SableApiConstants {

    private SableApiConstants() {}

    // ==================== 类全限定名 ====================
    public static final String CLASS_SABLE                = "dev.ryanhcode.sable.Sable";
    public static final String CLASS_SUBLEVEL_CONTAINER   = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    public static final String CLASS_SERVER_CONTAINER     = "dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer";
    public static final String CLASS_SUBLEVEL             = "dev.ryanhcode.sable.sublevel.SubLevel";
    public static final String CLASS_SERVER_SUBLEVEL      = "dev.ryanhcode.sable.sublevel.ServerSubLevel";
    public static final String CLASS_SUBLEVEL_ASSEMBLY    = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper";
    public static final String CLASS_REMOVAL_REASON       = "dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason";
    public static final String CLASS_BOUNDING_BOX_3DC     = "dev.ryanhcode.sable.companion.math.BoundingBox3dc";
    public static final String CLASS_BOUNDING_BOX_3IC     = "dev.ryanhcode.sable.companion.math.BoundingBox3ic";
    public static final String CLASS_BOUNDING_BOX_3D      = "dev.ryanhcode.sable.companion.math.BoundingBox3d";
    public static final String CLASS_BOUNDING_BOX_3I      = "dev.ryanhcode.sable.companion.math.BoundingBox3i";
    public static final String CLASS_POSE_3D              = "dev.ryanhcode.sable.companion.math.Pose3d";
    public static final String CLASS_MASS_TRACKER         = "dev.ryanhcode.sable.api.physics.mass.MassTracker";
    public static final String CLASS_MERGED_MASS_TRACKER  = "dev.ryanhcode.sable.api.physics.mass.MergedMassTracker";
    public static final String CLASS_LEVEL_PLOT           = "dev.ryanhcode.sable.sublevel.plot.LevelPlot";
    public static final String CLASS_PHYSICS_SYSTEM       = "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem";
    public static final String CLASS_PHYSICS_PIPELINE     = "dev.ryanhcode.sable.api.physics.PhysicsPipeline";
    public static final String CLASS_PHYSICS_PIPELINE_PROV = "dev.ryanhcode.sable.api.physics.PhysicsPipelineProvider";
    public static final String CLASS_RIGID_BODY_HANDLE    = "dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle";
    public static final String CLASS_FIXED_CONSTRAINT_CFG = "dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration";
    public static final String CLASS_BLOCK_PROPERTY_TYPES = "dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyTypes";
    public static final String CLASS_BLOCK_STATE_EXT      = "dev.ryanhcode.sable.mixinterface.block_properties.BlockStateExtension";

    // ==================== 枚举常量名 ====================
    public static final String ENUM_REMOVAL_REMOVED = "REMOVED";
    public static final String ENUM_REMOVAL_DISMANTLE = "DISMANTLED";

    // ==================== 方法名 ====================
    public static final String METHOD_GET_CONTAINER           = "getContainer";
    public static final String METHOD_GET_ALL_SUBLEVELS       = "getAllSubLevels";
    public static final String METHOD_REMOVE_SUBLEVEL         = "removeSubLevel";
    public static final String METHOD_GET_SUBLEVEL            = "getSubLevel";
    public static final String METHOD_GET_LOG_PLOT_SIZE       = "getLogPlotSize";
    public static final String METHOD_GET_ORIGIN              = "getOrigin";
    public static final String METHOD_PHYSICS_SYSTEM          = "physicsSystem";
    public static final String METHOD_GET_LEVEL               = "getLevel";
    public static final String METHOD_GET_PLOT                = "getPlot";
    public static final String METHOD_GET_MASS_TRACKER        = "getMassTracker";
    public static final String METHOD_BOUNDING_BOX            = "boundingBox";
    public static final String METHOD_GET_BOUNDING_BOX        = "getBoundingBox";
    public static final String METHOD_GET_UNIQUE_ID           = "getUniqueId";
    public static final String METHOD_LOGICAL_POSE            = "logicalPose";
    public static final String METHOD_SET_NAME                = "setName";
    public static final String METHOD_GET_NAME                = "getName";
    public static final String METHOD_ASSEMBLE_BLOCKS         = "assembleBlocks";
    public static final String METHOD_BB_FROM                 = "from";
    public static final String METHOD_GET_MASS                = "getMass";
    public static final String METHOD_GET_CENTER_OF_MASS      = "getCenterOfMass";
    public static final String METHOD_IS_REMOVED              = "isRemoved";
    public static final String METHOD_UPDATE_LAST_POSE        = "updateLastPose";
    public static final String METHOD_UPDATE_POSE             = "updatePose";
    public static final String METHOD_GET_PIPELINE            = "getPipeline";
    public static final String METHOD_TELEPORT                = "teleport";
    public static final String METHOD_SET_MASS                = "setMass";
    public static final String METHOD_GET_PHYSICS_HANDLE      = "getPhysicsHandle";
    public static final String METHOD_GET_LINEAR_VELOCITY     = "getLinearVelocity";
    public static final String METHOD_GET_ANGULAR_VELOCITY    = "getAngularVelocity";
    public static final String METHOD_ADD_LINEAR_AND_ANGULAR_VEL = "addLinearAndAngularVelocity";
    public static final String METHOD_ADD_CONSTRAINT          = "addConstraint";

    // ==================== Pose3d 的方法/字段（局部→全局几何变换用） ====================
    public static final String METHOD_POSE_POSITION           = "position";
    public static final String METHOD_POSE_ORIENTATION        = "orientation";
    public static final String METHOD_POSE_TRANSFORM_POSITION  = "transformPosition";

    // ==================== SubLevel plot 坐标相关 ====================
    public static final String METHOD_GET_CENTER_CHUNK        = "getCenterChunk";
}
