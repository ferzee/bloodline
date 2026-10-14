package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.fersyvanderzee.bloodline.Bloodline;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;

/** What has happened in each barrow crypt, keyed by the crypt's starting chunk. */
public class CryptState extends SavedData {
	/** One crypt: whether its sigil was hidden, its flames lit in the right order, its seal opened and its founder slain. */
	public static class Crypt {
		public static final Codec<Crypt> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.BOOL.optionalFieldOf("sigil_hidden", false).forGetter(c -> c.sigilHidden),
			Codec.BOOL.optionalFieldOf("flames_lit", false).forGetter(c -> c.flamesLit),
			Codec.BOOL.optionalFieldOf("opened", false).forGetter(c -> c.opened),
			Codec.BOOL.optionalFieldOf("cleared", false).forGetter(c -> c.cleared)
		).apply(i, Crypt::new));

		public boolean sigilHidden;
		public boolean flamesLit;
		public boolean opened;
		public boolean cleared;

		public Crypt() {
		}

		private Crypt(boolean sigilHidden, boolean flamesLit, boolean opened, boolean cleared) {
			this.sigilHidden = sigilHidden;
			this.flamesLit = flamesLit;
			this.opened = opened;
			this.cleared = cleared;
		}
	}

	private static final Codec<CryptState> CODEC = Codec.unboundedMap(Codec.STRING, Crypt.CODEC).fieldOf("crypts").codec()
		.xmap(CryptState::new, s -> s.crypts);

	public static final SavedDataType<CryptState> TYPE = new SavedDataType<>(Bloodline.id("crypts"), CryptState::new, CODEC, null);

	private final Map<String, Crypt> crypts;

	public CryptState() {
		this(Map.of());
	}

	private CryptState(Map<String, Crypt> crypts) {
		this.crypts = new HashMap<>(crypts);
	}

	public static CryptState get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public Crypt get(String key) {
		return crypts.computeIfAbsent(key, k -> new Crypt());
	}

	public void forget(String key) {
		crypts.remove(key);
		setDirty();
	}
}
