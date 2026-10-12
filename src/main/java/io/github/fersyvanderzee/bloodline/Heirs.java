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
	public static final int COUNT = 3;
	private static final long AUTO_CHOOSE_TICKS = 20 * 120;
	private static final int ATTEMPTS = 12;

	/** When each mourning player's choice runs out. Not saved: after a relog the choice is offered again. */
	private static final Map<UUID, Long> DEADLINES = new HashMap<>();

	private Heirs() {
	}

	/** Rolls the dead family member's children. Their birth day and starting stats are set once chosen. */
	public static void roll(ServerPlayer player, Lineage lineage) {
		Ancestor parent = lineage.parent();
		if (parent == null) return;
		RandomSource random = player.getRandom();
		List<Member> children = new ArrayList<>();
		Set<String> names = new HashSet<>();
		Set<Set<String>> traitSets = new HashSet<>();
		for (int i = 0; i < COUNT; i++) {
			List<String> traits = Traits.rollHeir(parent.traits(), random, lineage.inheritAll);
			// Try for children who differ from their siblings, but don't insist forever.
			for (int attempt = 0; attempt < ATTEMPTS && traitSets.contains(Set.copyOf(traits)); attempt++) {
				traits = Traits.rollHeir(parent.traits(), random, lineage.inheritAll);
			}
			traitSets.add(Set.copyOf(traits));
			String name = Names.given(random, parent.name());
			for (int attempt = 0; attempt < ATTEMPTS && names.contains(name); attempt++) name = Names.given(random, parent.name());
			names.add(name);
			children.add(new Member(name, parent.generation() + 1, traits, 0, Map.of()));
		}
		lineage.candidates.clear();
		lineage.candidates.addAll(children);
	}

	/** Shows the player their dead family member's children to choose from. */
	public static void offer(ServerPlayer player, Lineage lineage) {
		Ancestor parent = lineage.parent();
		if (parent == null) return;
		if (lineage.candidates.isEmpty()) roll(player, lineage);
		DEADLINES.put(player.getUUID(), player.level().getGameTime() + AUTO_CHOOSE_TICKS);

		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Choose your heir").withStyle(ChatFormatting.GOLD)));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(parent.fullName() + " has died")));
		player.sendSystemMessage(Component.literal(parent.fullName() + " leaves " + lineage.candidates.size()
			+ " children. Who will carry on the line?").withStyle(ChatFormatting.GOLD));
		for (int i = 0; i < lineage.candidates.size(); i++) {
			Member child = lineage.candidates.get(i);
			player.sendSystemMessage(Component.literal("  ")
				.append(button(i))
				.append(Component.literal(" " + child.name() + ": ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
				.append(Lineages.traitList(child.traits(), parent)));
		}
		if (lineage.inheritAll) {
			player.sendSystemMessage(Component.literal("Because " + parent.name() + " gave their life, every child keeps all of their boons.")
				.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}
		player.sendSystemMessage(Component.literal("Hover over a trait to see what it does. The choice is also in your Chronicle.")
			.withStyle(ChatFormatting.DARK_GRAY));
	}

	private static Component button(int index) {
		return Component.literal("[Choose]").withStyle(s -> s.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand("/bloodline heir " + (index + 1)))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Carry on as this child"))));
	}

	/** The heir the player picked (1-based, as typed in the command). */
	public static void choose(ServerPlayer player, int number) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null || !lineage.awaitingHeir || lineage.candidates.isEmpty()) {
			player.sendSystemMessage(Component.literal("There is no heir to choose right now.").withStyle(ChatFormatting.RED));
			return;
		}
		if (!player.isAlive()) return;
		if (number < 1 || number > lineage.candidates.size()) {
			player.sendSystemMessage(Component.literal("Choose a number from 1 to " + lineage.candidates.size() + ".").withStyle(ChatFormatting.RED));
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
			player.sendSystemMessage(Component.literal("The family could wait no longer and chose for you.").withStyle(ChatFormatting.GRAY));
			choose(player, 1);
		}
	}

	/** The Chronicle page for choosing an heir. */
	public static Component bookPage(Lineage lineage) {
		Ancestor parent = lineage.parent();
		MutableComponent page = Chronicle.heading("Choose your Heir");
		if (parent != null) page.append(Chronicle.faint(parent.fullName() + " has died.\n\n"));
		for (int i = 0; i < lineage.candidates.size(); i++) {
			Member child = lineage.candidates.get(i);
			page.append(Component.literal(child.name() + " ").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(true)))
				.append(Chronicle.button("Choose", "/bloodline heir " + (i + 1), Component.literal("Carry on as " + child.name())))
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
