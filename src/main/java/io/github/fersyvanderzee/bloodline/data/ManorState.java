package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.fersyvanderzee.bloodline.Bloodline;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;

/** What has happened at each Fallen Manor, keyed by the manor's starting chunk. */
public class ManorState extends SavedData {
	/** One manor: whether its occupants came and were driven out, its will's pages hidden, and which house claimed it. */
	public static class Manor {
		public static final Codec<Manor> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.BOOL.optionalFieldOf("occupied", false).forGetter(m -> m.occupied),
			Codec.BOOL.optionalFieldOf("cleared", false).forGetter(m -> m.cleared),
			Codec.BOOL.optionalFieldOf("pages_hidden", false).forGetter(m -> m.pagesHidden),
			Codec.STRING.optionalFieldOf("claimed_by", "").forGetter(m -> m.claimedBy)
		).apply(i, Manor::new));

		public boolean occupied;
		public boolean cleared;
		public boolean pagesHidden;
		/** The claiming house's id, or empty. */
		public String claimedBy = "";

		public Manor() {
		}

		private Manor(boolean occupied, boolean cleared, boolean pagesHidden, String claimedBy) {
			this.occupied = occupied;
			this.cleared = cleared;
			this.pagesHidden = pagesHidden;
			this.claimedBy = claimedBy;
		}
	}

	private static final Codec<ManorState> CODEC = Codec.unboundedMap(Codec.STRING, Manor.CODEC).fieldOf("manors").codec()
		.xmap(ManorState::new, s -> s.manors);

	public static final SavedDataType<ManorState> TYPE = new SavedDataType<>(Bloodline.id("manors"), ManorState::new, CODEC, null);

	private final Map<String, Manor> manors;

	public ManorState() {
		this(Map.of());
	}

	private ManorState(Map<String, Manor> manors) {
		this.manors = new HashMap<>(manors);
	}

	public static ManorState get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public Manor get(String key) {
		return manors.computeIfAbsent(key, k -> new Manor());
	}

	public void forget(String key) {
		manors.remove(key);
		setDirty();
	}
}
