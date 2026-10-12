package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.House;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Member;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Joining and leaving houses. The head of a house invites players; an invited player either
 * brings their own family along as a new branch of the house, or starts over as a newcomer.
 * Players who leave (or are removed) found a house of their own and keep their character.
 */
public final class Houses {
	private static final long INVITE_TICKS = 20 * 60 * 5;

	private record Invite(UUID house, UUID from, long expires) {
	}

	/** Pending invitations by invited player. Not saved: an invitation lasts five minutes. */
	private static final Map<UUID, Invite> INVITES = new HashMap<>();

	private Houses() {
	}

	private static Component fail(String message) {
		return Component.literal(message).withStyle(ChatFormatting.RED);
	}

	public static void invite(ServerPlayer head, ServerPlayer target) {
		Lineage lineage = Lineages.get(head);
		Lineage theirs = Lineages.get(target);
		if (lineage == null || theirs == null) return;
		if (!lineage.isHead(head.getUUID())) {
			head.sendSystemMessage(fail("Only the head of House " + lineage.house.name + " can invite new members."));
			return;
		}
		if (theirs.house == lineage.house) {
			head.sendSystemMessage(fail(theirs.fullName() + " is already part of House " + lineage.house.name + "."));
			return;
		}
		if (lineage.house.members.size() >= BloodlineConfig.get().houses.maxMembers) {
			head.sendSystemMessage(fail("House " + lineage.house.name + " can't have more than " + BloodlineConfig.get().houses.maxMembers + " members."));
			return;
		}
		INVITES.put(target.getUUID(), new Invite(lineage.house.id, head.getUUID(), head.level().getGameTime() + INVITE_TICKS));
		head.sendSystemMessage(Component.literal("You invited " + theirs.fullName() + " to join House " + lineage.house.name + ".")
			.withStyle(ChatFormatting.GRAY));

		String oldHouse = theirs.house.name;
		boolean last = theirs.house.members.size() == 1;
		String leftBehind = last
			? "House " + oldHouse + " ends: its oath, renown and legacy are left behind."
			: "You leave House " + oldHouse + "; its other members carry on.";
		MutableComponent message = Component.literal(lineage.fullName() + " invites you to join House " + lineage.house.name + ". ")
			.withStyle(ChatFormatting.GOLD)
			.append(button("Join with your family", "/bloodline accept keep", ChatFormatting.GREEN,
				"Keep " + theirs.current.name() + " and your ancestors. They become a branch of House " + lineage.house.name + ".\n" + leftBehind))
			.append(" ")
			.append(button("Join as a newcomer", "/bloodline accept fresh", ChatFormatting.AQUA,
				"Start a new character in House " + lineage.house.name + ". Your old family's story ends here.\n" + leftBehind))
			.append(" ")
			.append(button("Decline", "/bloodline decline", ChatFormatting.GRAY, "Stay in House " + oldHouse + "."));
		target.sendSystemMessage(message);
	}

