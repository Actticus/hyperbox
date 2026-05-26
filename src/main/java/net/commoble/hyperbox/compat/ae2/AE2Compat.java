package net.commoble.hyperbox.compat.ae2;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import appeng.api.AECapabilities;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;

import net.commoble.hyperbox.Hyperbox;
import net.commoble.hyperbox.blocks.ApertureBlockEntity;
import net.commoble.hyperbox.blocks.HyperboxBlockEntity;
import net.commoble.hyperbox.dimension.HyperboxSaveData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Applied Energistics 2 compatibility.
 *
 * Each HyperboxBlockEntity gets one IManagedGridNode in the parent dimension.
 * Each ApertureBlockEntity gets one IManagedGridNode in the hyperspace dimension.
 * When both sides are loaded, GridHelper.createConnection() bridges them so
 * the ME network can span the dimensional boundary transparently.
 *
 * This class is only loaded (and these callbacks only set) when AE2 is present.
 * All AE2 API references are confined to this package.
 */
public class AE2Compat
{
	// (dimensionKey → (blockPos → managed node)) for O(1) lookup in both directions
	private static final Map<ResourceKey<Level>, Map<BlockPos, IManagedGridNode>> hyperboxNodes = new HashMap<>();
	private static final Map<ResourceKey<Level>, Map<BlockPos, IManagedGridNode>> apertureNodes = new HashMap<>();

	/** No-op IGridNodeListener — saves nothing, reacts to nothing. */
	private static final IGridNodeListener<Object> NOP_LISTENER = (owner, node) -> {};

	public static void init(IEventBus modBus)
	{
		modBus.addListener(EventPriority.NORMAL, AE2Compat::onRegisterCapabilities);

		HyperboxBlockEntity.ae2OnLoad = AE2Compat::onHyperboxLoad;
		HyperboxBlockEntity.ae2OnUnload = AE2Compat::onHyperboxUnload;
		HyperboxBlockEntity.ae2OnLevelKeySet = AE2Compat::onHyperboxLevelKeySet;
		ApertureBlockEntity.ae2OnLoad = AE2Compat::onApertureLoad;
		ApertureBlockEntity.ae2OnUnload = AE2Compat::onApertureUnload;
	}

	// --- Capability registration ---

	private static void onRegisterCapabilities(RegisterCapabilitiesEvent event)
	{
		event.registerBlockEntity(
			AECapabilities.IN_WORLD_GRID_NODE_HOST,
			Hyperbox.INSTANCE.hyperboxBlockEntityType.get(),
			(be, side) -> getHyperboxHost(be));

		event.registerBlockEntity(
			AECapabilities.IN_WORLD_GRID_NODE_HOST,
			Hyperbox.INSTANCE.apertureBlockEntityType.get(),
			(be, side) -> getApertureHost(be));
	}

	@Nullable
	private static IInWorldGridNodeHost getHyperboxHost(HyperboxBlockEntity be)
	{
		if (!(be.getLevel() instanceof ServerLevel level)) return null;
		IManagedGridNode node = getNode(hyperboxNodes, level.dimension(), be.getBlockPos());
		if (node == null) return null;
		IGridNode gridNode = node.getNode();
		if (gridNode == null) return null;
		return dir -> gridNode;
	}

	@Nullable
	private static IInWorldGridNodeHost getApertureHost(ApertureBlockEntity be)
	{
		if (!(be.getLevel() instanceof ServerLevel level)) return null;
		IManagedGridNode node = getNode(apertureNodes, level.dimension(), be.getBlockPos());
		if (node == null) return null;
		IGridNode gridNode = node.getNode();
		if (gridNode == null) return null;
		return dir -> gridNode;
	}

	// --- Lifecycle callbacks ---

	private static void onHyperboxLoad(HyperboxBlockEntity be)
	{
		if (!(be.getLevel() instanceof ServerLevel level)) return;

		ResourceKey<Level> levelKey = level.dimension();
		BlockPos pos = be.getBlockPos();
		IManagedGridNode node = createNode(be, level, pos);
		hyperboxNodes.computeIfAbsent(levelKey, k -> new HashMap<>()).put(pos, node);
		level.invalidateCapabilities(pos);

		tryConnectHyperboxToApertures(be, node);
	}

