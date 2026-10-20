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
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
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


public class Bloodline implements ModInitializer {
	public static final String MOD_ID = "bloodline";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int HEIRLOOM_TICK_INTERVAL = 5;
	private static final int QUEST_TICK_INTERVAL = 20;
	private static final int INN_TICK_INTERVAL = 100;
	private static final java.util.Set<String> BOSSES = java.util.Set.of("ender_dragon", "wither", "elder_guardian", "warden");

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
			Screens.tick(server);
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
					safely("crypt tick", () -> Crypts.tick(player));
					safely("shrine tick", () -> Shrines.tick(player));
					safely("manor tick", () -> Manors.tick(player));
					if (server.getTickCount() % INN_TICK_INTERVAL == 0) {
						Inns.tick(player);
						Stewards.tick(player, lineage);
						FamilyEvents.tick(player, lineage);
					}
				}
			}
		});

		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			if (destination.dimension() == Level.NETHER) {
				Lineages.milestone(player, "entered_nether", "milestone.nether");
			} else if (destination.dimension() == Level.END) {
				Lineages.milestone(player, "entered_end", "milestone.end");
			}
		});

		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed, source) -> {
			if (!(killer instanceof ServerPlayer player)) return;
			if (killed instanceof ServerPlayer victim) Quests.onPlayerKilled(player, victim);
			Quests.onKill(player, killed);
			Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType());
			// Fake players from other mods (deployers, turtles) kill things too, but have no family.
			var lineage = Lineages.get(player);
			if (lineage != null && type.getNamespace().equals("minecraft") && BOSSES.contains(type.getPath())) {
				Lineages.milestone(player, "killed_" + type.getPath() + "_" + lineage.current.generation(), "milestone.killed." + type.getPath());
			}
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

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer serverPlayer) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			try {
				InteractionResult shrine = Shrines.onUseBlock(serverPlayer, level, hit.getBlockPos(), player.getItemInHand(hand));
				if (shrine != InteractionResult.PASS) return shrine;
				InteractionResult manor = Manors.onUseBlock(serverPlayer, level, hit.getBlockPos());
				if (manor != InteractionResult.PASS) return manor;
				return Crypts.onUseBlock(serverPlayer, level, hit.getBlockPos());
			} catch (RuntimeException e) {
				LOGGER.error("Bloodline: crypt or shrine interaction failed", e);
				return InteractionResult.PASS;
			}
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			try {
				return Crypts.canBreak(level, player, pos);
			} catch (RuntimeException e) {
				LOGGER.error("Bloodline: crypt protection check failed", e);
				return true;
			}
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

		// Players who have the mod get choices as a parchment scroll instead of Minecraft's own dialog window.
		PayloadTypeRegistry.clientboundPlay().register(ScrollPayload.TYPE, ScrollPayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> BloodlineCommands.register(dispatcher));

		LOGGER.info("Bloodline Chronicles loaded");
	}

	/** Runs a feature's tick, logging a failure instead of letting it crash the server. */
	private static void safely(String what, Runnable task) {
		try {
			task.run();
		} catch (RuntimeException e) {
			LOGGER.error("Bloodline: {} failed", what, e);
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
