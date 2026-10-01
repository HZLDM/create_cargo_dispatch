package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.hzldm.createcargodispatch.cargo.SableApiConstants.*;

/**
 * Sable SubLevel 扫描器（软依赖，全部通过反射）
 *
 * <h3>修复记录（对应代码审查清单 P1~P16）：</h3>
 * <ul>
 *   <li><b>P1 修复</b>：removeSubLevel 改为 (int x, int z, reason) 局部 plot 坐标签名，从 SubLevel plot 计算 localX/localZ。</li>
 *   <li><b>P2 修复</b>：translateSubLevelBy 首选 PhysicsPipeline.teleport，次选改 pose.position + physicsSystem.updatePose，
 *       不再依赖 Sable 里不存在的 translate/move/setAnchor 公开 API。</li>
 *   <li><b>P3 修复</b>：BoundingBox 反射兼容 BoundingBox3ic（int 返回）与 BoundingBox3dc（double 返回）两套，
 *       使用 Number 统一收窄类型，避免 Double.class.cast(int) 抛 ClassCastException。</li>
 *   <li><b>P4 修复</b>：disassemble 内坐标 → 世界坐标走 logicalPose.transformPosition（支持旋转+平移）；
 *       不再用不存在的 globalize() 方法，也不再假设 inner==world。</li>
 *   <li><b>P6 修复</b>：findSubLevelContainingGlobalPos 改为使用 Sable.HELPER.getContaining(Level,BlockPos)。</li>
 *   <li><b>P7/P11/P12 修复</b>：反射初始化由 init() 的 DCL 同步单例 + 所有核心 API 初始化失败立刻 throw，
 *       不再 "log warn + 后续代码 NPE"。Sable 核心类缺失时 available=false 不抛错（兼容不装 Sable）。</li>
 *   <li><b>P13 修复</b>：disassemble/translate 加最大方块数上限（DISASSEMBLE_MAX_BLOCKS），避免大 SubLevel OOM。</li>
 *   <li><b>P16 修复</b>：所有类名/方法名常量统一走 {@link SableApiConstants}。</li>
 * </ul>
 */
