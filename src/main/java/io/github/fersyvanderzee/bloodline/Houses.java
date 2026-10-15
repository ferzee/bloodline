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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
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

	private static Component fail(MutableComponent message) {
		return message.withStyle(ChatFormatting.RED);
	}

	public static void invite(ServerPlayer head, ServerPlayer target) {
		Lineage lineage = Lineages.get(head);
		Lineage theirs = Lineages.get(target);
		if (lineage == null || theirs == null) return;
		if (!lineage.isHead(head.getUUID())) {
			head.sendSystemMessage(fail(Text.t("house.invite.head_only", lineage.house.name)));
			return;
		}
		if (theirs.house == lineage.house) {
			head.sendSystemMessage(fail(Text.t("house.invite.already", theirs.fullName(), lineage.house.name)));
			return;
		}
		if (lineage.house.members.size() >= BloodlineConfig.get().houses.maxMembers) {
			head.sendSystemMessage(fail(Text.t("house.invite.full", lineage.house.name, BloodlineConfig.get().houses.maxMembers)));
			return;
		}
		INVITES.put(target.getUUID(), new Invite(lineage.house.id, head.getUUID(), head.level().getGameTime() + INVITE_TICKS));
		head.sendSystemMessage(Text.t("house.invite.sent", theirs.fullName(), lineage.house.name)
			.withStyle(ChatFormatting.GRAY));

		String oldHouse = theirs.house.name;
		boolean last = theirs.house.members.size() == 1;
		Component leftBehind = last ? Text.t("house.invite.old_ends", oldHouse) : Text.t("house.invite.old_continues", oldHouse);
		MutableComponent message = Text.t("house.invite.received", lineage.fullName(), lineage.house.name).withStyle(ChatFormatting.GOLD)
			.append(" ")
			.append(button(Text.t("button.join_keep"), "/bloodline accept keep", ChatFormatting.GREEN,
				Text.t("house.invite.keep_hover", theirs.current.name(), lineage.house.name).append("\n").append(leftBehind)))
			.append(" ")
			.append(button(Text.t("button.join_fresh"), "/bloodline accept fresh", ChatFormatting.AQUA,
				Text.t("house.invite.fresh_hover", lineage.house.name).append("\n").append(leftBehind)))
			.append(" ")
			.append(button(Text.t("button.decline"), "/bloodline decline", ChatFormatting.GRAY, Text.t("house.invite.decline_hover", oldHouse)));
		target.sendSystemMessage(message);
		Screens.show(target, Screens.Window.of(new ItemStack(Items.SHIELD), Text.t("house.invite.window"),
			List.of(Text.t("house.invite.received", lineage.fullName(), lineage.house.name), leftBehind),
			List.of(new Screens.Button(Text.t("button.join_keep"), Text.t("house.invite.keep_hover", theirs.current.name(), lineage.house.name),
					"/bloodline accept keep"),
				new Screens.Button(Text.t("button.join_fresh"), Text.t("house.invite.fresh_hover", lineage.house.name), "/bloodline accept fresh"),
				new Screens.Button(Text.t("button.decline"), Text.t("house.invite.decline_hover", oldHouse), "/bloodline decline"))));
	}

	private static Component button(Component label, String command, ChatFormatting color, Component hover) {
		return Component.empty().append(label).withStyle(s -> s.withColor(color)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	public static void decline(ServerPlayer player) {
		Invite invite = INVITES.remove(player.getUUID());
		if (invite == null) return;
		player.sendSystemMessage(Text.t("house.invite.declined").withStyle(ChatFormatting.GRAY));
		ServerPlayer from = player.level().getServer().getPlayerList().getPlayer(invite.from());
		Lineage theirs = Lineages.get(player);
		if (from != null && theirs != null) {
			from.sendSystemMessage(Text.t("house.invite.declined_by", theirs.fullName()).withStyle(ChatFormatting.GRAY));
		}
	}

	/** Accepts a pending invitation. With {@code keep}, the player's character and ancestors come along. */
	public static void accept(ServerPlayer player, boolean keep) {
		MinecraftServer server = player.level().getServer();
		BloodlineState state = BloodlineState.get(server);
		Invite invite = INVITES.remove(player.getUUID());
		House target = invite == null ? null : state.house(invite.house());
		if (target == null || player.level().getGameTime() > invite.expires()) {
			player.sendSystemMessage(fail(Text.t("house.invite.none")));
			return;
		}
		Lineage lineage = Lineages.get(player);
		if (lineage == null || lineage.awaitingHeir || !player.isAlive()) {
			player.sendSystemMessage(fail(Text.t("house.invite.not_now")));
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

		Lineages.record(server, lineage, keep
			? Text.t("chronicle.joined_keep", lineage.current.name(), target.name, oldName)
			: Text.t("chronicle.joined", lineage.current.name(), target.name));
		Lineages.tellHouse(server, target, Text.t("house.joined", lineage.fullName(), target.name)
			.withStyle(ChatFormatting.GOLD));
		settle(player, lineage);
	}

	/** The player leaves their house and founds a new one, keeping their character. */
	public static void leave(ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null) return;
		if (lineage.house.members.size() == 1) {
			player.sendSystemMessage(fail(Text.t("house.leave.only", lineage.house.name)));
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
			head.sendSystemMessage(fail(Text.t("house.remove.head_only")));
			return;
		}
		if (theirs.house != lineage.house || target == head) {
			head.sendSystemMessage(fail(Text.t("house.remove.not_member", theirs.fullName())));
			return;
		}
		split(target, theirs, "removed");
	}

	public static void makeHead(ServerPlayer head, ServerPlayer target) {
		Lineage lineage = Lineages.get(head);
		Lineage theirs = Lineages.get(target);
		if (lineage == null || theirs == null) return;
		if (!lineage.isHead(head.getUUID())) {
			head.sendSystemMessage(fail(Text.t("house.head.head_only")));
			return;
		}
		if (theirs.house != lineage.house) {
			head.sendSystemMessage(fail(Text.t("house.head.not_member", theirs.fullName())));
			return;
		}
		lineage.house.head = target.getUUID();
		Lineages.record(head.level().getServer(), lineage, Text.t("chronicle.new_head", theirs.current.name(), lineage.house.name));
		Lineages.tellHouse(head.level().getServer(), lineage.house, Text.t("house.head.new", theirs.fullName(), lineage.house.name)
			.withStyle(ChatFormatting.GOLD));
	}

	/** {@code how} is "left" or "removed", choosing which wording the messages use. */
	private static void split(ServerPlayer player, Lineage lineage, String how) {
		MinecraftServer server = player.level().getServer();
		BloodlineState state = BloodlineState.get(server);
		House old = lineage.house;
		Lineages.record(server, lineage, Text.t("chronicle." + how, lineage.current.name(), old.name));
		detach(server, old, player.getUUID(), lineage.current.name());
		House founded = House.found(Names.house(player.getRandom()), player.getUUID());
		state.addHouse(founded);
		lineage.house = founded;
		state.put(player.getUUID(), lineage);
		Lineages.record(server, lineage, Text.t("chronicle." + how + "_founded", lineage.current.name(), old.name, founded.name));
		Lineages.tellHouse(server, old, Text.t("chronicle." + how, lineage.current.name(), old.name).withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Text.t("house." + how + ".you", old.name, founded.name).withStyle(ChatFormatting.GOLD)
			.append(" ").append(Text.t("house.rename_hint").withStyle(ChatFormatting.GRAY)));
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
				newHead.sendSystemMessage(Text.t("house.head.inherited", name, house.name).withStyle(ChatFormatting.GOLD));
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
		player.sendSystemMessage(Text.t("house.you_are", Component.literal(lineage.fullName()).withStyle(ChatFormatting.GOLD), lineage.house.name));
		Questlines.nudgeOath(player, lineage);
	}
}
