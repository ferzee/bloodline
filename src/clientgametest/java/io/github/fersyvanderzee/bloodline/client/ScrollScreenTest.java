package io.github.fersyvanderzee.bloodline.client;

import io.github.fersyvanderzee.bloodline.ScrollPayload;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Opens each kind of Bloodline Chronicles scroll in a real game and takes a screenshot of it (in build/clientgametest/screenshots). */
public class ScrollScreenTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			show(context, "heir", heirs());
			show(context, "feud", new ScrollPayload(Component.literal("A Rival House"),
				List.of(Component.literal("House Blackwood has declared a feud on House Oakheart!").withStyle(ChatFormatting.GOLD),
					Component.literal("Their champion will hunt you at night unless you pay tribute.")),
				List.of(),
				List.of(new ScrollPayload.Choice(Component.literal("Accept the challenge"), Component.literal("Their champion comes for you at night."),
						"/bloodline event accept", ScrollPayload.Tone.PRIMARY.ordinal()),
					new ScrollPayload.Choice(Component.literal("Pay 8 emeralds tribute"), Component.literal("Buy peace. The house loses 3 renown."),
						"/bloodline event decline", ScrollPayload.Tone.PLAIN.ordinal())),
				Component.empty(), new ItemStack(Items.IRON_SWORD)));
			show(context, "sacrifice", new ScrollPayload(Component.literal("The Sacrifice"),
				List.of(Component.literal("You kneel at Elske the Bold's grave under the full moon."),
					Component.literal("Will Robin Oakheart give their life for the house?"),
					Component.literal("Their heir inherits every boon and the house gains 10 renown.").withStyle(ChatFormatting.GRAY)),
				List.of(),
				List.of(new ScrollPayload.Choice(Component.literal("Give my life").withStyle(ChatFormatting.DARK_RED), Component.empty(),
						"/bloodline sacrifice confirm", ScrollPayload.Tone.BLOOD.ordinal()),
					new ScrollPayload.Choice(Component.literal("Not yet"), Component.empty(), "", ScrollPayload.Tone.PLAIN.ordinal())),
				Component.empty(), new ItemStack(Items.WITHER_ROSE)));
		}
	}

	private static void show(ClientGameTestContext context, String name, ScrollPayload scroll) {
		context.setScreen(() -> new ScrollScreen(scroll));
		context.waitTicks(10);
		context.takeScreenshot("bloodline_" + name);
		context.setScreen(() -> null);
		context.waitTick();
	}

	private static ScrollPayload heirs() {
		List<ScrollPayload.Card> cards = List.of(
			card(1, "Robin", List.of(Component.literal("Mighty").withStyle(ChatFormatting.RED),
				Component.literal("Keen-Eyed").withStyle(ChatFormatting.AQUA).append(Component.literal(" (new)").withStyle(ChatFormatting.DARK_GRAY)))),
			card(2, "Marek", List.of(Component.literal("Sure-Footed").withStyle(ChatFormatting.GREEN).append(Component.literal(" (new)").withStyle(ChatFormatting.DARK_GRAY)),
				Component.literal("Stalwart").withStyle(ChatFormatting.GOLD))),
			card(3, "Isolde", List.of(Component.literal("Mighty").withStyle(ChatFormatting.RED), Component.literal("Frail").withStyle(ChatFormatting.DARK_PURPLE),
				Component.literal("Lucky").withStyle(ChatFormatting.YELLOW).append(Component.literal(" (new)").withStyle(ChatFormatting.DARK_GRAY)))));
		return new ScrollPayload(Component.literal("Choose an Heir"),
			List.of(Component.literal("Ember the Bold leaves 3 children. Who will carry on the line?")),
			cards, List.of(), Component.literal("You can also choose later in chat or in your Chronicle."), new ItemStack(Items.TOTEM_OF_UNDYING));
	}

	private static ScrollPayload.Card card(int number, String name, List<Component> traits) {
		return new ScrollPayload.Card(Component.literal(name), Component.literal("Generation III"), traits,
			Component.literal(name + "'s traits, explained"), "/bloodline heir " + number);
	}
}