public final class SubLevelScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-SubLevel");

    /** 拆卸/扫描方块数硬上限（防止大 SubLevel 拖垮 OOM，Sable assembleBlocks 也有 max 限制） */
    public static final int DISASSEMBLE_MAX_BLOCKS = 32768;

    private static volatile boolean initialized = false;
    private static volatile boolean available = false;

    // ============ SubLevelContainer ============
    private static MethodHandle getContainerHandle;
    private static MethodHandle getAllSubLevelsHandle;
    private static MethodHandle removeSubLevelHandle;   // 参数 (int x, int z, SubLevelRemovalReason)
    private static MethodHandle physicsSystemHandle;    // SubLevelContainer.physicsSystem()
    private static MethodHandle containerGetLogPlotSize; // SubLevelContainer.getLogPlotSize()
    private static MethodHandle containerGetOrigin;     // SubLevelContainer.getOrigin() 返回 Vector2i

    // ============ SubLevel ============
    private static MethodHandle getLevelHandle;
    private static MethodHandle boundingBoxHandle;
    private static MethodHandle getUniqueIdHandle;
    private static MethodHandle getMassTrackerHandle;
    private static MethodHandle getMassHandle;
    private static MethodHandle getPlotHandle;          // SubLevel.getPlot()
    private static MethodHandle logicalPoseHandle;      // SubLevel.logicalPose()
    private static MethodHandle isRemovedHandle;        // SubLevel.isRemoved()
    private static MethodHandle updateLastPoseHandle;   // SubLevel.updateLastPose()

    // ============ LevelPlot ============
    private static MethodHandle plotGetCenterChunk;     // LevelPlot.getCenterChunk() 返回 ChunkPos
    private static MethodHandle plotGetBoundingBox;     // LevelPlot.getBoundingBox()
    private static MethodHandle plotUpdateBounds;       // LevelPlot.updateBoundingBox()：按已加载 chunk 重建 localBounds
    private static MethodHandle plotGetChunkMin;        // LevelPlot.getChunkMin()：plot 在 grid 的起始 ChunkPos
    private static MethodHandle plotGetChunkMax;        // LevelPlot.getChunkMax()：plot 在 grid 的结束 ChunkPos

    // ============ Pose3d 几何变换 ============
    private static MethodHandle posePositionHandle;     // Pose3d.position() 返回 Vector3d（可写）
    private static MethodHandle poseOrientationHandle;  // Pose3d.orientation() 返回 Quaterniondc
    private static MethodHandle poseTransformPosHandle; // Pose3d.transformPosition(Vector3dc) → Vector3d (原地改)
    private static MethodHandle poseTransformPosInverseHandle; // Pose3d.transformPositionInverse（可选）
    private static Method vector3dSetXyz;               // Vector3d.set(double,double,double)
    private static Method vector3dGetX;                 // x()
    private static Method vector3dGetY;
    private static Method vector3dGetZ;
    private static Method quaternionTransformPos;       // Quaterniond.transform(Vector3d) 或者 Vector3d.rotate(quat)

    // ============ BoundingBox3ic / 3dc ============
    private static MethodHandle bboxMinX, bboxMinY, bboxMinZ, bboxMaxX, bboxMaxY, bboxMaxZ;
    // true = 方法返回 double；false = 返回 int（需要 Number 收窄）
    private static boolean bboxReturnsDouble = true;

    /** Plot 局部 bbox 专用 int 句柄（plot 返回 BoundingBox3ic，不能复用 3dc 句柄，否则接收者类型不匹配） */
    private static MethodHandle plotBboxMinX, plotBboxMinY, plotBboxMinZ,
            plotBboxMaxX, plotBboxMaxY, plotBboxMaxZ;
    /** plot bbox 接口类型（诊断类加载器分裂用，init 赋值） */
    private static Class<?> plotBboxInterfaceCls;

    // ============ PhysicsPipeline / PhysicsSystem ============
    private static MethodHandle systemGetPipeline;      // SubLevelPhysicsSystem.getPipeline()
    private static MethodHandle physicsSystemStaticGet; // SubLevelPhysicsSystem 静态 get(Level) 兜底
    private static MethodHandle pipelineTeleport;       // PhysicsPipeline.teleport(subLevel, Vector3dc, Quaterniondc)
    private static MethodHandle systemUpdatePose;       // SubLevelPhysicsSystem.updatePose(ServerSubLevel)
    private static MethodHandle systemGetPhysicsHandle; // SubLevelPhysicsSystem.getPhysicsHandle(ServerSubLevel) → RigidBodyHandle
    private static MethodHandle rigidBodySetMass;       // RigidBodyHandle.setMass(double) 或同名 API

    // ============ 固定约束（连接器即时焊死） ============
    private static MethodHandle pipelineAddConstraint; // PhysicsPipeline.addConstraint(body,body,config)
    private static java.lang.reflect.Constructor<?> fixedConstraintCfgCtor;


    // ============ Sable.HELPER.getContaining ============
    private static Method sableHelperGetContaining;     // Sable.HELPER.getContaining(Level, BlockPos)

    // ============ SubLevelRemovalReason.REMOVED ============
    private static Object removalReasonRemoved;

    private SubLevelScanner() {}

    // =========================================================================
    // 初始化（DCL 单例；核心 API 缺失直接 throw，否则 available=false 静默降级）
    // =========================================================================
    private static void init() {
        if (initialized) return;
        synchronized (SubLevelScanner.class) {
            if (initialized) return;
            initialized = true;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                // 1) 先判断 Sable 核心类是否存在（不存在直接 available=false，不抛错）
                Class<?> containerCls;
                Class<?> serverContainerCls;
                Class<?> subLevelCls;
                Class<?> removalReasonCls;
                Class<?> plotCls;
                Class<?> physicsSystemCls;
                Class<?> pipelineCls;
                Class<?> poseCls;
                Class<?> bbox3icCls, bbox3dcCls;
                Class<?> sableCls;
                try {
                    containerCls       = Class.forName(CLASS_SUBLEVEL_CONTAINER, true, cl);
                    serverContainerCls = Class.forName(CLASS_SERVER_CONTAINER, true, cl);
                    subLevelCls        = Class.forName(CLASS_SUBLEVEL, true, cl);
                    removalReasonCls   = Class.forName(CLASS_REMOVAL_REASON, true, cl);
                    plotCls            = Class.forName(CLASS_LEVEL_PLOT, true, cl);
                    physicsSystemCls   = Class.forName(CLASS_PHYSICS_SYSTEM, true, cl);
                    pipelineCls        = Class.forName(CLASS_PHYSICS_PIPELINE, true, cl);
                    poseCls            = Class.forName(CLASS_POSE_3D, true, cl);
                    bbox3icCls         = Class.forName(CLASS_BOUNDING_BOX_3IC, true, cl);
                    bbox3dcCls         = Class.forName(CLASS_BOUNDING_BOX_3DC, true, cl);
                    sableCls           = Class.forName(CLASS_SABLE, true, cl);
                } catch (ClassNotFoundException noSable) {
                    LOGGER.warn("[CargoDispatch] Sable 未安装，SubLevel 相关功能全部静默降级。缺失类：{}", noSable.getMessage());
                    available = false;
                    return;
                }

                MethodHandles.Lookup lookup = MethodHandles.lookup();

                // --- SubLevelContainer.getContainer(ServerLevel) ---
                // 关键：必须绑定服务端入口。Sable 存在 getContainer(ClientLevel)/getContainer(ServerLevel) 重载，
                // 旧谓词「Level.class.isAssignableFrom」会同时命中两者，findMethod 取 declaredMethods 第一个，
                // 一旦顺序把客户端版排前，服务端传 ServerLevel 即 ClassCastException，连接器检测/货箱删除全挂。
                Method getContainerMethod = findMethod(containerCls, METHOD_GET_CONTAINER,
                        m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == ServerLevel.class);
                if (getContainerMethod == null) {
                    // 兜底：可安全接收 ServerLevel 的通用入口（排除 ClientLevel 专用）
                    getContainerMethod = findMethod(containerCls, METHOD_GET_CONTAINER,
                            m -> acceptsServerLevel(m.getParameterTypes()));
                }
                if (getContainerMethod == null) throw new NoSuchMethodException(METHOD_GET_CONTAINER);
                getContainerMethod.setAccessible(true);
                getContainerHandle = lookup.unreflect(getContainerMethod);

                // --- SubLevelContainer.getAllSubLevels() ---
                getAllSubLevelsHandle = lookup.unreflect(require(containerCls.getMethod(METHOD_GET_ALL_SUBLEVELS)));

                // ★ P1：SubLevelContainer.removeSubLevel(int x, int z, SubLevelRemovalReason)
                Method removeMethod = findMethod(containerCls, METHOD_REMOVE_SUBLEVEL, m -> {
                    Class<?>[] p = m.getParameterTypes();
                    return p.length == 3 && p[0] == int.class && p[1] == int.class
                            && removalReasonCls.isAssignableFrom(p[2]);
                });
                if (removeMethod == null) throw new NoSuchMethodException(METHOD_REMOVE_SUBLEVEL + "(int,int,SubLevelRemovalReason)");
                removeMethod.setAccessible(true);
                removeSubLevelHandle = lookup.unreflect(removeMethod);

                // physicsSystem() 属于可选 API（仅传送/质量修正用）：
                // 不同 Sable 版本可能没有该方法，缺失时降级即可，绝不能拖垮扫描/删除等核心能力
                // physicsSystem() 只存在于 ServerSubLevelContainer（子类），抽象父类无此方法
                try {
                    physicsSystemHandle =
                            lookup.unreflect(require(serverContainerCls.getMethod(METHOD_PHYSICS_SYSTEM)));
                } catch (NoSuchMethodException e) {
                    LOGGER.warn("[CargoDispatch] ServerSubLevelContainer.physicsSystem() 不可用，传送降级：{}",
                            e.getMessage());
                }
                // 兜底：SubLevelPhysicsSystem 静态 get(Level)，实例方法不可用时仍可取到活动物理系统
                try {
                    physicsSystemStaticGet = lookup.unreflect(require(
                            physicsSystemCls.getMethod(METHOD_GET_CONTAINER, Level.class)));
                } catch (NoSuchMethodException ignored) {
                }
                containerGetLogPlotSize = lookup.unreflect(require(containerCls.getMethod(METHOD_GET_LOG_PLOT_SIZE)));
                containerGetOrigin = lookup.unreflect(require(containerCls.getMethod(METHOD_GET_ORIGIN)));

                // --- SubLevel 公共 API ---
                getLevelHandle       = lookup.unreflect(require(subLevelCls.getMethod(METHOD_GET_LEVEL)));
                getUniqueIdHandle    = lookup.unreflect(require(subLevelCls.getMethod(METHOD_GET_UNIQUE_ID)));
                boundingBoxHandle    = lookup.unreflect(require(subLevelCls.getMethod(METHOD_BOUNDING_BOX)));
                getPlotHandle        = lookup.unreflect(require(subLevelCls.getMethod(METHOD_GET_PLOT)));
                logicalPoseHandle    = lookup.unreflect(require(subLevelCls.getMethod(METHOD_LOGICAL_POSE)));
                isRemovedHandle      = lookup.unreflect(require(subLevelCls.getMethod(METHOD_IS_REMOVED)));
                updateLastPoseHandle = lookup.unreflect(require(subLevelCls.getMethod(METHOD_UPDATE_LAST_POSE)));

                // --- MassTracker API ---
                try {
                    Method getMt = require(subLevelCls.getMethod(METHOD_GET_MASS_TRACKER));
                    getMassTrackerHandle = lookup.unreflect(getMt);
                    Class<?> massTrackerCls = getMt.getReturnType();
                    Method getM = require(massTrackerCls.getMethod(METHOD_GET_MASS));
                    getMassHandle = lookup.unreflect(getM);
                    LOGGER.debug("[CargoDispatch] MassTracker API 加载成功");
                } catch (NoSuchMethodException e) {
                    LOGGER.warn("[CargoDispatch] MassTracker API 不可用（质量读取功能受限）：{}", e.getMessage());
                }

                // --- LevelPlot ---
                plotGetCenterChunk = lookup.unreflect(require(plotCls.getMethod(METHOD_GET_CENTER_CHUNK)));
                Method plotBb = null;
                try {
                    plotBb = plotCls.getMethod(METHOD_GET_BOUNDING_BOX);
                } catch (NoSuchMethodException ok) {
                    plotBb = plotCls.getMethod(METHOD_BOUNDING_BOX);
                }
                plotGetBoundingBox = lookup.unreflect(require(plotBb));
                plotBboxInterfaceCls = bbox3icCls;
                // 主动重建句柄：存档恢复/异常路径后 localBounds 可能缺失，调用方据此兜底
                plotUpdateBounds = lookup.unreflect(require(plotCls.getMethod("updateBoundingBox")));
                // plot 在 grid 的 chunk 范围（public）：据此换算 SubLevel plot 的 grid 方块边界
                plotGetChunkMin = lookup.unreflect(require(plotCls.getMethod("getChunkMin")));
                plotGetChunkMax = lookup.unreflect(require(plotCls.getMethod("getChunkMax")));

                // Plot bbox 是 int 方块坐标（BoundingBox3ic），单独绑定，避免误用 3dc 句柄
                plotBboxMinX = lookup.unreflect(bbox3icCls.getMethod("minX"));
                plotBboxMinY = lookup.unreflect(bbox3icCls.getMethod("minY"));
                plotBboxMinZ = lookup.unreflect(bbox3icCls.getMethod("minZ"));
                plotBboxMaxX = lookup.unreflect(bbox3icCls.getMethod("maxX"));
                plotBboxMaxY = lookup.unreflect(bbox3icCls.getMethod("maxY"));
                plotBboxMaxZ = lookup.unreflect(bbox3icCls.getMethod("maxZ"));

                // --- BoundingBox getter：优先 3dc（double），失败再 3ic（int） ---
                boolean usedIntFallback = false;
                try {
                    bboxMinX = findGetter(lookup, bbox3dcCls, "minX", "getMinX");
                    bboxMinY = findGetter(lookup, bbox3dcCls, "minY", "getMinY");
                    bboxMinZ = findGetter(lookup, bbox3dcCls, "minZ", "getMinZ");
                    bboxMaxX = findGetter(lookup, bbox3dcCls, "maxX", "getMaxX");
                    bboxMaxY = findGetter(lookup, bbox3dcCls, "maxY", "getMaxY");
                    bboxMaxZ = findGetter(lookup, bbox3dcCls, "maxZ", "getMaxZ");
                    bboxReturnsDouble = true;
                } catch (Throwable t) {
                    bboxMinX = findGetter(lookup, bbox3icCls, "minX", "getMinX");
                    bboxMinY = findGetter(lookup, bbox3icCls, "minY", "getMinY");
                    bboxMinZ = findGetter(lookup, bbox3icCls, "minZ", "getMinZ");
                    bboxMaxX = findGetter(lookup, bbox3icCls, "maxX", "getMaxX");
                    bboxMaxY = findGetter(lookup, bbox3icCls, "maxY", "getMaxY");
                    bboxMaxZ = findGetter(lookup, bbox3icCls, "maxZ", "getMaxZ");
                    bboxReturnsDouble = false;
                    usedIntFallback = true;
                }
                if (bboxMinX == null || bboxMinY == null || bboxMinZ == null
                        || bboxMaxX == null || bboxMaxY == null || bboxMaxZ == null) {
                    throw new IllegalStateException("BoundingBox 访问器加载失败（double=" + !usedIntFallback + "，int=" + usedIntFallback + "）");
                }

                // --- Pose3d 几何变换（P4 内→全局坐标变换用） ---
                posePositionHandle     = lookup.unreflect(require(poseCls.getMethod(METHOD_POSE_POSITION)));
                poseOrientationHandle  = lookup.unreflect(require(poseCls.getMethod(METHOD_POSE_ORIENTATION)));
                // transformPosition(Vector3d)：必须精确匹配参数类型——
                // 同名 1 参重载还有 transformPosition(Vec3)，误绑会导致 invoke 类型不匹配
                Method transPos = findMethod(poseCls, METHOD_POSE_TRANSFORM_POSITION, m ->
                        m.getParameterCount() == 1
                                && "org.joml.Vector3d".equals(m.getParameterTypes()[0].getName()));
                if (transPos == null) throw new NoSuchMethodException(METHOD_POSE_TRANSFORM_POSITION + "(Vector3d)");
                poseTransformPosHandle = lookup.unreflect(require(transPos));
                // transformPositionInverse(Vector3d)（可选 API，缺失时仅逆变换降级）
                Method transPosInv = findMethod(poseCls, "transformPositionInverse", m ->
                        m.getParameterCount() == 1
                                && "org.joml.Vector3d".equals(m.getParameterTypes()[0].getName()));
                poseTransformPosInverseHandle =
                        transPosInv != null ? lookup.unreflect(require(transPosInv)) : null;

                Class<?> vector3dCls = Class.forName("org.joml.Vector3d");
                vector3dSetXyz = require(vector3dCls.getMethod("set", double.class, double.class, double.class));
                vector3dGetX   = require(vector3dCls.getMethod("x"));
                vector3dGetY   = require(vector3dCls.getMethod("y"));
                vector3dGetZ   = require(vector3dCls.getMethod("z"));

                // 四元数旋转（transformPosition 若未完成旋转，则再用 orientation 转一次）
                Class<?> quatCls = Class.forName("org.joml.Quaterniond");
                Method qTrans = findMethod(quatCls, "transform", m -> {
                    Class<?>[] p = m.getParameterTypes();
                    return p.length >= 1 && vector3dCls.isAssignableFrom(p[0]);
                });
                if (qTrans != null) quaternionTransformPos = qTrans;

                // --- SubLevelPhysicsSystem（整块均为可选 API：仅传送/姿态同步/质量修正使用）---
                // 任一方法缺失只让对应特性降级（调用点均有 null 判断），不能影响扫描/收货/删除主链路
                try {
                    systemGetPipeline    = lookup.unreflect(require(physicsSystemCls.getMethod(METHOD_GET_PIPELINE)));
                    systemGetPhysicsHandle = lookup.unreflect(require(physicsSystemCls.getMethod(METHOD_GET_PHYSICS_HANDLE,
                            Class.forName(CLASS_SERVER_SUBLEVEL, true, cl))));

                    // PhysicsPipeline.teleport(subLevel, Vector3dc, Quaterniondc)
                    Method teleport = findMethod(pipelineCls, METHOD_TELEPORT, m -> m.getParameterCount() == 3);
                    if (teleport != null) pipelineTeleport = lookup.unreflect(require(teleport));

                    // SubLevelPhysicsSystem.updatePose(ServerSubLevel) — 名字精确匹配
                    Method upPose = findMethod(physicsSystemCls, METHOD_UPDATE_POSE, m -> m.getParameterCount() == 1);
                    if (upPose != null) systemUpdatePose = lookup.unreflect(require(upPose));

                    // RigidBodyHandle.setMass — 兜底（也可能叫别的名字，找不到就忽略，后续 CargoPhysicsHelper 会降级）
                    Class<?> rbCls = Class.forName(CLASS_RIGID_BODY_HANDLE, true, cl);
                    Method setMass = findMethod(rbCls, METHOD_SET_MASS, m -> m.getParameterCount() == 1
                            && (m.getParameterTypes()[0] == double.class || m.getParameterTypes()[0] == Double.class));
                    if (setMass != null) rigidBodySetMass = lookup.unreflect(require(setMass));

                    // FixedConstraintConfiguration(Vector3dc, Vector3dc, Quaterniondc)
                    Class<?> fixedCfgCls = Class.forName(CLASS_FIXED_CONSTRAINT_CFG, true, cl);
                    fixedConstraintCfgCtor = fixedCfgCls.getDeclaredConstructor(
                            org.joml.Vector3dc.class, org.joml.Vector3dc.class, org.joml.Quaterniondc.class);
                    fixedConstraintCfgCtor.setAccessible(true);
                    // PhysicsPipeline.addConstraint(body1, body2, config)（接口 default 方法）
                    Method addConstraint = findMethod(pipelineCls, METHOD_ADD_CONSTRAINT,
                            m -> m.getParameterCount() == 3);
                    if (addConstraint != null) {
                        pipelineAddConstraint = lookup.unreflect(require(addConstraint));
                    }
                } catch (Throwable ePhys) {
                    LOGGER.warn("[CargoDispatch] SubLevelPhysicsSystem 部分 API 不可用，传送/姿态/约束降级：{}", ePhys.getMessage());
                }

                // --- SubLevelRemovalReason.REMOVED ---
                Object rr = null;
                for (Object c : removalReasonCls.getEnumConstants()) {
                    String name = ((Enum<?>) c).name();
                    if (ENUM_REMOVAL_REMOVED.equals(name)) { rr = c; break; }
                }
                if (rr == null) {
                    for (Object c : removalReasonCls.getEnumConstants()) {
                        String name = ((Enum<?>) c).name();
                        if (ENUM_REMOVAL_DISMANTLE.equals(name)) { rr = c; break; }
                    }
                }
                if (rr == null && removalReasonCls.getEnumConstants().length > 0) rr = removalReasonCls.getEnumConstants()[0];
                removalReasonRemoved = rr;

                // --- Sable.HELPER.getContaining (P6) ---
                // Sable.HELPER 是一个 static final 实例字段（dev.ryanhcode.sable.Sable.HELPER），
                // 其类型实现了 getContaining(Level, BlockPos) 方法。
                try {
                    Field helperF = null;
                    for (Field f : sableCls.getDeclaredFields()) {
                        if ("HELPER".equals(f.getName())) { helperF = f; break; }
                    }
                    if (helperF != null) {
                        helperF.setAccessible(true);
                        Object helper = helperF.get(null);
                        if (helper != null) {
                            Method gc = findMethod(helper.getClass(), "getContaining",
                                    m -> m.getParameterCount() == 2
                                            && Level.class.isAssignableFrom(m.getParameterTypes()[0])
                                            && m.getParameterTypes()[1] == BlockPos.class);
                            if (gc != null) {
                                gc.setAccessible(true);
                                sableHelperGetContaining = gc;
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[CargoDispatch] Sable.HELPER.getContaining 未找到，将回退为 BoundingBox 粗判：{}", t.getMessage());
                }

                available = true;
                LOGGER.info("[CargoDispatch] Sable SubLevel 扫描 API 加载完成（bboxType={}，HELPER.getContaining={}，PhysicsPipeline.teleport={}）",
                        bboxReturnsDouble ? "BoundingBox3dc(double)" : "BoundingBox3ic(int)",
                        sableHelperGetContaining != null ? "OK" : "fallback(bbox)",
                        pipelineTeleport != null ? "OK" : "MISSING(!!)");
            } catch (Throwable t) {
                LOGGER.error("[CargoDispatch] Sable 反射初始化失败，SubLevel 功能不可用：{}: {}",
                        t.getClass().getSimpleName(), t.getMessage(), t);
                available = false;
            }
        }
    }

    private static Method require(Method m) { m.setAccessible(true); return m; }

    /**
     * 候选 getContainer 重载是否能安全接收 ServerLevel：
     *  参数类型须是 ServerLevel 的父型，且不是 ClientLevel 专用入口。
     *  ClientLevel 用 Class.forName 动态判断（不硬引用客户端类），物理服务端无该类时按「非客户端」处理。
     */
    private static boolean acceptsServerLevel(Class<?>[] params) {
        if (params.length != 1) return false;
        Class<?> param = params[0];
        if (!param.isAssignableFrom(ServerLevel.class)) return false;
        try {
            Class<?> clientLevel = Class.forName("net.minecraft.client.multiplayer.ClientLevel");
            return !clientLevel.isAssignableFrom(param);
        } catch (ClassNotFoundException e) {
            return true; // 物理服务端：不存在客户端类型，不会误选
        }
    }

    private static Method findMethod(Class<?> cls, String name, java.util.function.Predicate<Method> filter) {
        Method exact = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (!name.equals(m.getName())) continue;
            if (filter.test(m)) { exact = m; break; }
        }
        if (exact != null) return exact;
        // 公共/父类方法兜底
        for (Method m : cls.getMethods()) {
            if (!name.equals(m.getName())) continue;
            if (filter.test(m)) return m;
        }
        return null;
    }

    private static MethodHandle findGetter(MethodHandles.Lookup lookup, Class<?> cls, String field, String getter) throws NoSuchMethodException, IllegalAccessException {
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt != double.class && rt != float.class && rt != int.class && rt != long.class) continue;
            if (m.getName().equals(field) || m.getName().equals(getter)) {
                m.setAccessible(true);
                return lookup.unreflect(m);
            }
        }
        throw new NoSuchMethodException(cls.getName() + "#" + field + " / " + getter);
    }

    // =========================================================================
    // 基础访问器
    // =========================================================================
    public static boolean isAvailable() { init(); return available; }

    /** 获取 SubLevel 的 UUID */
    public static UUID getSubLevelUuid(Object subLevel) {
        init(); if (!available) return null;
        try { return (UUID) getUniqueIdHandle.invoke(subLevel); }
        catch (Throwable t) { return null; }
    }

    /** 获取 SubLevel 内部 Level（plot 内部虚拟世界） */
    public static Level getSubLevelInternalLevel(Object subLevel) {
        init(); if (!available || subLevel == null || getLevelHandle == null) return null;
        try { return (Level) getLevelHandle.invoke(subLevel); }
        catch (Throwable t) { LOGGER.warn("[CargoDispatch] getSubLevelInternalLevel 失败: {}", t.getMessage()); return null; }
    }

    /** SubLevel 世界包围盒（double，几何边界）六分量 {minX,minY,minZ,maxX,maxY,maxZ}；失败 null */
    public static double[] globalBBoxComponents(Object subLevel) {
        try {
            Object bb = boundingBoxHandle.invoke(subLevel);
            return new double[]{
                    num(bboxMinX.invoke(bb)), num(bboxMinY.invoke(bb)), num(bboxMinZ.invoke(bb)),
                    num(bboxMaxX.invoke(bb)), num(bboxMaxY.invoke(bb)), num(bboxMaxZ.invoke(bb))
            };
        } catch (Throwable t) { return null; }
    }

    /** 获取 SubLevel 的物理质量（Sable 原生计算）；失败返回 -1 */
    public static double getSubLevelMass(Object subLevel) {
        init();
        if (!available || subLevel == null || getMassTrackerHandle == null || getMassHandle == null) return -1;
        try {
            Object mt = getMassTrackerHandle.invoke(subLevel);
            if (mt == null) return -1;
            return (double) getMassHandle.invoke(mt);
        } catch (Throwable t) { LOGGER.warn("[CargoDispatch] getSubLevelMass 失败: {}", t.getMessage()); return -1; }
    }

    // =========================================================================
    // P6 修复：findSubLevelContainingGlobalPos 优先 Sable.HELPER.getContaining(Level,BlockPos)
    // =========================================================================
    /** helper 单例（Sable.HELPER），延迟获取 */
    @Nullable private static Object sableHelperInstance;

    @Nullable
    private static Object getHelperInstance() {
        if (sableHelperInstance == null && sableHelperGetContaining != null) {
            try {
                Field f = Class.forName(CLASS_SABLE).getDeclaredField("HELPER");
                f.setAccessible(true);
                sableHelperInstance = f.get(null);
            } catch (Throwable ignored) {}
        }
        return sableHelperInstance;
    }

    /**
     * 查找包含某全局世界坐标的 SubLevel。
     * <p>优先走 Sable 官方 {@code Sable.HELPER.getContaining(Level,BlockPos)}，
     * 失败才回退到 O(n) BoundingBox 粗判。对「连接器是否位于某个航空学飞行器 SubLevel 内部」这种高频查询，
     * Sable 官方实现有 plot-occupancy 索引，比全量遍历快得多。
     */
    @Nullable
    public static Object findSubLevelContainingGlobalPos(ServerLevel level, BlockPos worldPos) {
        init();
        if (!available) return null;
        Object helper = getHelperInstance();
        if (helper != null && sableHelperGetContaining != null) {
            try {
                Object r = sableHelperGetContaining.invoke(helper, level, worldPos);
                if (r != null) return r;
            } catch (Throwable t) {
                LOGGER.debug("[CargoDispatch] Sable.HELPER.getContaining 失败，fallback BBox 粗判：{}", t.getMessage());
            }
        }
        return findSubLevelContainingGlobalPosByBBox(level, worldPos);
    }

    /** 保留旧名，避免外部（如有）引用 Fast 版本。实现同上。 */
    @Nullable
    public static Object findSubLevelContainingGlobalPosFast(ServerLevel level, BlockPos worldPos) {
        return findSubLevelContainingGlobalPos(level, worldPos);
    }

    private static Object findSubLevelContainingGlobalPosByBBox(ServerLevel level, BlockPos wp) {
        try {
            Object container = getContainerHandle.invoke(level);
            List<?> all = (List<?>) getAllSubLevelsHandle.invoke(container);
            for (Object sl : all) {
                if (isGlobalPosInsideSubLevelBBox(sl, wp)) return sl;
            }
        } catch (Throwable t) { LOGGER.warn("[CargoDispatch] findSubLevel BBox scan 失败: {}", t.getMessage()); }
        return null;
    }

    private static boolean isGlobalPosInsideSubLevelBBox(Object subLevel, BlockPos wp) {
        try {
            Object bb = boundingBoxHandle.invoke(subLevel);
            if (bb == null) return false;
            double minX = num(bboxMinX.invoke(bb)), minY = num(bboxMinY.invoke(bb)), minZ = num(bboxMinZ.invoke(bb));
            double maxX = num(bboxMaxX.invoke(bb)), maxY = num(bboxMaxY.invoke(bb)), maxZ = num(bboxMaxZ.invoke(bb));
            if (minX == maxX && minY == maxY && minZ == maxZ) return false;
            double x = wp.getX(), y = wp.getY(), z = wp.getZ();
            return x >= minX - 1e-6 && x <= maxX + 1e-6
                    && y >= minY - 1e-6 && y <= maxY + 1e-6
                    && z >= minZ - 1e-6 && z <= maxZ + 1e-6;
        } catch (Throwable t) { return false; }
    }

    /** P3 修复：Number 收窄，兼容 int/double 返回类型 */
    private static double num(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }

    // =========================================================================
    // 范围扫描 / UUID 查询
    // =========================================================================

    /** 检测范围水平半径（统一取检测器配置） */
    public static int rangeXz() {
        return ModConfig.getDetectorRangeXZ();
    }

    /** 检测范围垂直半径（统一取检测器配置） */
    public static int rangeY() {
        return ModConfig.getDetectorRangeY();
    }

    public static List<Object> getNearbySubLevels(ServerLevel level, BlockPos center, int rangeXZ, int rangeY) {
        init(); if (!available) return List.of();
        List<Object> result = new ArrayList<>();
        try {
            Object container = getContainerHandle.invoke(level);
            List<?> all = (List<?>) getAllSubLevelsHandle.invoke(container);
            for (Object sl : all) {
                if (isSubLevelInRange(sl, center, rangeXZ, rangeY)) result.add(sl);
            }
        } catch (Throwable t) { LOGGER.warn("[CargoDispatch] getNearbySubLevels 失败: {}", t.getMessage()); }
        return result;
    }

    private static boolean isSubLevelInRange(Object subLevel, BlockPos center, int rangeXZ, int rangeY) {
        try {
            Object bb = boundingBoxHandle.invoke(subLevel);
            if (bb == null) return false;
            double minX = num(bboxMinX.invoke(bb)), minY = num(bboxMinY.invoke(bb)), minZ = num(bboxMinZ.invoke(bb));
            double maxX = num(bboxMaxX.invoke(bb)), maxY = num(bboxMaxY.invoke(bb)), maxZ = num(bboxMaxZ.invoke(bb));
            return maxX >= center.getX() - rangeXZ && minX <= center.getX() + rangeXZ
                    && maxY >= center.getY() - rangeY && minY <= center.getY() + rangeY
                    && maxZ >= center.getZ() - rangeXZ && minZ <= center.getZ() + rangeXZ;
        } catch (Throwable t) { return false; }
    }

    @Nullable
    public static Object findSubLevelByUuid(ServerLevel level, UUID uuid) {
        init(); if (!available || uuid == null) return null;
        try {
            Object container = getContainerHandle.invoke(level);
            List<?> all = (List<?>) getAllSubLevelsHandle.invoke(container);
            for (Object sl : all) {
                UUID u = (UUID) getUniqueIdHandle.invoke(sl);
                if (uuid.equals(u)) return sl;
            }
        } catch (Throwable t) { LOGGER.warn("[CargoDispatch] findSubLevelByUuid 失败: {}", t.getMessage()); }
        return null;
    }

    public static BlockEntity findCargoBlockEntity(Object subLevel, List<BlockPos> positions) {
        init(); if (!available) return null;
        try {
            Level inner = (Level) getLevelHandle.invoke(subLevel);
            for (BlockPos p : positions) {
                BlockEntity be = inner.getBlockEntity(p);
                if (be != null) return be;
            }
            if (positions.isEmpty()) {
                try {
                    Method m = inner.getClass().getMethod("getBlockEntityList");
                    Object list = m.invoke(inner);
                    if (list instanceof Iterable<?> it) {
                        for (Object o : it) if (o instanceof BlockEntity be) return be;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Throwable t) { LOGGER.warn("[CargoDispatch] findCargoBlockEntity 失败: {}", t.getMessage()); }
        return null;
    }

    // =========================================================================
    // P1 修复：removeSubLevel(int localPlotX, int localPlotZ, reason)
    // =========================================================================
    public static boolean removeSubLevel(ServerLevel level, Object subLevel) {
        init();
        if (!available || removalReasonRemoved == null) return false;
        if (removeSubLevelHandle == null) {
            LOGGER.error("[CargoDispatch] removeSubLevel API 未初始化，删除失败");
            return false;
        }
        // 有意删除白名单：让 SubLevelRemovalMixin 放行本次 markRemoved（所有主动删除路径统一收口于此）
        UUID removalUuid = getSubLevelUuid(subLevel);
        CargoManager.markSubLevelRemoval(removalUuid);
        try {
            Object container = getContainerHandle.invoke(level);
            // 1) 从 subLevel.plot.getCenterChunk() 拿到 plot 中心 chunk 坐标
            Object plot = getPlotHandle.invoke(subLevel);
            if (plot == null) { LOGGER.error("[CargoDispatch] SubLevel 无 plot，无法删除"); return false; }
            ChunkPos centerChunk = (ChunkPos) plotGetCenterChunk.invoke(plot);
            // 2) 查 container.logPlotSize 与 origin
            int logPlotSize = (int) containerGetLogPlotSize.invoke(container);
            Vector2i origin = (Vector2i) containerGetOrigin.invoke(container);
            int localX = (centerChunk.x >> logPlotSize) - origin.x;
            int localZ = (centerChunk.z >> logPlotSize) - origin.y;
            removeSubLevelHandle.invoke(container, localX, localZ, removalReasonRemoved);
            LOGGER.info("[CargoDispatch] SubLevel 已删除 plot(local={},{}) centerChunk={} uuid={}",
                    localX, localZ, centerChunk, getSubLevelUuid(subLevel));
            return true;
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 删除 SubLevel 失败：{}", t.getMessage(), t);
            return false;
        } finally {
            // 无论成功失败都清理白名单：失败时保留会让该货箱永久失去保护
            CargoManager.clearSubLevelRemoval(removalUuid);
        }
    }

    // =========================================================================
    // 几何变换辅助（P4：plot inner → 全局世界坐标）
    // =========================================================================
    /**
     * 把 plot 内部的 BlockPos（plotyard 绝对块坐标）通过 logicalPose 变换为全局世界 BlockPos。
     * 变换模型：v' = pose.orientation * (v - plotCenter) + pose.position。
     * 为了兼容"SubLevel 未发生物理运动（恒等 pose）+ plotCenter 就是 anchor"这类情况，
     * 我们直接使用 Sable 公开的 {@code Pose3d.transformPosition(Vector3dc)} —— 它已经包含平移+旋转。
     * （Sable 在 SubLevelAssemblyHelper 中就是通过 pose.transformPosition 转换坐标。）
     */
    @Nullable
    static BlockPos plotInnerToGlobalWorld(@Nullable Object pose, BlockPos plotInner) {
        if (pose == null || poseTransformPosHandle == null || vector3dSetXyz == null) return plotInner;
        try {
            // 创建 Vector3d(inner.x+0.5, inner.y+0.5, inner.z+0.5)，然后 transformPosition → 再 floor 为 BlockPos
            Object vec = Class.forName("org.joml.Vector3d").getDeclaredConstructor(double.class, double.class, double.class)
                    .newInstance(plotInner.getX() + 0.5, plotInner.getY() + 0.5, plotInner.getZ() + 0.5);
            Object vecOut = poseTransformPosHandle.invoke(pose, vec);
            if (vecOut == null) vecOut = vec;
            double x = (double) vector3dGetX.invoke(vecOut);
            double y = (double) vector3dGetY.invoke(vecOut);
            double z = (double) vector3dGetZ.invoke(vecOut);
            return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] plotInnerToGlobalWorld 失败（fallback 原样返回）：{}", t.getMessage());
            return plotInner;
        }
    }

    // =========================================================================
    // P2 修复：translateSubLevelBy 优先 PhysicsPipeline.teleport / 改 pose + updatePose
    // =========================================================================
    @Nullable
    public static UUID translateSubLevelBy(ServerLevel sl, Object subLevel, int dx, int dy, int dz) {
        init();
        if (sl == null || subLevel == null) return null;
        UUID oldUuid = getSubLevelUuid(subLevel);
        if (oldUuid == null) {
            LOGGER.error("[CargoDispatch][translateSubLevelBy] subLevel 无 UUID");
            return null;
        }
        if (dx == 0 && dy == 0 && dz == 0) return oldUuid;

        java.util.List<BlockPos> oldBlocks = CargoManager.getBlocksBySubLevel(oldUuid);
        BlockPos oldAnchor = CargoManager.getStartPosBySubLevel(oldUuid);
        CargoData oldCargoData = CargoManager.getCargoDataBySubLevel(oldUuid);
        java.util.List<net.minecraft.world.item.ItemStack> oldInvSnap = CargoManager.getInventorySnapshot(oldUuid);
        Double oldMass = getSubLevelMassCache(oldUuid);

        // ── 路径 ①：PhysicsPipeline.teleport（Sable 官方改位置方式） ──
        try {
            if (tryTeleport(sl, subLevel, dx, dy, dz)) {
                updateCargoManagerAfterTranslate(oldUuid, oldAnchor, oldBlocks, oldCargoData, oldInvSnap, oldMass, dx, dy, dz);
                LOGGER.info("[CargoDispatch][translateSubLevelBy] ✅ PhysicsPipeline.teleport 成功 uuid={} delta=({},{},{})",
                        oldUuid, dx, dy, dz);
                return oldUuid;
            }
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch][translateSubLevelBy] teleport 失败，降级 pose + updatePose：{}", t.getMessage());
        }

        // ── 路径 ②：直接改 logicalPose.position + SubLevelPhysicsSystem.updatePose ──
        try {
            if (tryPoseUpdate(sl, subLevel, dx, dy, dz)) {
                updateCargoManagerAfterTranslate(oldUuid, oldAnchor, oldBlocks, oldCargoData, oldInvSnap, oldMass, dx, dy, dz);
                LOGGER.info("[CargoDispatch][translateSubLevelBy] ✅ poseUpdate 成功 uuid={} delta=({},{},{})",
                        oldUuid, dx, dy, dz);
                return oldUuid;
            }
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch][translateSubLevelBy] poseUpdate 失败，降级拆卸重装配：{}", t.getMessage());
        }

        // ── 路径 ③：拆卸 → 搬 → 重新装配（UUID 变化，100% 兜底可用） ──
        return translateByDisassembleReassemble(sl, subLevel, dx, dy, dz,
                oldUuid, oldBlocks, oldAnchor, oldCargoData, oldInvSnap, oldMass);
    }

    /**
     * 传送 SubLevel 到精确世界位姿。
     *
     * @param quaternion 目标朝向；null 表示保持原朝向
     */
    public static boolean teleportToPose(ServerLevel sl, Object subLevel,
                                         double nx, double ny, double nz,
                                         @Nullable org.joml.Quaterniondc quaternion) {
        init();
        if (!available || pipelineTeleport == null) return false;
        try {
            Object container = getContainerHandle.invoke(sl);
            Object sys = physicsSystemHandle != null
                    ? physicsSystemHandle.invoke(container)
                    : (physicsSystemStaticGet != null ? physicsSystemStaticGet.invoke(sl) : null);
            if (sys == null) {
                LOGGER.warn("[CargoDispatch] teleportToPose 失败：物理系统不可用");
                return false;
            }
            Object pipeline = systemGetPipeline.invoke(sys);
            Object newPos = Class.forName("org.joml.Vector3d")
                    .getDeclaredConstructor(double.class, double.class, double.class)
                    .newInstance(nx, ny, nz);
            Object orientation = quaternion;
            if (orientation == null) {
                Object pose = logicalPoseHandle.invoke(subLevel);
                orientation = poseOrientationHandle.invoke(pose);
            }
            pipelineTeleport.invoke(pipeline, subLevel, newPos, orientation);
            if (updateLastPoseHandle != null) updateLastPoseHandle.invoke(subLevel);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] teleportToPose 失败: {}", t.getMessage());
            return false;
        }
    }

    /**
     * 在两个物理化刚体之间添加固定约束（锚点为各自局部坐标）。
     *
     * @return 约束句柄；失败返回 null
     */
    @Nullable
    public static Object addFixedConstraint(ServerLevel sl, Object bodyA, Object bodyB,
                                            org.joml.Vector3dc anchorA,
                                            org.joml.Vector3dc anchorB,
                                            org.joml.Quaterniondc orientation) {
        init();
        if (!available || pipelineAddConstraint == null || fixedConstraintCfgCtor == null) return null;
        try {
            Object container = getContainerHandle.invoke(sl);
            Object sys = physicsSystemHandle != null
                    ? physicsSystemHandle.invoke(container)
                    : (physicsSystemStaticGet != null ? physicsSystemStaticGet.invoke(sl) : null);
            if (sys == null) return null;
            Object pipeline = systemGetPipeline.invoke(sys);
            Object config = fixedConstraintCfgCtor.newInstance(anchorA, anchorB, orientation);
            return pipelineAddConstraint.invoke(pipeline, bodyA, bodyB, config);
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] addFixedConstraint 失败: {}", t.getMessage());
            return null;
        }
    }

    /** pose 点变换（局部→世界坐标），失败返回 null */
    @Nullable
    public static org.joml.Vector3d transformPosePoint(Object pose, double x, double y, double z) {
        init();
        if (!available || poseTransformPosHandle == null) return null;
        try {
            Object vec = Class.forName("org.joml.Vector3d")
                    .getDeclaredConstructor(double.class, double.class, double.class)
                    .newInstance(x, y, z);
            Object out = poseTransformPosHandle.invoke(pose, vec);
            if (!(out instanceof org.joml.Vector3d v)) {
                LOGGER.debug("[CargoDispatch] transformPosePoint 返回类型异常：{}",
                        out != null ? out.getClass().getName() : "null");
                return null;
            }
            return v;
        } catch (Throwable t) {
            LOGGER.debug("[CargoDispatch] transformPosePoint 调用失败：{}: {}",
                    t.getClass().getName(), t.getMessage());
            return null;
        }
    }

    /** SubLevel 内部点 → 世界坐标（经 logicalPose，即时准确，不依赖世界 bbox 更新时序） */
    @Nullable
    public static org.joml.Vector3d worldPoint(Object subLevel, double x, double y, double z) {
        Object pose = readSubLevelPose(subLevel);
        return pose != null ? transformPosePoint(pose, x, y, z) : null;
    }

    /** SubLevel 世界点 → 内部坐标（经 logicalPose 逆变换）；句柄缺失/失败返回 null */
    @Nullable
    public static org.joml.Vector3d localPoint(Object subLevel, double x, double y, double z) {
        init();
        if (!available || poseTransformPosInverseHandle == null) return null;
        try {
            Object pose = getLogicalPose(subLevel);
            if (pose == null) return null;
            Object vec = Class.forName("org.joml.Vector3d")
                    .getDeclaredConstructor(double.class, double.class, double.class)
                    .newInstance(x, y, z);
            Object out = poseTransformPosInverseHandle.invoke(pose, vec);
            return out instanceof org.joml.Vector3d v ? v : null;
        } catch (Throwable t) { return null; }
    }

    /** 销毁约束句柄（PhysicsConstraintHandle.remove） */
    public static boolean removeConstraintHandle(Object handle) {
        if (handle == null) return true;
        try {
            handle.getClass().getMethod("remove").invoke(handle);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] removeConstraintHandle 失败: {}", t.getMessage());
            return false;
        }
    }

    private static boolean tryTeleport(ServerLevel sl, Object subLevel, int dx, int dy, int dz) throws Throwable {
        if (pipelineTeleport == null || logicalPoseHandle == null) return false;
        Object container = getContainerHandle.invoke(sl);
        Object sys = physicsSystemHandle.invoke(container);
        Object pipeline = systemGetPipeline.invoke(sys);
        Object pose = logicalPoseHandle.invoke(subLevel);
        if (pose == null) return false;
        Object posVec = posePositionHandle.invoke(pose);
        if (posVec == null || vector3dSetXyz == null) return false;
        // 计算新位置：pos + (dx, dy, dz)
        double px = (double) vector3dGetX.invoke(posVec) + dx;
        double py = (double) vector3dGetY.invoke(posVec) + dy;
        double pz = (double) vector3dGetZ.invoke(posVec) + dz;
        // 构建新 Vector3d（作为 Vector3dc 实参）
        Object newPos = Class.forName("org.joml.Vector3d").getDeclaredConstructor(double.class, double.class, double.class)
                .newInstance(px, py, pz);
        Object orientation = poseOrientationHandle.invoke(pose);
        if (Boolean.TRUE.equals(isRemovedHandle.invoke(subLevel))) {
            LOGGER.warn("[CargoDispatch] teleport 前检测到 SubLevel 已 removed，跳过");
            return false;
        }
        pipelineTeleport.invoke(pipeline, subLevel, newPos, orientation);
        if (updateLastPoseHandle != null) updateLastPoseHandle.invoke(subLevel);
        return true;
    }

    private static boolean tryPoseUpdate(ServerLevel sl, Object subLevel, int dx, int dy, int dz) throws Throwable {
        if (logicalPoseHandle == null || posePositionHandle == null || systemUpdatePose == null) return false;
        Object pose = logicalPoseHandle.invoke(subLevel);
        Object posVec = posePositionHandle.invoke(pose);
        double px = (double) vector3dGetX.invoke(posVec) + dx;
        double py = (double) vector3dGetY.invoke(posVec) + dy;
        double pz = (double) vector3dGetZ.invoke(posVec) + dz;
        vector3dSetXyz.invoke(posVec, px, py, pz);
        Object container = getContainerHandle.invoke(sl);
        Object sys = physicsSystemHandle.invoke(container);
        systemUpdatePose.invoke(sys, subLevel);
        if (updateLastPoseHandle != null) updateLastPoseHandle.invoke(subLevel);
        return true;
    }

    private static UUID translateByDisassembleReassemble(ServerLevel sl, Object subLevel, int dx, int dy, int dz,
                                                         UUID oldUuid, java.util.List<BlockPos> oldBlocks, BlockPos oldAnchor,
                                                         CargoData oldCargoData, java.util.List<net.minecraft.world.item.ItemStack> oldInvSnap,
                                                         Double oldMass) {
        LOGGER.warn("[CargoDispatch][translateSubLevelBy] ⚠️  物理平移 API 不可用，兜底拆卸-重装配 uuid={} delta=({},{},{})",
                oldUuid, dx, dy, dz);
        try {
            CargoPhysicsHelper.DisassemblyResult dis = CargoPhysicsHelper.disassembleSubLevelToWorld(sl, subLevel);
            if (dis == null || dis.globalPoses() == null || dis.globalPoses().isEmpty()) {
                LOGGER.error("[CargoDispatch][translateSubLevelBy] 拆卸为空，平移终止 uuid={}", oldUuid);
                return null;
            }
            // P13 防 OOM 上限
            if (dis.globalPoses().size() > DISASSEMBLE_MAX_BLOCKS) {
                LOGGER.error("[CargoDispatch][translateSubLevelBy] 方块数超过上限 n={} > {}, 平移终止（防止 OOM）",
                        dis.globalPoses().size(), DISASSEMBLE_MAX_BLOCKS);
                return null;
            }
            java.util.List<BlockPos> newPoses = new ArrayList<>(dis.globalPoses().size());
            for (BlockPos gp : dis.globalPoses()) newPoses.add(gp.offset(dx, dy, dz));
            BlockPos oldAnch = dis.anchor() != null ? dis.anchor() : dis.globalPoses().get(0);
            BlockPos newAnch = oldAnch.offset(dx, dy, dz);

            java.util.List<BlockState> disSt = dis.states();
            java.util.List<CompoundTag> disTags = dis.beTags();
            final int UPDATE = net.minecraft.world.level.block.Block.UPDATE_ALL;
            for (int i = 0; i < dis.globalPoses().size(); i++) {
                BlockPos oldGp = dis.globalPoses().get(i);
                BlockPos newGp = newPoses.get(i);
                BlockState st = (disSt != null && i < disSt.size()) ? disSt.get(i) : sl.getBlockState(oldGp);
                CompoundTag tag = (disTags != null && i < disTags.size()) ? disTags.get(i) : null;
                if (tag == null) {
                    BlockEntity be = sl.getBlockEntity(oldGp);
                    if (be != null) tag = be.saveWithFullMetadata(sl.registryAccess());
                }
                sl.removeBlockEntity(newGp);
                sl.setBlock(newGp, st, UPDATE);
                if (tag != null) {
                    BlockEntity be = sl.getBlockEntity(newGp);
                    if (be != null) { try { be.loadWithComponents(tag, sl.registryAccess()); be.setChanged(); } catch (Throwable ignored) {} }
                }
                sl.setBlock(oldGp, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), UPDATE);
            }
            String name = String.format(Locale.ROOT, "Translated#%s_d%d_%d_%d",
                    oldUuid.toString().substring(0, 6), dx, dy, dz);
            Object newSub = CargoPhysicsHelper.assembleBlocks(sl, newAnch, newPoses, name);
            UUID newUuid = getSubLevelUuid(newSub);
            if (newUuid == null) {
                LOGGER.error("[CargoDispatch][translateSubLevelBy] 重装配 SubLevel UUID 为 null");
                return null;
            }
            net.minecraft.world.SimpleContainer inv = rebuildInvFromSnapDirect(oldInvSnap);
            CargoManager.registerSubLevel(newUuid, newAnch, newPoses, inv, oldCargoData);
            if (oldMass != null && oldMass > 0) CargoManager.cacheSubLevelMass(newUuid, oldMass);
            LOGGER.info("[CargoDispatch][translateSubLevelBy] ✅ 兜底拆卸-重装配成功：{} → {} anchor({}→{}) blocks={}",
                    oldUuid, newUuid, oldAnch, newAnch, newPoses.size());
            return newUuid;
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch][translateSubLevelBy] 兜底拆卸-重装配失败 uuid=" + oldUuid, t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Double getSubLevelMassCache(UUID uuid) {
        try {
            Field f = CargoManager.class.getDeclaredField("SUBLEVEL_MASS_CACHE");
            f.setAccessible(true);
            ConcurrentHashMap<UUID, Double> m = (ConcurrentHashMap<UUID, Double>) f.get(null);
            return m.get(uuid);
        } catch (Throwable ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static void updateCargoManagerAfterTranslate(UUID uuid, BlockPos oldAnchor, java.util.List<BlockPos> oldBlocks,
                                                         CargoData cargoData, java.util.List<net.minecraft.world.item.ItemStack> invSnap,
                                                         Double mass, int dx, int dy, int dz) {
        try {
            Field startF = CargoManager.class.getDeclaredField("SUBLEVEL_START_MAP"); startF.setAccessible(true);
            ConcurrentHashMap<UUID, BlockPos> START_MAP = (ConcurrentHashMap<UUID, BlockPos>) startF.get(null);
            Field blocksF = CargoManager.class.getDeclaredField("SUBLEVEL_BLOCKS_MAP"); blocksF.setAccessible(true);
            ConcurrentHashMap<UUID, java.util.List<BlockPos>> BLOCKS_MAP = (ConcurrentHashMap<UUID, java.util.List<BlockPos>>) blocksF.get(null);
            Field b2mF = CargoManager.class.getDeclaredField("BLOCK_TO_MAIN_MAP"); b2mF.setAccessible(true);
            ConcurrentHashMap<BlockPos, BlockPos> B2M_MAP = (ConcurrentHashMap<BlockPos, BlockPos>) b2mF.get(null);
            Field s2sF = CargoManager.class.getDeclaredField("START_TO_SUBLEVEL_MAP"); s2sF.setAccessible(true);
            ConcurrentHashMap<BlockPos, UUID> S2S_MAP = (ConcurrentHashMap<BlockPos, UUID>) s2sF.get(null);

            BlockPos newAnchor = (oldAnchor != null) ? oldAnchor.offset(dx, dy, dz) : null;
            if (oldAnchor != null) {
                START_MAP.put(uuid, newAnchor.immutable());
                S2S_MAP.remove(oldAnchor.immutable());
                S2S_MAP.put(newAnchor.immutable(), uuid);
                try {
                    Field cargoMapF = CargoManager.class.getDeclaredField("CARGO_MAP"); cargoMapF.setAccessible(true);
                    ConcurrentHashMap<BlockPos, CargoData> CM = (ConcurrentHashMap<BlockPos, CargoData>) cargoMapF.get(null);
                    CargoData cd = CM.remove(oldAnchor.immutable());
                    if (cd != null) CM.put(newAnchor.immutable(), cd);
                    Field siF = CargoManager.class.getDeclaredField("SHARED_INVENTORIES"); siF.setAccessible(true);
                    ConcurrentHashMap<BlockPos, net.minecraft.world.SimpleContainer> SI =
                            (ConcurrentHashMap<BlockPos, net.minecraft.world.SimpleContainer>) siF.get(null);
                    net.minecraft.world.SimpleContainer sc = SI.remove(oldAnchor.immutable());
                    if (sc != null) SI.put(newAnchor.immutable(), sc);
                } catch (Throwable ignored) {}
            }

            if (oldBlocks != null && !oldBlocks.isEmpty() && newAnchor != null) {
                java.util.List<BlockPos> newBlocks = new ArrayList<>(oldBlocks.size());
                for (BlockPos op : oldBlocks) {
                    BlockPos np = op.offset(dx, dy, dz);
                    newBlocks.add(np.immutable());
                    BlockPos mainPrev = B2M_MAP.remove(op.immutable());
                    if (mainPrev != null) {
                        BlockPos newMain = mainPrev.offset(dx, dy, dz);
                        B2M_MAP.put(np.immutable(), newMain.immutable());
                    }
                }
                BLOCKS_MAP.put(uuid, newBlocks);
            }

            try {
                Method md = CargoManager.class.getDeclaredMethod("markDirty"); md.setAccessible(true); md.invoke(null);
            } catch (Throwable ignored) {}
        } catch (Throwable t) { LOGGER.error("[CargoDispatch] updateCargoManagerAfterTranslate 失败 uuid=" + uuid, t); }
    }

    private static net.minecraft.world.SimpleContainer rebuildInvFromSnapDirect(@Nullable java.util.List<net.minecraft.world.item.ItemStack> snap) {
        int size = 54;
        if (snap != null && snap.size() > size) size = Math.max(108, snap.size());
        net.minecraft.world.SimpleContainer out = new net.minecraft.world.SimpleContainer(size);
        if (snap != null) {
            int slot = 0;
            for (net.minecraft.world.item.ItemStack s : snap) {
                if (s != null && !s.isEmpty() && slot < size) out.setItem(slot++, s.copy());
            }
        }
        return out;
    }

    // 为外部（例如 CargoPhysicsHelper.disassembleSubLevelToWorld、连接器 onLoad 重登记 actor）暴露 plot 访问器
    public static Object getPlot(Object subLevel) throws Throwable { return getPlotHandle.invoke(subLevel); }
    static Object getPlotBBox(Object plot) throws Throwable { return plotGetBoundingBox.invoke(plot); }
    static Object getLogicalPose(Object subLevel) throws Throwable { return logicalPoseHandle.invoke(subLevel); }

    /**
     * 主动重建 plot 的 localBounds（按当前已加载 chunk 重新聚合）。
     * 原理：Sable 仅在 addChunkHolder 时更新 bbox，存档恢复等路径后 localBounds 可能缺失/失效，
     *       连接器检测、坐标桥接读不到有效范围，此处提供幂等的强制刷新入口。
     */
    static void refreshPlotBounds(Object plot) {
        try {
            plotUpdateBounds.invoke(plot);
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] plot.updateBoundingBox 调用失败：{}", t.toString());
        }
    }

    /**
     * SubLevel 自身 plot 在 grid 中的水平方块边界：{minBX, minBZ, maxBX(含), maxBZ(含)}；失败 null。
     * 原理：plot.getChunkMin/Max 给出该 SubLevel 占据的 grid chunk 范围，换算为方块坐标。
     * 用途：连接器模式放置货箱时校验不越界到相邻 plot（别的 SubLevel），防止写错位置。
     */
    @Nullable
    public static int[] readSubLevelGridXZBounds(Object subLevel) {
        try {
            Object plot = getPlotHandle.invoke(subLevel);
            net.minecraft.world.level.ChunkPos cmin =
                    (net.minecraft.world.level.ChunkPos) plotGetChunkMin.invoke(plot);
            net.minecraft.world.level.ChunkPos cmax =
                    (net.minecraft.world.level.ChunkPos) plotGetChunkMax.invoke(plot);
            return new int[]{
                    cmin.x << 4, cmin.z << 4,
                    ((cmax.x + 1) << 4) - 1, ((cmax.z + 1) << 4) - 1
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读取 SubLevel 自身 plot bbox（内部方块坐标）int[6]。
     * 包私有三步访问（getPlot → getPlotBBox → plotBBoxMinMax）的对外只读封装，
     * 供 blockentity 包的对齐逻辑使用，避免逐个暴露内部句柄。
     *
     * @return {minX,minY,minZ,maxX,maxY,maxZ}；bbox 无效/异常时 null
     */
    @Nullable
    public static int[] readPlotBBoxComponents(Object subLevel) {
        try {
            Object plot = getPlotHandle.invoke(subLevel);
            int[] m = plotBBoxMinMax(plotGetBoundingBox.invoke(plot));
            return m[3] < 0 ? null : m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取 SubLevel 位姿位置（世界坐标），失败返回全 NaN */
    public static double[] readSubLevelPosition(Object subLevel) {
        try {
            Object pose = getLogicalPose(subLevel);
            Object posVec = posePositionHandle.invoke(pose);
            return new double[]{
                    (double) vector3dGetX.invoke(posVec),
                    (double) vector3dGetY.invoke(posVec),
                    (double) vector3dGetZ.invoke(posVec)
            };
        } catch (Throwable t) { return new double[]{Double.NaN, Double.NaN, Double.NaN}; }
    }

    /** 读取 SubLevel 位姿朝向（Quaterniondc），失败返回 null */
    @Nullable
    public static Object readSubLevelOrientation(Object subLevel) {
        try {
            Object pose = getLogicalPose(subLevel);
            return poseOrientationHandle.invoke(pose);
        } catch (Throwable t) {
            LOGGER.debug("[CargoDispatch] readSubLevelOrientation 失败：{}: {}",
                    t.getClass().getName(), t.getMessage(), t);
            return null;
        }
    }

    /** 读取 SubLevel pose 对象（配合 {@link #transformPosePoint}），失败 null */
    @Nullable
    public static Object readSubLevelPose(Object subLevel) {
        try {
            return getLogicalPose(subLevel);
        } catch (Throwable t) {
            LOGGER.debug("[CargoDispatch] readSubLevelPose 失败：{}: {}",
                    t.getClass().getName(), t.getMessage());
            return null;
        }
    }

    /** 解析 plot BoundingBox（int）为 int[6] {minX,minY,minZ,maxX,maxY,maxZ} */
    static int[] plotBBoxMinMax(Object bbox) {
        try {
            return new int[]{
                    (int) plotBboxMinX.invoke(bbox),
                    (int) plotBboxMinY.invoke(bbox),
                    (int) plotBboxMinZ.invoke(bbox),
                    (int) plotBboxMaxX.invoke(bbox),
                    (int) plotBboxMaxY.invoke(bbox),
                    (int) plotBboxMaxZ.invoke(bbox)
            };
        } catch (Throwable t) {
            // 不能吞异常：类型不匹配/类加载器分裂等根因必须可见，否则上层只会收到哨兵误判为"无连接器"
            Class<?> actual = bbox != null ? bbox.getClass() : null;
            LOGGER.warn("[CargoDispatch] plot bbox 反射失败 bbox={} 实际类型={} 实际CL={} 接口CL={} isInstance={} 错误={}: {}",
                    bbox,
                    actual != null ? actual.getName() : "null",
                    actual != null ? actual.getClassLoader() : "null",
                    plotBboxInterfaceCls.getClassLoader(),
                    actual != null && plotBboxInterfaceCls.isInstance(bbox),
                    t.getClass().getName(), t.getMessage());
            return new int[]{0,0,0,-1,-1,-1};
        }
    }

    /** 把 RigidBody 质量同步到底层物理后端（P5 修复时 CargoPhysicsHelper 会调用） */
    static boolean syncRigidBodyMass(ServerLevel sl, Object subLevel, double mass) {
        init();
        if (!available || rigidBodySetMass == null || systemGetPhysicsHandle == null || physicsSystemHandle == null
                || getContainerHandle == null) return false;
        try {
            Object container = getContainerHandle.invoke(sl);
            Object sys = physicsSystemHandle.invoke(container);
            Object handle = systemGetPhysicsHandle.invoke(sys, subLevel);
            if (handle == null) return false;
            rigidBodySetMass.invoke(handle, mass);
            return true;
        } catch (Throwable t) {
            LOGGER.debug("[CargoDispatch] syncRigidBodyMass 失败（物理后端 setMass 接口未生效，本次忽略）：{}", t.getMessage());
            return false;
        }
    }
}
