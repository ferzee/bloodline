package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A house's steward: where they live, their name, and the family vault they keep. The vault is a
 * single live container shared by every open vault menu, so two members can't duplicate items.
 */
public class Steward {
	public static final int VAULT_SIZE = 9;

	public static final Codec<Steward> CODEC = RecordCodecBuilder.create(i -> i.group(
		GlobalPos.MAP_CODEC.fieldOf("home").forGetter(s -> s.home),
		Codec.STRING.fieldOf("name").forGetter(s -> s.name),
		ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("vault", List.of()).forGetter(Steward::vaultItems)
	).apply(i, Steward::new));

	public GlobalPos home;
	public final String name;
	public final SimpleContainer vault = new SimpleContainer(VAULT_SIZE);

	public Steward(GlobalPos home, String name, List<ItemStack> vault) {
		this.home = home;
		this.name = name;
		for (int slot = 0; slot < Math.min(vault.size(), VAULT_SIZE); slot++) {
			this.vault.setItem(slot, vault.get(slot));
		}
	}

	private List<ItemStack> vaultItems() {
		List<ItemStack> items = new ArrayList<>();
		for (int slot = 0; slot < VAULT_SIZE; slot++) items.add(vault.getItem(slot).copy());
		return items;
	}
}
