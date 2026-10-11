package io.github.fersyvanderzee.bloodline;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class Bloodline implements ModInitializer {
	public static final String MOD_ID = "bloodline";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int HEIRLOOM_TICK_INTERVAL = 5;
	private static final Map<String, String> BOSSES = Map.of(
		"ender_dragon", "slew the Ender Dragon.",
		"wither", "destroyed the Wither.",
		"elder_guardian", "slew an Elder Guardian.",
		"warden", "slew a Warden."
	);

	@Override
	public void onInitialize() {
		ServerPlayerEvents.JOIN.register(Lineages::onJoin);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) Lineages.onDeath(player);
		});
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
			if (!alive) Heirlooms.passOn(oldPlayer, newPlayer);
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> Lineages.onRespawn(newPlayer, alive));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % HEIRLOOM_TICK_INTERVAL != 0) return;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.isAlive()) Heirlooms.tick(player);
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
			Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType());
			String deed = type.getNamespace().equals("minecraft") ? BOSSES.get(type.getPath()) : null;
			if (deed != null) Lineages.milestone(player, "killed_" + type.getPath() + "_" + Lineages.get(player).current.generation(), deed);
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (player instanceof ServerPlayer serverPlayer && Chronicle.isChronicle(stack)) {
				Chronicle.refresh(serverPlayer, stack);
			}
			return InteractionResult.PASS;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> BloodlineCommands.register(dispatcher));

		LOGGER.info("Bloodline loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
