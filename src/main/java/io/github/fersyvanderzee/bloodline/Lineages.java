package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.ChronicleEntry;
import io.github.fersyvanderzee.bloodline.data.House;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Member;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;

import java.util.List;
import java.util.Optional;

/** Founding families, recording deaths and passing the family on to an heir. */
public final class Lineages {
	private Lineages() {
	}

	public static Lineage get(ServerPlayer player) {
		return BloodlineState.get(player.level().getServer()).get(player.getUUID());
	}

	/** Members of the house who are online right now. */
	public static List<ServerPlayer> onlineMembers(MinecraftServer server, House house) {
		return house.members.stream().map(id -> server.getPlayerList().getPlayer(id)).filter(java.util.Objects::nonNull).toList();
	}

	/** "Bram Ashford (head), Cora Ashford" for every member of the house, online or not. */
	public static String memberNames(MinecraftServer server, House house) {
		BloodlineState state = BloodlineState.get(server);
		return String.join(", ", house.members.stream().map(id -> {
			Lineage member = state.get(id);
			String name = member == null ? "?" : member.fullName();
			return house.head.equals(id) ? name + " (head)" : name;
		}).toList());
	}

	/** Sends a message to every online member of the house. */
	public static void tellHouse(MinecraftServer server, House house, Component message) {
		onlineMembers(server, house).forEach(member -> member.sendSystemMessage(message));
	}

	/** Sends a message to every online member of the house except one. */
	public static void tellOthers(ServerPlayer except, House house, Component message) {
		onlineMembers(except.level().getServer(), house).stream().filter(m -> m != except).forEach(m -> m.sendSystemMessage(message));
	}

	public static void markDirty(MinecraftServer server) {
		BloodlineState.get(server).setDirty();
	}

	public static int today(MinecraftServer server) {
		return (int) (server.overworld().getOverworldClockTime() / 24000L) + 1;
	}

	public static void record(MinecraftServer server, Lineage lineage, Component text) {
		lineage.house.chronicle.add(new ChronicleEntry(today(server), text));
		markDirty(server);
	}

	/** Called when a player joins: founds a family the first time, otherwise restores traits. */
	public static void onJoin(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		BloodlineState state = BloodlineState.get(server);
		Lineage lineage = state.get(player.getUUID());
		if (lineage == null) {
			Member founder = new Member(Names.given(player.getRandom(), ""), 1, Traits.rollFounder(player.getRandom()),
				today(server), Epithets.snapshot(player));
			lineage = new Lineage(House.found(Names.house(player.getRandom()), player.getUUID()), founder);
			state.put(player.getUUID(), lineage);
			record(server, lineage, Component.literal(founder.name() + " founded House " + lineage.house.name + "."));
			Quests.addFounding(player, lineage);
			Chronicle.give(player);
			announceBirth(player, lineage, null);
		} else if (lineage.awaitingHeir && player.isAlive()) {
			Heirs.offer(player, lineage);
		} else {
			Questlines.resume(player, lineage);
			Inns.addIntroQuest(player, lineage);
			Quests.remind(player, lineage);
		}
		applyTraits(player, lineage);
		refreshName(player);
		if (lineage.house.oath.isEmpty() && !lineage.awaitingHeir) Questlines.nudgeOath(player, lineage);
	}

	/** Called when a player dies: the current family member becomes an ancestor. */
	public static void onDeath(ServerPlayer player, DamageSource source) {
		Lineage lineage = get(player);
		if (lineage == null || lineage.awaitingHeir) return;
		MinecraftServer server = player.level().getServer();
		Member dead = lineage.current;
		boolean sacrificed = Sacrifice.consume(player);
		String epithet = sacrificed ? "the Devoted" : Epithets.choose(dead.statsAtBirth(), Epithets.snapshot(player), player.getRandom());
		Component deathMessage = sacrificed
			? Component.literal(dead.name() + " gave their life for House " + lineage.house.name)
			: player.getCombatTracker().getDeathMessage();
		GlobalPos place = GlobalPos.of(player.level().dimension(), player.blockPosition());
		Ancestor ancestor = new Ancestor(dead.name(), epithet, dead.generation(), dead.traits(), dead.bornDay(), today(server), deathMessage,
			Optional.of(place));
		lineage.ancestors.add(ancestor);
		lineage.awaitingHeir = true;
		record(server, lineage, sacrificed
			? Component.literal(ancestor.fullName() + " gave their life for the house under the full moon.")
			: Component.literal(ancestor.fullName() + " died. ").append(deathMessage.copy()));
		Quests.onDeath(player, lineage, ancestor, source, sacrificed);
		if (sacrificed) Sacrifice.honour(player, lineage, ancestor);
		Heirs.roll(player, lineage);
	}

