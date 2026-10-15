package io.github.fersyvanderzee.bloodline.client;

import io.github.fersyvanderzee.bloodline.Chronicle;
import io.github.fersyvanderzee.bloodline.Lineages;
import io.github.fersyvanderzee.bloodline.Questlines;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Opens the Chronicle the way a player does (the server tells the client to open the book) and checks that
 * Bloodline's illustrated book appears instead of the plain one; screenshots a few of its spreads.
 */
public class ChronicleScreenTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				var lineage = Lineages.get(player);
				lineage.house.renown = 12;
				Questlines.swear(player, lineage, "sword");
				// Hold the Chronicle and open it.
				var inventory = player.getInventory();
				for (int i = 0; i < inventory.getContainerSize(); i++) {
					ItemStack stack = inventory.getItem(i);
					if (Chronicle.isChronicle(stack)) {
						inventory.setItem(i, ItemStack.EMPTY);
						player.setItemInHand(InteractionHand.MAIN_HAND, stack);
						break;
					}
				}
				Chronicle.reopen(player);
			});
			context.waitForScreen(ChronicleScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("bloodline_chronicle_cover");
			for (int page : new int[]{2, 4, 6}) {
				context.runOnClient(minecraft -> ((ChronicleScreen) minecraft.gui.screen()).goTo(page));
				context.waitTicks(2);
				context.takeScreenshot("bloodline_chronicle_" + page);
			}
			context.setScreen(() -> null);
		}
	}
}
