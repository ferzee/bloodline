package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import io.github.fersyvanderzee.bloodline.Bloodline;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Every family in the world, keyed by the player who plays them. */
public class BloodlineState extends SavedData {
	private static final Codec<BloodlineState> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Lineage.CODEC)
		.fieldOf("lineages").codec()
		.xmap(BloodlineState::new, s -> s.lineages);

	public static final SavedDataType<BloodlineState> TYPE = new SavedDataType<>(Bloodline.id("lineages"), BloodlineState::new, CODEC, null);

	private final Map<UUID, Lineage> lineages;

	public BloodlineState() {
		this(Map.of());
	}

	private BloodlineState(Map<UUID, Lineage> lineages) {
		this.lineages = new HashMap<>(lineages);
	}

	public static BloodlineState get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public Lineage get(UUID player) {
		return lineages.get(player);
	}

	public void put(UUID player, Lineage lineage) {
		lineages.put(player, lineage);
		setDirty();
	}
}
