package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.fersyvanderzee.bloodline.Bloodline;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Every house in the world, and every player's branch of one. */
public class BloodlineState extends SavedData {
	private static final Codec<BloodlineState> CURRENT_CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, House.CODEC).fieldOf("houses").forGetter(s -> s.houses),
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, Lineage.CODEC).fieldOf("lineages").forGetter(s -> s.lineages)
	).apply(i, BloodlineState::new));

	/** Worlds saved before houses could have several members: one family per player. */
	private static final Codec<BloodlineState> LEGACY_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, LegacyLineage.CODEC)
		.fieldOf("lineages").codec()
		.xmap(BloodlineState::fromLegacy, s -> {
			throw new UnsupportedOperationException("Bloodline always saves in the current format");
		});

	private static final Codec<BloodlineState> CODEC = Codec.withAlternative(CURRENT_CODEC, LEGACY_CODEC);

	public static final SavedDataType<BloodlineState> TYPE = new SavedDataType<>(Bloodline.id("lineages"), BloodlineState::new, CODEC, null);

	private final Map<UUID, House> houses;
	private final Map<UUID, Lineage> lineages;

	public BloodlineState() {
		this(Map.of(), Map.of());
	}

	private BloodlineState(Map<UUID, House> houses, Map<UUID, Lineage> lineages) {
		this.houses = new HashMap<>(houses);
		this.lineages = new HashMap<>(lineages);
		for (Map.Entry<UUID, Lineage> entry : this.lineages.entrySet()) {
			Lineage lineage = entry.getValue();
			lineage.house = this.houses.get(lineage.houseId);
			if (lineage.house == null) {
				// A branch whose house went missing gets a house of its own rather than being lost.
				lineage.house = House.found("Lost", entry.getKey());
				this.houses.put(lineage.house.id, lineage.house);
			}
		}
	}

	private static BloodlineState fromLegacy(Map<UUID, LegacyLineage> legacy) {
		Map<UUID, House> houses = new HashMap<>();
		Map<UUID, Lineage> lineages = new HashMap<>();
		legacy.forEach((player, old) -> {
			boolean actPending = old.oathActs() > 0 && old.oathActGeneration() >= old.current().generation();
			House house = new House(UUID.randomUUID(), old.house(), player, List.of(player), old.chronicle(), old.milestones(),
				old.quests().stream().filter(q -> House.isShared(q.type)).toList(), old.oath(), old.oathActs(), actPending,
				old.legacies(), old.lostHeirlooms(), old.renown(), old.preparations(), 0, Optional.empty());
			houses.put(house.id, house);
			lineages.put(player, new Lineage(house.id, old.current(), old.awaitingHeir(), old.ancestors(),
				old.quests().stream().filter(q -> !House.isShared(q.type)).toList(), old.completed(), false, List.of()));
		});
		return new BloodlineState(houses, lineages);
	}

	public static BloodlineState get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public Lineage get(UUID player) {
		return lineages.get(player);
	}

	public void put(UUID player, Lineage lineage) {
		lineages.put(player, lineage);
		houses.put(lineage.house.id, lineage.house);
		setDirty();
	}

	public void addHouse(House house) {
		houses.put(house.id, house);
		setDirty();
	}

	public void removeHouse(House house) {
		houses.remove(house.id);
		setDirty();
	}

	public House house(UUID id) {
		return houses.get(id);
	}

	public Optional<House> houseNamed(String name) {
		return houses.values().stream().filter(h -> h.name.equalsIgnoreCase(name)).findFirst();
	}

	public Collection<House> houses() {
		return houses.values();
	}
}
