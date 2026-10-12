package io.github.fersyvanderzee.bloodline;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class Bloodline implements ModInitializer {
	public static final String MOD_ID = "bloodline";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int HEIRLOOM_TICK_INTERVAL = 5;
	private static final int QUEST_TICK_INTERVAL = 20;
	private static final int INN_TICK_INTERVAL = 100;
	private static final Map<String, String> BOSSES = Map.of(
		"ender_dragon", "slew the Ender Dragon.",
		"wither", "destroyed the Wither.",
		"elder_guardian", "slew an Elder Guardian.",
		"warden", "slew a Warden."
	);

	@Override
	public void onInitialize() {
		BloodlineConfig.load();
		// Read the file again whenever a world starts, so edits apply without restarting the game.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> BloodlineConfig.load());
		ServerPlayerEvents.JOIN.register(Lineages::onJoin);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) Lineages.onDeath(player, source);
			if (Champions.isChampion(entity)) Champions.onDeath(entity.level().getServer(), entity);
		});
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
			if (!alive) Heirlooms.passOn(oldPlayer, newPlayer);
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> Lineages.onRespawn(newPlayer, alive));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % HEIRLOOM_TICK_INTERVAL != 0) return;
			boolean questTick = server.getTickCount() % QUEST_TICK_INTERVAL == 0;
			Champions.tick(server);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!player.isAlive()) continue;
				Heirlooms.tick(player);
				var lineage = Lineages.get(player);
				if (questTick && lineage != null && lineage.awaitingHeir) Heirs.tick(player, lineage);
				if (questTick && lineage != null && !lineage.awaitingHeir) {
					Quests.tick(player, lineage);
					Questlines.tickReclaim(player, lineage);
					if (server.getTickCount() % INN_TICK_INTERVAL == 0) {
						Inns.tick(player);
						Stewards.tick(player, lineage);
					}
				}
			}
		});

		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			if (destination.dimension() == Level.NETHER) {
				Lineages.milestone(player, "entered_nether", "was the first of the family to set foot in the Nether.");
			} else if (destination.dimension() == Level.END) {
				Lineages.milestone(player, "entered_end", "was the first of the family to reach the End.");
			}
		});

		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed, source) -> {
			if (!(killer instanceof ServerPlayer player)) return;
			if (killed instanceof ServerPlayer victim) Quests.onPlayerKilled(player, victim);
			Quests.onKill(player, killed);
			Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType());
			String deed = type.getNamespace().equals("minecraft") ? BOSSES.get(type.getPath()) : null;
			if (deed != null) Lineages.milestone(player, "killed_" + type.getPath() + "_" + Lineages.get(player).current.generation(), deed);
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> Champions.onLoad(entity));
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			Champions.onUnload(entity);
			// An heirloom lying on the ground that burns, despawns or falls out of the world is "lost".
			// Picked-up items are removed too, but with an empty stack.
			if (entity instanceof ItemEntity item && !item.getItem().isEmpty()) {
				Entity.RemovalReason reason = item.getRemovalReason();
				if (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED) {
					Questlines.onHeirloomLost(level.getServer(), item.getItem());
				}
			}
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (player instanceof ServerPlayer serverPlayer && Chronicle.isChronicle(stack)) {
				Chronicle.refresh(serverPlayer, stack);
			}
			return InteractionResult.PASS;
		});

		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			boolean innkeeper = Inns.isInnkeeper(entity);
			if (!innkeeper && !Stewards.isSteward(entity)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer serverPlayer && entity instanceof Villager villager && hand == InteractionHand.MAIN_HAND) {
				if (innkeeper) Inns.onInteract(serverPlayer, villager);
				else Stewards.onInteract(serverPlayer, villager);
			}
			return InteractionResult.SUCCESS;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> BloodlineCommands.register(dispatcher));

		LOGGER.info("Bloodline loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
