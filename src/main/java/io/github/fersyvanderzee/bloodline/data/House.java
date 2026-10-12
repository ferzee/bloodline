package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A house: one or more players, each playing their own branch of the family ({@link Lineage}).
 * Everything here is shared by all members: the name, the oath, renown, legendary traits, the
 * history, and the quests the whole house works on together.
 */
public class House {
	public static final Codec<House> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(h -> h.id),
		Codec.STRING.fieldOf("name").forGetter(h -> h.name),
		UUIDUtil.STRING_CODEC.fieldOf("head").forGetter(h -> h.head),
		UUIDUtil.STRING_CODEC.listOf().fieldOf("members").forGetter(h -> h.members),
		ChronicleEntry.CODEC.listOf().optionalFieldOf("chronicle", List.of()).forGetter(h -> h.chronicle),
		Codec.STRING.listOf().optionalFieldOf("milestones", List.of()).forGetter(h -> List.copyOf(h.milestones)),
		Quest.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(h -> h.quests),
		Codec.STRING.optionalFieldOf("oath").forGetter(h -> h.oath),
		Codec.INT.optionalFieldOf("oath_acts", 0).forGetter(h -> h.oathActs),
		Codec.BOOL.optionalFieldOf("act_pending", false).forGetter(h -> h.actPending),
		Codec.STRING.listOf().optionalFieldOf("legacies", List.of()).forGetter(h -> h.legacies),
		ItemStack.CODEC.listOf().optionalFieldOf("lost_heirlooms", List.of()).forGetter(h -> h.lostHeirlooms),
		Codec.INT.optionalFieldOf("renown", 0).forGetter(h -> h.renown),
		Codec.STRING.listOf().optionalFieldOf("preparations", List.of()).forGetter(h -> h.preparations),
		Extras.MAP_CODEC.forGetter(House::extras)
	).apply(i, (id, name, head, members, chronicle, milestones, quests, oath, oathActs, actPending, legacies, lostHeirlooms, renown,
				 preparations, extras) -> new House(id, name, head, members, chronicle, milestones, quests, oath, oathActs, actPending, legacies,
		lostHeirlooms, renown, preparations, extras.sacrifices(), extras.steward(), extras.fulfilledOaths(), extras.nextEventDay(), extras.pendingEvent())));

	/**
	 * Fields saved alongside the others, grouped only because a codec can hold at most 16 fields.
	 * They are stored at the same level as the rest, so older saves read the same.
	 */
	private record Extras(int sacrifices, Optional<Steward> steward, List<String> fulfilledOaths, int nextEventDay,
						  Optional<PendingEvent> pendingEvent) {
		static final MapCodec<Extras> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.INT.optionalFieldOf("sacrifices", 0).forGetter(Extras::sacrifices),
			Steward.CODEC.optionalFieldOf("steward").forGetter(Extras::steward),
			Codec.STRING.listOf().optionalFieldOf("fulfilled_oaths", List.of()).forGetter(Extras::fulfilledOaths),
			Codec.INT.optionalFieldOf("next_event_day", 0).forGetter(Extras::nextEventDay),
			PendingEvent.CODEC.optionalFieldOf("pending_event").forGetter(Extras::pendingEvent)
		).apply(i, Extras::new));
	}

	private Extras extras() {
		return new Extras(sacrifices, steward, List.copyOf(fulfilledOaths), nextEventDay, pendingEvent);
	}

	public final UUID id;
	public String name;
	/** The member who leads the house: only they can invite, remove, rename and swear the oath. */
	public UUID head;
	public final List<UUID> members;
	public final List<ChronicleEntry> chronicle;
	/** House-wide firsts, such as the first to enter the Nether. */
	public final Set<String> milestones;
	/** Quests the whole house works on: oath steps, preparations, blood debts and stolen heirlooms. */
	public final List<Quest> quests;
	/** The oath the house swore, if any (see Questlines). */
	public Optional<String> oath;
	/** How many acts of the oath are done. */
	public int oathActs;
	/** True after an act is finished, until any member's heir takes over (one act per generation). */
	public boolean actPending;
	/** Legendary traits every member of the house is born with. */
	public final List<String> legacies;
	/** Heirlooms that were destroyed or lost; the Stolen Heirloom questline brings them back. */
	public final List<ItemStack> lostHeirlooms;
	/** Earned by fulfilling duties; each act of the oath needs a certain amount to begin. */
	public int renown;
	/** Preparations finished for the coming champion fight. */
	public final List<String> preparations;
	/** How many members have given their lives for the house (see Sacrifice). */
	public int sacrifices;
	/** The steward the house hired at an inn, if any. */
	public Optional<Steward> steward;
	/** Oaths the house has fulfilled; once one is, the house may swear another (see Questlines). */
	public final List<String> fulfilledOaths;
	/** The day the next family event may happen (0 = not scheduled yet), and one waiting for a choice. */
	public int nextEventDay;
	public Optional<PendingEvent> pendingEvent;

	public House(UUID id, String name, UUID head, List<UUID> members, List<ChronicleEntry> chronicle, List<String> milestones,
				 List<Quest> quests, Optional<String> oath, int oathActs, boolean actPending, List<String> legacies,
				 List<ItemStack> lostHeirlooms, int renown, List<String> preparations, int sacrifices, Optional<Steward> steward,
				 List<String> fulfilledOaths, int nextEventDay, Optional<PendingEvent> pendingEvent) {
		this.id = id;
		this.name = name;
		this.head = head;
		this.members = new ArrayList<>(members);
		this.chronicle = new ArrayList<>(chronicle);
		this.milestones = new HashSet<>(milestones);
		this.quests = new ArrayList<>(quests);
		this.oath = oath;
		this.oathActs = oathActs;
		this.actPending = actPending;
		this.legacies = new ArrayList<>(legacies);
		this.lostHeirlooms = new ArrayList<>(lostHeirlooms);
		this.renown = renown;
		this.preparations = new ArrayList<>(preparations);
		this.sacrifices = sacrifices;
		this.steward = steward;
		this.fulfilledOaths = new ArrayList<>(fulfilledOaths);
		this.nextEventDay = nextEventDay;
		this.pendingEvent = pendingEvent;
	}

	public static House found(String name, UUID head) {
		return new House(UUID.randomUUID(), name, head, List.of(head), List.of(), List.of(), List.of(), Optional.empty(), 0, false,
			List.of(), List.of(), 0, List.of(), 0, Optional.empty(), List.of(), 0, Optional.empty());
	}

	/** Whether a quest of this type belongs to the whole house rather than one member. */
	public static boolean isShared(Quest.Type type) {
		return type == Quest.Type.BLOOD_DEBT || type == Quest.Type.QUESTLINE || type == Quest.Type.PREPARATION || type == Quest.Type.EVENT;
	}
}