	private static Component button(String label, String command, ChatFormatting color, String hover) {
		return Component.literal("[" + label + "]").withStyle(s -> s.withColor(color)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	public static void decline(ServerPlayer player) {
		Invite invite = INVITES.remove(player.getUUID());
		if (invite == null) return;
		player.sendSystemMessage(Component.literal("You declined the invitation.").withStyle(ChatFormatting.GRAY));
		ServerPlayer from = player.level().getServer().getPlayerList().getPlayer(invite.from());
		Lineage theirs = Lineages.get(player);
		if (from != null && theirs != null) {
			from.sendSystemMessage(Component.literal(theirs.fullName() + " declined your invitation.").withStyle(ChatFormatting.GRAY));
		}
	}

	/** Accepts a pending invitation. With {@code keep}, the player's character and ancestors come along. */
	public static void accept(ServerPlayer player, boolean keep) {
		MinecraftServer server = player.level().getServer();
		BloodlineState state = BloodlineState.get(server);
		Invite invite = INVITES.remove(player.getUUID());
		House target = invite == null ? null : state.house(invite.house());
		if (target == null || player.level().getGameTime() > invite.expires()) {
			player.sendSystemMessage(fail("You have no open invitation to a house."));
			return;
		}
		Lineage lineage = Lineages.get(player);
		if (lineage == null || lineage.awaitingHeir || !player.isAlive()) {
			player.sendSystemMessage(fail("You can't join a house right now."));
			return;
		}
		String oldName = lineage.house.name;
		detach(server, lineage.house, player.getUUID(), lineage.current.name());

		if (!keep) {
			Member newcomer = new Member(Names.given(player.getRandom(), lineage.current.name()), 1, Traits.rollFounder(player.getRandom()),
				Lineages.today(server), Epithets.snapshot(player));
			lineage.current = newcomer;
			lineage.ancestors.clear();
			lineage.ownQuests.clear();
			lineage.completed.clear();
		}
		lineage.house = target;
		target.members.add(player.getUUID());
		state.put(player.getUUID(), lineage);

		Lineages.record(server, lineage, Component.literal(keep
			? lineage.current.name() + " joined House " + target.name + ", bringing the line of House " + oldName + " with them."
			: lineage.current.name() + " joined House " + target.name + "."));
		Lineages.tellHouse(server, target, Component.literal(lineage.fullName() + " has joined House " + target.name + "!")
			.withStyle(ChatFormatting.GOLD));
		settle(player, lineage);
	}

	/** The player leaves their house and founds a new one, keeping their character. */
	public static void leave(ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null) return;
		if (lineage.house.members.size() == 1) {
			player.sendSystemMessage(fail("You are the only member of House " + lineage.house.name + "."));
			return;
		}
		split(player, lineage, "left");
	}

	/** The head removes a member, who founds a house of their own. */
	public static void remove(ServerPlayer head, ServerPlayer target) {
		Lineage lineage = Lineages.get(head);
		Lineage theirs = Lineages.get(target);
		if (lineage == null || theirs == null) return;
		if (!lineage.isHead(head.getUUID())) {
			head.sendSystemMessage(fail("Only the head of the house can remove members."));
			return;
		}
		if (theirs.house != lineage.house || target == head) {
			head.sendSystemMessage(fail(theirs.fullName() + " isn't another member of your house."));
			return;
		}
		split(target, theirs, "was sent away from");
	}

	public static void makeHead(ServerPlayer head, ServerPlayer target) {
		Lineage lineage = Lineages.get(head);
		Lineage theirs = Lineages.get(target);
		if (lineage == null || theirs == null) return;
		if (!lineage.isHead(head.getUUID())) {
			head.sendSystemMessage(fail("Only the head of the house can pass on leadership."));
			return;
		}
		if (theirs.house != lineage.house) {
			head.sendSystemMessage(fail(theirs.fullName() + " isn't a member of your house."));
			return;
		}
		lineage.house.head = target.getUUID();
		Lineages.record(head.level().getServer(), lineage, Component.literal(theirs.current.name() + " became head of House " + lineage.house.name + "."));
		Lineages.tellHouse(head.level().getServer(), lineage.house, Component.literal(theirs.fullName() + " is now head of House "
			+ lineage.house.name + ".").withStyle(ChatFormatting.GOLD));
	}

	private static void split(ServerPlayer player, Lineage lineage, String verb) {
		MinecraftServer server = player.level().getServer();
		BloodlineState state = BloodlineState.get(server);
		House old = lineage.house;
		Lineages.record(server, lineage, Component.literal(lineage.current.name() + " " + verb + " House " + old.name + "."));
		detach(server, old, player.getUUID(), lineage.current.name());
		House founded = House.found(Names.house(player.getRandom()), player.getUUID());
		state.addHouse(founded);
		lineage.house = founded;
		state.put(player.getUUID(), lineage);
		Lineages.record(server, lineage, Component.literal(lineage.current.name() + " " + verb + " House " + old.name
			+ " and founded House " + founded.name + "."));
		Lineages.tellHouse(server, old, Component.literal(lineage.current.name() + " " + verb + " House " + old.name + ".").withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Component.literal("You " + verb + " House " + old.name + " and founded House " + founded.name + ". ")
			.withStyle(ChatFormatting.GOLD)
			.append(Component.literal("Rename it with /bloodline house <name>.").withStyle(ChatFormatting.GRAY)));
		settle(player, lineage);
	}

	/** Takes a player out of a house, passing on leadership or ending the house if they were the last. */
	static void detach(MinecraftServer server, House house, UUID player, String name) {
		house.members.remove(player);
		if (house.members.isEmpty()) {
			BloodlineState.get(server).removeHouse(house);
			return;
		}
		if (house.head.equals(player)) {
			house.head = house.members.getFirst();
			ServerPlayer newHead = server.getPlayerList().getPlayer(house.head);
			if (newHead != null) {
				newHead.sendSystemMessage(Component.literal(name + " has gone. You are now head of House " + house.name + ".").withStyle(ChatFormatting.GOLD));
			}
		}
		Lineages.markDirty(server);
	}

	/** Brings a player who changed house up to date: name, traits (legacies differ per house), book and quests. */
	private static void settle(ServerPlayer player, Lineage lineage) {
		Traits.apply(player, lineage.allTraits());
		Lineages.refreshName(player);
		Chronicle.ensure(player);
		Inns.addIntroQuest(player, lineage);
		Questlines.resume(player, lineage);
		player.sendSystemMessage(Component.literal("You are ").append(Component.literal(lineage.fullName()).withStyle(ChatFormatting.GOLD))
			.append(", of House " + lineage.house.name + "."));
		if (lineage.house.oath.isEmpty()) Questlines.nudgeOath(player, lineage);
	}
}
