package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.ChronicleEntry;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Member;
import net.minecraft.ChatFormatting;
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

/** Founding families, recording deaths and passing the family on to an heir. */
public final class Lineages {
	private Lineages() {
	}

	public static Lineage get(ServerPlayer player) {
		return BloodlineState.get(player.level().getServer()).get(player.getUUID());
	}

	public static void markDirty(MinecraftServer server) {
		BloodlineState.get(server).setDirty();
	}

	public static int today(MinecraftServer server) {
		return (int) (server.overworld().getOverworldClockTime() / 24000L) + 1;
	}

	public static void record(MinecraftServer server, Lineage lineage, Component text) {
		lineage.chronicle.add(new ChronicleEntry(today(server), text));
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
			lineage = new Lineage(Names.house(player.getRandom()), founder);
			state.put(player.getUUID(), lineage);
			record(server, lineage, Component.literal(founder.name() + " founded House " + lineage.house + "."));
			Quests.addFounding(player, lineage);
			Chronicle.give(player);
			announceBirth(player, lineage, null);
		} else if (lineage.awaitingHeir && player.isAlive()) {
			succeed(player, lineage);
		} else {
			Quests.remind(player, lineage);
		}
		Traits.apply(player, lineage.current.traits());
		refreshName(player);
	}

	/** Called when a player dies: the current family member becomes an ancestor. */
	public static void onDeath(ServerPlayer player, DamageSource source) {
		Lineage lineage = get(player);
		if (lineage == null || lineage.awaitingHeir) return;
		MinecraftServer server = player.level().getServer();
		Member dead = lineage.current;
		String epithet = Epithets.choose(dead.statsAtBirth(), Epithets.snapshot(player), player.getRandom());
		Component deathMessage = player.getCombatTracker().getDeathMessage();
		Ancestor ancestor = new Ancestor(dead.name(), epithet, dead.generation(), dead.traits(), dead.bornDay(), today(server), deathMessage);
		lineage.ancestors.add(ancestor);
		lineage.awaitingHeir = true;
		record(server, lineage, Component.literal(ancestor.fullName() + " died. ").append(deathMessage.copy()));
		Quests.onDeath(player, lineage, ancestor, source);
	}

	/** Called when a player respawns. After a death, their heir takes over. */
	public static void onRespawn(ServerPlayer player, boolean alive) {
		Lineage lineage = get(player);
		if (lineage == null) return;
		if (!alive && lineage.awaitingHeir) {
			succeed(player, lineage);
		}
		Traits.apply(player, lineage.current.traits());
		player.setHealth(player.getMaxHealth());
		refreshName(player);
	}

	private static void succeed(ServerPlayer player, Lineage lineage) {
		MinecraftServer server = player.level().getServer();
		Ancestor parent = lineage.parent();
		List<String> traits = Traits.rollHeir(parent.traits(), player.getRandom());
		Member heir = new Member(Names.given(player.getRandom(), parent.name()), parent.generation() + 1, traits,
			today(server), Epithets.snapshot(player));
		lineage.current = heir;
		lineage.awaitingHeir = false;
		markDirty(server);
		record(server, lineage, Component.literal(heir.name() + ", child of " + parent.name() + ", became head of House " + lineage.house + "."));
		server.getPlayerList().broadcastSystemMessage(Component.literal(parent.fullName() + " of House " + lineage.house
			+ " has died. " + heir.name() + " takes up the family name.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
		announceBirth(player, lineage, parent);
	}

	private static void announceBirth(ServerPlayer player, Lineage lineage, Ancestor parent) {
		Member member = lineage.current;
		Component subtitle = parent == null
			? Component.literal("Founder of House " + lineage.house)
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
		if (lineage == null || !lineage.milestones.add(key)) return false;
		record(player.level().getServer(), lineage, Component.literal(lineage.current.name() + " " + description));
		player.sendSystemMessage(Component.literal("The chronicle of House " + lineage.house + " records: ")
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
