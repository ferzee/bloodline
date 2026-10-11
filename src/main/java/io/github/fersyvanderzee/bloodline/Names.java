package io.github.fersyvanderzee.bloodline;

import net.minecraft.util.RandomSource;

import java.util.List;

final class Names {
	private static final List<String> GIVEN = List.of(
		"Aldric", "Bram", "Cedric", "Dorian", "Edmund", "Fenn", "Gareth", "Hale", "Ivo", "Jory", "Kael", "Leif",
		"Marek", "Nils", "Osric", "Piers", "Quill", "Rowan", "Silas", "Tobin", "Ulric", "Wren", "Yorick", "Alys",
		"Brienne", "Cora", "Dalia", "Elske", "Freya", "Gwen", "Hilde", "Isolde", "Jessa", "Kira", "Liesel", "Maren",
		"Nessa", "Odile", "Petra", "Rosalind", "Saskia", "Tamsin", "Una", "Vela", "Wilma", "Ysolde", "Ash", "Ember",
		"Rook", "Sage", "Robin", "Morgan", "Linden", "Arden", "Tarran", "Briar", "Ellis", "Jules", "Quinn", "Sorrel"
	);

	private static final List<String> HOUSES = List.of(
		"Ashford", "Blackwood", "Coldwater", "Duskmere", "Embervale", "Fairholt", "Greymantle", "Hollowell",
		"Ironside", "Kingsley", "Larkspur", "Mossgrave", "Northcott", "Oakheart", "Pemberly", "Ravensworth",
		"Stonebridge", "Thornfield", "Underhill", "Valemont", "Westbrook", "Whitlock", "Wolfsbane", "Yarrow"
	);

	private Names() {
	}

	static String given(RandomSource random, String avoid) {
		String name;
		do {
			name = GIVEN.get(random.nextInt(GIVEN.size()));
		} while (name.equals(avoid));
		return name;
	}

	static String house(RandomSource random) {
		return HOUSES.get(random.nextInt(HOUSES.size()));
	}

	/** Roman numeral for a generation, used in titles ("Generation IV"). */
	static String numeral(int n) {
		String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
		int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < values.length; i++) {
			while (n >= values[i]) {
				out.append(symbols[i]);
				n -= values[i];
			}
		}
		return out.toString();
	}
}