	private static void onHyperboxUnload(HyperboxBlockEntity be)
	{
		if (!(be.getLevel() instanceof ServerLevel level)) return;
		BlockPos pos = be.getBlockPos();
		destroyNode(hyperboxNodes, level.dimension(), pos);
		level.invalidateCapabilities(pos);
	}

	private static void onHyperboxLevelKeySet(HyperboxBlockEntity be)
	{
		if (!(be.getLevel() instanceof ServerLevel level)) return;
		IManagedGridNode hyperboxNode = getNode(hyperboxNodes, level.dimension(), be.getBlockPos());
		if (hyperboxNode == null) return;
		tryConnectHyperboxToApertures(be, hyperboxNode);
	}

	private static void onApertureLoad(ApertureBlockEntity be)
	{
		if (!(be.getLevel() instanceof ServerLevel level)) return;

		ResourceKey<Level> levelKey = level.dimension();
		BlockPos pos = be.getBlockPos();
		IManagedGridNode node = createNode(be, level, pos);
		apertureNodes.computeIfAbsent(levelKey, k -> new HashMap<>()).put(pos, node);
		level.invalidateCapabilities(pos);

		HyperboxSaveData data = HyperboxSaveData.getOrCreate(level);
		IManagedGridNode hyperboxNode = getNode(hyperboxNodes, data.getParentWorld(), data.getParentPos());
		if (hyperboxNode != null)
		{
			tryConnect(hyperboxNode.getNode(), node.getNode());
		}
	}

	private static void onApertureUnload(ApertureBlockEntity be)
	{
		if (!(be.getLevel() instanceof ServerLevel level)) return;
		BlockPos pos = be.getBlockPos();
		destroyNode(apertureNodes, level.dimension(), pos);
		level.invalidateCapabilities(pos);
	}

	// --- Helpers ---

	private static void tryConnectHyperboxToApertures(HyperboxBlockEntity be, IManagedGridNode hyperboxNode)
	{
		IGridNode hyperboxGridNode = hyperboxNode.getNode();
		if (hyperboxGridNode == null) return;

		be.getLevelKey().ifPresent(hyperspaceLevelKey ->
		{
			Map<BlockPos, IManagedGridNode> apertures = apertureNodes.get(hyperspaceLevelKey);
			if (apertures == null) return;
			for (IManagedGridNode apertureNode : apertures.values())
			{
				tryConnect(hyperboxGridNode, apertureNode.getNode());
			}
		});
	}

	private static void tryConnect(@Nullable IGridNode a, @Nullable IGridNode b)
	{
		if (a == null || b == null) return;
		try
		{
			GridHelper.createConnection(a, b);
		}
		catch (Exception ignored) {}
	}

	@SuppressWarnings("unchecked")
	private static IManagedGridNode createNode(Object owner, ServerLevel level, BlockPos pos)
	{
		IManagedGridNode node = GridHelper.createManagedNode(owner, (IGridNodeListener<Object>) NOP_LISTENER)
			.setIdlePowerUsage(0.0)
			.setInWorldNode(true);
		node.create(level, pos);
		return node;
	}

	@Nullable
	private static IManagedGridNode getNode(
		Map<ResourceKey<Level>, Map<BlockPos, IManagedGridNode>> map,
		ResourceKey<Level> levelKey,
		BlockPos pos)
	{
		Map<BlockPos, IManagedGridNode> levelMap = map.get(levelKey);
		return levelMap != null ? levelMap.get(pos) : null;
	}

	private static void destroyNode(
		Map<ResourceKey<Level>, Map<BlockPos, IManagedGridNode>> map,
		ResourceKey<Level> levelKey,
		BlockPos pos)
	{
		Map<BlockPos, IManagedGridNode> levelMap = map.get(levelKey);
		if (levelMap == null) return;
		IManagedGridNode node = levelMap.remove(pos);
		if (node != null) node.destroy();
		if (levelMap.isEmpty()) map.remove(levelKey);
	}
}
