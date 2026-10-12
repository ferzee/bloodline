package io.github.fersyvanderzee.bloodline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server settings, read from {@code config/bloodline.json}. The file is created with the defaults
 * on first start and rewritten on every start, so settings added in later versions appear in it
 * with their default values. Invalid values fall back to the defaults.
 */
public final class BloodlineConfig {
	public static final class Families {
		/** Most traits a family member can be born with or earn. */
		public int maxTraits = 4;
		/** Chance that each of a parent's boons and quirks passes to a child. */
		public double boonPassChance = 0.5;
		/** Chance that each of a parent's flaws passes to a child. */
		public double flawPassChance = 0.35;
		/** Chances that a child's new trait is a quirk or a flaw (otherwise a boon). */
		public double newTraitQuirkChance = 0.15;
		public double newTraitFlawChance = 0.25;
		/** How many children a dead family member leaves to choose from. */
		public int heirChoices = 3;
		/** Seconds of play before the family chooses an heir for a player who hasn't. */
		public int heirAutoChooseSeconds = 120;
		/** A life shorter than this (minutes of play) is "the Brief" and passes no traits on. */
		public int briefLifeMinutes = 20;
	}

	public static final class Renown {
		/** Renown the house needs to begin each act of the oath (acts I to IV). */
		public int[] actThresholds = {0, 10, 25, 45};
		public int bloodDebt = 5;
		public int unfinishedBusiness = 3;
		public int restingPlace = 2;
		public int foundingGoal = 2;
		public int preparation = 2;
		public int innJob = 3;
		public int reclaimedHeirloom = 4;
		public int actCompleted = 5;
		/** Lost when a member dies leaving their blood debts unpaid (per debt). */
		public int debtInherited = -2;
		/** Lost when an old blood debt is pushed out by newer ones. */
		public int debtForgotten = -3;
		/** Most unpaid blood debts a house can carry. */
		public int maxBloodDebts = 3;
	}

	public static final class Sacrifice {
		public int renown = 10;
		/** Sacrifices after which the house becomes Blood-Sworn. */
		public int sacrificesForBloodSworn = 3;
	}

	public static final class Steward {
		public int hireCost = 10;
		public int moveCost = 2;
		/** Vault slots (at most 9) unlocked at the matching renown: 3 at 0, 6 at 25, 9 at 50. */
		public int[] vaultSlots = {3, 6, 9};
		public int[] vaultRenown = {0, 25, 50};
	}

	public static final class Inns {
		public int maxJobsPerPlayer = 2;
		/** Whether a bell placed by a player also attracts an innkeeper, or only bells in villages. */
		public boolean innkeepersAtPlayerBells = true;
	}

	public static final class Houses {
		public int maxMembers = 12;
	}

	public static final class Heirlooms {
		/** Highest rank an heirloom's bonus can reach. */
		public int maxRank = 3;
	}

	public static final class Structures {
		/**
		 * How far (in chunks) to look for structures for quests and maps. Finding rare structures
		 * far away can briefly pause the server, so keep this modest on busy servers.
		 */
		public int searchRadiusChunks = 100;
		/** Search radius for inn rumours, which ask for rarer places more often. */
		public int rumourSearchRadiusChunks = 64;
	}

	public Families families = new Families();
	public Renown renown = new Renown();
	public Sacrifice sacrifice = new Sacrifice();
	public Steward steward = new Steward();
	public Inns inns = new Inns();
	public Houses houses = new Houses();
	public Heirlooms heirlooms = new Heirlooms();
	public Structures structures = new Structures();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static BloodlineConfig current = new BloodlineConfig();

	public static BloodlineConfig get() {
		return current;
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("bloodline.json");
		BloodlineConfig config = new BloodlineConfig();
		if (Files.exists(file)) {
			try {
				BloodlineConfig read = GSON.fromJson(Files.readString(file), BloodlineConfig.class);
				if (read != null) config = read;
			} catch (IOException | JsonParseException e) {
				Bloodline.LOGGER.error("Couldn't read {}, using the default settings", file, e);
			}
		}
		config.fillGaps();
		current = config;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(config));
		} catch (IOException e) {
			Bloodline.LOGGER.warn("Couldn't write {}", file, e);
		}
	}

	/** Replaces sections missing from an older file and values that make no sense with the defaults. */
	private void fillGaps() {
		if (families == null) families = new Families();
		if (renown == null) renown = new Renown();
		if (sacrifice == null) sacrifice = new Sacrifice();
		if (steward == null) steward = new Steward();
		if (inns == null) inns = new Inns();
		if (houses == null) houses = new Houses();
		if (heirlooms == null) heirlooms = new Heirlooms();
		if (structures == null) structures = new Structures();
		if (renown.actThresholds == null || renown.actThresholds.length < 4) renown.actThresholds = new Renown().actThresholds;
		if (steward.vaultSlots == null || steward.vaultRenown == null || steward.vaultSlots.length == 0
			|| steward.vaultSlots.length != steward.vaultRenown.length) {
			steward.vaultSlots = new Steward().vaultSlots;
			steward.vaultRenown = new Steward().vaultRenown;
		}
		families.maxTraits = Math.max(1, families.maxTraits);
		families.heirChoices = Math.max(1, Math.min(5, families.heirChoices));
		houses.maxMembers = Math.max(1, houses.maxMembers);
		inns.maxJobsPerPlayer = Math.max(1, inns.maxJobsPerPlayer);
		structures.searchRadiusChunks = Math.max(8, structures.searchRadiusChunks);
		structures.rumourSearchRadiusChunks = Math.max(8, structures.rumourSearchRadiusChunks);
	}
}
