package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player's branch of a house: the family member they are playing now, their own ancestors,
 * and the duties that are theirs alone. Shared things live in {@link #house}.
 */
public class Lineage {
	public static final Codec<Lineage> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.STRING_CODEC.fieldOf("house_id").forGetter(l -> l.house.id),
		Member.CODEC.fieldOf("current").forGetter(l -> l.current),
		Codec.BOOL.optionalFieldOf("awaiting_heir", false).forGetter(l -> l.awaitingHeir),
		Ancestor.CODEC.listOf().optionalFieldOf("ancestors", List.of()).forGetter(l -> l.ancestors),
		Quest.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(l -> l.ownQuests),
		CompletedQuest.CODEC.listOf().optionalFieldOf("completed", List.of()).forGetter(l -> l.completed),
		Codec.BOOL.optionalFieldOf("inherit_all", false).forGetter(l -> l.inheritAll),
		Member.CODEC.listOf().optionalFieldOf("heir_candidates", List.of()).forGetter(l -> l.candidates),
		Codec.BOOL.optionalFieldOf("fulfilled_act", false).forGetter(l -> l.fulfilledAct),
		Codec.BOOL.optionalFieldOf("blessed_match", false).forGetter(l -> l.blessedMatch),
		Codec.BOOL.optionalFieldOf("sick", false).forGetter(l -> l.sick)
	).apply(i, Lineage::new));

	/** The house this branch belongs to. Linked by {@link BloodlineState} after loading. */
	public House house;
	/** Only used while loading, before {@link #house} is linked. */
	final UUID houseId;
	public Member current;
	/** True between this branch's family member dying and their heir stepping in. */
	public boolean awaitingHeir;
	public final List<Ancestor> ancestors;
	/** Duties that belong to this branch only: resting places, unfinished business, inn jobs, founding goals. */
	public final List<Quest> ownQuests;
	/** Quests this branch's current family member has finished, shown crossed out. */
	public final List<CompletedQuest> completed;
	/** Set when this branch's family member gave their life: their heir inherits every boon. */
	public boolean inheritAll;
	/** The dead family member's children, one of whom the player chooses to carry on (see Heirs). */
	public final List<Member> candidates;
	/** Whether this branch's current family member finished an act of the oath; only they may perform the Sacrifice. */
	public boolean fulfilledAct;
	/** Set by a wedding event: this branch's next children are each born with an extra boon. */
	public boolean blessedMatch;
	/** Set by a sickness event until it is cured. */
	public boolean sick;

	Lineage(UUID houseId, Member current, boolean awaitingHeir, List<Ancestor> ancestors, List<Quest> ownQuests, List<CompletedQuest> completed,
			boolean inheritAll, List<Member> candidates, boolean fulfilledAct, boolean blessedMatch, boolean sick) {
		this.houseId = houseId;
		this.current = current;
		this.awaitingHeir = awaitingHeir;
		this.ancestors = new ArrayList<>(ancestors);
		this.ownQuests = new ArrayList<>(ownQuests);
		this.completed = new ArrayList<>(completed);
		this.inheritAll = inheritAll;
		this.candidates = new ArrayList<>(candidates);
		this.fulfilledAct = fulfilledAct;
		this.blessedMatch = blessedMatch;
		this.sick = sick;
	}

	public Lineage(House house, Member current) {
		this(house.id, current, false, List.of(), List.of(), List.of(), false, List.of(), false, false, false);
		this.house = house;
	}

	public String fullName() {
		return current.name() + " " + house.name;
	}

	/** The house's shared quests followed by this branch's own. A copy: change quests with {@link #addQuest} and {@link #removeQuest}. */
	public List<Quest> allQuests() {
		List<Quest> all = new ArrayList<>(house.quests);
		all.addAll(ownQuests);
		return all;
	}

	public void addQuest(Quest quest) {
		(House.isShared(quest.type) ? house.quests : ownQuests).add(quest);
	}

	public void removeQuest(Quest quest) {
		house.quests.remove(quest);
		ownQuests.remove(quest);
	}

	/** The current member's own traits plus the house's legendary ones. */
	public List<String> allTraits() {
		List<String> all = new ArrayList<>(current.traits());
		all.addAll(house.legacies);
		return all;
	}

	/** What this branch's current family member has finished. */
	public List<CompletedQuest> completedThisGeneration() {
		return completed.stream().filter(c -> c.generation() == current.generation()).toList();
	}

	public Ancestor parent() {
		return ancestors.isEmpty() ? null : ancestors.getLast();
	}

	public boolean isHead(UUID player) {
		return house.head.equals(player);
	}
}