	/** Called when a player respawns. After a death, their heir takes over. */
	public static void onRespawn(ServerPlayer player, boolean alive) {
		Lineage lineage = get(player);
		if (lineage == null) return;
		if (!alive && lineage.awaitingHeir) {
			Heirs.offer(player, lineage);
		}
		applyTraits(player, lineage);
		player.setHealth(player.getMaxHealth());
		refreshName(player);
	}

	/** While mourning (before an heir is chosen) only the house's legendary traits apply. */
	private static void applyTraits(ServerPlayer player, Lineage lineage) {
		Traits.apply(player, lineage.awaitingHeir ? lineage.house.legacies : lineage.allTraits());
	}

	/** The chosen child takes their dead parent's place in the house. */
	public static void succeed(ServerPlayer player, Lineage lineage, Member chosen) {
		MinecraftServer server = player.level().getServer();
		Ancestor parent = lineage.parent();
		boolean inheritAll = lineage.inheritAll;
		lineage.inheritAll = false;
		Member heir = new Member(chosen.name(), chosen.generation(), chosen.traits(), today(server), Epithets.snapshot(player));
		lineage.current = heir;
		lineage.awaitingHeir = false;
		lineage.candidates.clear();
		// A new generation has come: the house may begin its next act of the oath.
		lineage.house.actPending = false;
		markDirty(server);
		record(server, lineage, Component.literal(heir.name() + ", child of " + parent.name() + ", took their place in House " + lineage.house.name + "."));
		server.getPlayerList().broadcastSystemMessage(Component.literal(parent.fullName() + " of House " + lineage.house.name
			+ " has died. " + heir.name() + " takes up the family name.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
		Questlines.resume(player, lineage);
		Inns.addIntroQuest(player, lineage);
		announceBirth(player, lineage, parent);
		applyTraits(player, lineage);
		player.setHealth(player.getMaxHealth());
		refreshName(player);
		if (lineage.house.oath.isEmpty()) Questlines.nudgeOath(player, lineage);
		if (inheritAll) {
			player.sendSystemMessage(Component.literal("Because " + parent.name() + " gave their life, every one of their boons lives on in "
				+ heir.name() + ".").withStyle(ChatFormatting.GOLD));
		}
	}

	private static void announceBirth(ServerPlayer player, Lineage lineage, Ancestor parent) {
		Member member = lineage.current;
		Component subtitle = parent == null
			? Component.literal("Founder of House " + lineage.house.name)
			: Component.literal("Heir of " + parent.fullName() + " · Generation " + Names.numeral(member.generation()));
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(lineage.fullName()).withStyle(ChatFormatting.GOLD)));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.level().playSound(null, player.blockPosition(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 0.6f, 1.2f);

		player.sendSystemMessage(Component.literal("You are ").append(Component.literal(lineage.fullName()).withStyle(ChatFormatting.GOLD))
			.append(parent == null ? ", founder of your house." : ", heir of " + parent.fullName() + "."));
		player.sendSystemMessage(Component.literal("Born with: ").append(traitList(member.traits(), parent)));
		Quests.remind(player, lineage);
	}

	/** Comma-separated trait names; traits that weren't inherited from the parent are marked as new. */
	public static MutableComponent traitList(List<String> traitIds, Ancestor parent) {
		MutableComponent out = Component.empty();
		for (int i = 0; i < traitIds.size(); i++) {
			Trait trait = Traits.get(traitIds.get(i));
			if (trait == null) continue;
			if (i > 0) out.append(", ");
			out.append(trait.displayName());
			if (parent != null && !parent.traits().contains(trait.id())) {
				out.append(Component.literal(" (new)").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		return traitIds.isEmpty() ? Component.literal("nothing remarkable").withStyle(ChatFormatting.GRAY) : out;
	}

	/** Records a family-wide first, such as the first to reach the Nether. Returns false if it already happened. */
	public static boolean milestone(ServerPlayer player, String key, String description) {
		Lineage lineage = get(player);
		if (lineage == null || !lineage.house.milestones.add(key)) return false;
		record(player.level().getServer(), lineage, Component.literal(lineage.current.name() + " " + description));
		player.sendSystemMessage(Component.literal("The chronicle of House " + lineage.house.name + " records: ")
			.withStyle(ChatFormatting.GRAY).append(Component.literal(lineage.current.name() + " " + description).withStyle(ChatFormatting.WHITE)));
		return true;
	}

	/** Display name used in chat, death messages and the tab list, e.g. "Bram Ashford". */
	public static Component displayName(ServerPlayer player, Component fallback) {
		Lineage lineage = get(player);
		return lineage == null ? fallback : Component.literal(lineage.fullName());
	}

	public static void refreshName(ServerPlayer player) {
		player.level().getServer().getPlayerList()
			.broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
	}
}
