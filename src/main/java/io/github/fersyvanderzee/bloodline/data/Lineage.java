package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
		Quest.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(l -> l.quests),
		Codec.STRING.optionalFieldOf("oath").forGetter(l -> l.oath),
		Codec.INT.optionalFieldOf("oath_acts", 0).forGetter(l -> l.oathActs),
		Codec.INT.optionalFieldOf("oath_act_generation", 0).forGetter(l -> l.oathActGeneration),
		Codec.STRING.listOf().optionalFieldOf("legacies", List.of()).forGetter(l -> l.legacies),
		ItemStack.CODEC.listOf().optionalFieldOf("lost_heirlooms", List.of()).forGetter(l -> l.lostHeirlooms),
		Codec.INT.optionalFieldOf("renown", 0).forGetter(l -> l.renown),
		Codec.STRING.listOf().optionalFieldOf("preparations", List.of()).forGetter(l -> l.preparations),
		CompletedQuest.CODEC.listOf().optionalFieldOf("completed", List.of()).forGetter(l -> l.completed)
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
	/** The oath the founder swore, if any (see Questlines). */
	public Optional<String> oath;
	/** How many acts of the oath are done, and in which generation the last one was finished. */
	public int oathActs;
	public int oathActGeneration;
	/** Legendary traits every member of the family is born with. */
	public final List<String> legacies;
	/** Heirlooms that were destroyed or lost; the Stolen Heirloom questline brings them back. */
	public final List<ItemStack> lostHeirlooms;
	/** Earned by fulfilling duties; each act of the oath needs a certain amount to begin. */
	public int renown;
	/** Preparations finished for the coming champion fight (see Questlines). */
	public final List<String> preparations;
	/** Quests the current head of the family has finished, shown crossed out. */
	public final List<CompletedQuest> completed;

	public Lineage(String house, Member current, boolean awaitingHeir, List<Ancestor> ancestors, List<ChronicleEntry> chronicle,
				   List<String> milestones, List<Quest> quests, Optional<String> oath, int oathActs, int oathActGeneration,
				   List<String> legacies, List<ItemStack> lostHeirlooms, int renown, List<String> preparations,
				   List<CompletedQuest> completed) {
		this.house = house;
		this.current = current;
		this.awaitingHeir = awaitingHeir;
		this.ancestors = new ArrayList<>(ancestors);
		this.chronicle = new ArrayList<>(chronicle);
		this.milestones = new HashSet<>(milestones);
		this.quests = new ArrayList<>(quests);
		this.oath = oath;
		this.oathActs = oathActs;
		this.oathActGeneration = oathActGeneration;
		this.legacies = new ArrayList<>(legacies);
		this.lostHeirlooms = new ArrayList<>(lostHeirlooms);
		this.renown = renown;
		this.preparations = new ArrayList<>(preparations);
		this.completed = new ArrayList<>(completed);
	}

	public Lineage(String house, Member founder) {
		this(house, founder, false, List.of(), List.of(), List.of(), List.of(), Optional.empty(), 0, 0, List.of(), List.of(), 0, List.of(), List.of());
	}

	public String fullName() {
		return current.name() + " " + house;
	}

	/** The current member's own traits plus the family's legendary ones. */
	public List<String> allTraits() {
		List<String> all = new ArrayList<>(current.traits());
		all.addAll(legacies);
		return all;
	}

	/** What the current head of the family has finished. */
	public List<CompletedQuest> completedThisGeneration() {
		return completed.stream().filter(c -> c.generation() == current.generation()).toList();
	}

	public Ancestor parent() {
		return ancestors.isEmpty() ? null : ancestors.getLast();
	}
}
