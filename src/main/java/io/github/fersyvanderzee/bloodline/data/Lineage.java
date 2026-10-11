package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One player's family: the living head, everyone who came before, and what they did. */
public class Lineage {
	public static final Codec<Lineage> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("house").forGetter(l -> l.house),
		Member.CODEC.fieldOf("current").forGetter(l -> l.current),
		Codec.BOOL.optionalFieldOf("awaiting_heir", false).forGetter(l -> l.awaitingHeir),
		Ancestor.CODEC.listOf().optionalFieldOf("ancestors", List.of()).forGetter(l -> l.ancestors),
		ChronicleEntry.CODEC.listOf().optionalFieldOf("chronicle", List.of()).forGetter(l -> l.chronicle),
		Codec.STRING.listOf().optionalFieldOf("milestones", List.of()).forGetter(l -> List.copyOf(l.milestones)),
		Quest.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(l -> l.quests)
	).apply(i, Lineage::new));

	public String house;
	public Member current;
	/** True between the head of the family dying and their heir stepping in. */
	public boolean awaitingHeir;
	public final List<Ancestor> ancestors;
	public final List<ChronicleEntry> chronicle;
	/** Family-wide firsts, such as the first to enter the Nether. */
	public final Set<String> milestones;
	/** Duties the current head of the family can fulfil. */
	public final List<Quest> quests;

	public Lineage(String house, Member current, boolean awaitingHeir, List<Ancestor> ancestors, List<ChronicleEntry> chronicle,
				   List<String> milestones, List<Quest> quests) {
		this.house = house;
		this.current = current;
		this.awaitingHeir = awaitingHeir;
		this.ancestors = new ArrayList<>(ancestors);
		this.chronicle = new ArrayList<>(chronicle);
		this.milestones = new HashSet<>(milestones);
		this.quests = new ArrayList<>(quests);
	}

	public Lineage(String house, Member founder) {
		this(house, founder, false, List.of(), List.of(), List.of(), List.of());
	}

	public String fullName() {
		return current.name() + " " + house;
	}

	public Ancestor parent() {
		return ancestors.isEmpty() ? null : ancestors.getLast();
	}
}
