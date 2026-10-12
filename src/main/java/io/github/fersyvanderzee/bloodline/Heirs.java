package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Member;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Choosing an heir. When a family member dies, three of their children are rolled, each with
 * their own name and inherited traits. On respawn the player picks one to carry on; until then
 * they are in mourning. If they don't choose within a couple of minutes, the family picks for them.
 */
public final class Heirs {
	/** Most children a configuration may ask for; the command accepts numbers up to this. */
	public static final int MAX_CHOICES = 5;
	private static final int ATTEMPTS = 12;

	/** When each mourning player's choice runs out. Not saved: after a relog the choice is offered again. */
	private static final Map<UUID, Long> DEADLINES = new HashMap<>();

	private Heirs() {
	}

	/**
	 * Rolls the dead family member's children. Their birth day and starting stats are set once chosen.
	 * A family member who died young (less than a day of play, "the Brief") passes on nothing: each
	 * child has only one trait of their own. This keeps deliberate quick deaths from breeding a
	 * perfect bloodline.
	 */
	public static void roll(ServerPlayer player, Lineage lineage) {
		Ancestor parent = lineage.parent();
		if (parent == null) return;
		List<String> inherited = diedYoung(parent) ? List.of() : parent.traits();
		RandomSource random = player.getRandom();
		List<Member> children = new ArrayList<>();
		Set<String> names = new HashSet<>();
		Set<Set<String>> traitSets = new HashSet<>();
		for (int i = 0; i < BloodlineConfig.get().families.heirChoices; i++) {
			List<String> traits = Traits.rollHeir(inherited, random, lineage.inheritAll);
			// Try for children who differ from their siblings, but don't insist forever.
			for (int attempt = 0; attempt < ATTEMPTS && traitSets.contains(Set.copyOf(traits)); attempt++) {
				traits = Traits.rollHeir(inherited, random, lineage.inheritAll);
			}
			traitSets.add(Set.copyOf(traits));
			String name = Names.given(random, parent.name());
			for (int attempt = 0; attempt < ATTEMPTS && names.contains(name); attempt++) name = Names.given(random, parent.name());
			names.add(name);
			children.add(new Member(name, parent.generation() + 1, traits, 0, Map.of()));
		}
		// A wedding blessed this branch: every child is born with one extra boon.
		if (lineage.blessedMatch) {
			lineage.blessedMatch = false;
			children.replaceAll(child -> Traits.rollExtraBoon(child.traits(), random)
				.map(extra -> {
					List<String> traits = new ArrayList<>(child.traits());
					traits.add(extra);
					return new Member(child.name(), child.generation(), traits, child.bornDay(), child.statsAtBirth());
				})
				.orElse(child));
		}
		lineage.candidates.clear();
		lineage.candidates.addAll(children);
	}

	/** Shows the player their dead family member's children to choose from. */
	public static void offer(ServerPlayer player, Lineage lineage) {
		Ancestor parent = lineage.parent();
		if (parent == null) return;
		if (lineage.candidates.isEmpty()) roll(player, lineage);
		DEADLINES.put(player.getUUID(), player.level().getGameTime() + 20L * BloodlineConfig.get().families.heirAutoChooseSeconds);

		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
		player.connection.send(new ClientboundSetTitleTextPacket(Text.t("heir.title").withStyle(ChatFormatting.GOLD)));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Text.t("heir.subtitle", parent.fullName())));
		player.sendSystemMessage(Text.t("heir.leaves", parent.fullName(), lineage.candidates.size()).withStyle(ChatFormatting.GOLD));
		for (int i = 0; i < lineage.candidates.size(); i++) {
			Member child = lineage.candidates.get(i);
			player.sendSystemMessage(Component.literal("  ")
				.append(button(i))
				.append(Component.literal(" " + child.name() + ": ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
				.append(Lineages.traitList(child.traits(), parent)));
		}
		if (lineage.inheritAll) {
			player.sendSystemMessage(Text.t("heir.sacrifice_note", parent.name())
				.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		} else if (diedYoung(parent)) {
			player.sendSystemMessage(Text.t("heir.brief_note", parent.fullName()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}
		player.sendSystemMessage(Text.t("heir.hint")
			.withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Died within a day of play. Sacrifices never count: they need a fulfilled act and earn "the Devoted". */
	public static boolean diedYoung(Ancestor ancestor) {
		return Epithets.BRIEF.equals(Epithets.id(ancestor.epithet()));
	}

	private static Component button(int index) {
		return Text.t("button.choose").withStyle(s -> s.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand("/bloodline heir " + (index + 1)))
			.withHoverEvent(new HoverEvent.ShowText(Text.t("heir.choose_hover"))));
	}

	/** The heir the player picked (1-based, as typed in the command). */
	public static void choose(ServerPlayer player, int number) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null || !lineage.awaitingHeir || lineage.candidates.isEmpty()) {
			player.sendSystemMessage(Text.t("heir.none").withStyle(ChatFormatting.RED));
			return;
		}
		if (!player.isAlive()) return;
		if (number < 1 || number > lineage.candidates.size()) {
			player.sendSystemMessage(Text.t("heir.number", lineage.candidates.size()).withStyle(ChatFormatting.RED));
			return;
		}
		DEADLINES.remove(player.getUUID());
		Lineages.succeed(player, lineage, lineage.candidates.get(number - 1));
	}

	/** Re-offers the choice after a relog, and chooses for players who leave it too long. */
	public static void tick(ServerPlayer player, Lineage lineage) {
		if (!lineage.awaitingHeir || !player.isAlive() || lineage.parent() == null) return;
		Long deadline = DEADLINES.get(player.getUUID());
		if (deadline == null) {
			offer(player, lineage);
		} else if (player.level().getGameTime() > deadline) {
			player.sendSystemMessage(Text.t("heir.auto").withStyle(ChatFormatting.GRAY));
			choose(player, 1);
		}
	}

	/** The Chronicle page for choosing an heir. */
	public static Component bookPage(Lineage lineage) {
		Ancestor parent = lineage.parent();
		MutableComponent page = Chronicle.heading(Text.t("book.heir.title"));
		if (parent != null) {
			page.append(Chronicle.faint(Text.t(diedYoung(parent) ? "book.heir.died_young" : "book.heir.died", parent.fullName()))).append("\n\n");
		}
		for (int i = 0; i < lineage.candidates.size(); i++) {
			Member child = lineage.candidates.get(i);
			page.append(Component.literal(child.name() + " ").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(true)))
				.append(Chronicle.button(Text.t("button.choose"), "/bloodline heir " + (i + 1), Text.t("heir.choose_hover_named", child.name())))
				.append("\n");
			for (String id : child.traits()) {
				Trait trait = Traits.get(id);
				if (trait == null) continue;
				ChatFormatting color = switch (trait.kind()) {
					case BOON -> ChatFormatting.DARK_GREEN;
					case FLAW -> ChatFormatting.DARK_RED;
					case QUIRK -> ChatFormatting.DARK_AQUA;
					case LEGENDARY -> ChatFormatting.GOLD;
				};
				page.append(Component.literal("  ").append(trait.displayName().withStyle(s -> s.withColor(color).withBold(false))).append("\n"));
			}
		}
		return page;
	}
}
