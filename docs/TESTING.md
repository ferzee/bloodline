# Testing Bloodline Chronicles

How every change is tested before it's called done. Two scripts work together:

- **This file** is for Claude (or any developer): what to run, what to check by hand, and which rules a change has to meet.
- **[`docs/test-script.html`](test-script.html)** is for the player-tester: about 120 in-game tests with what to do and what to expect. It is published as the "Bloodline Test Script" artifact. Test IDs below (such as `F2`) refer to it.

A change is done when the steps for its kind (section 2) pass, and the report to the user (section 7) says what was and wasn't covered.

## 1. The one command

```bash
tools/check.sh
```

It stops at the first failure and keeps its logs in `build/check/`. Its five steps:

1. **Text and data:** `tools/check_text.py`. Every JSON file under `src/` parses, every `Text.t("…")` key exists in `en_us.json`, and no text has a placeholder Minecraft can't read.
2. **Build:** `./gradlew build` produces `build/libs/bloodline-chronicles-<version>.jar`.
3. **Server game tests:** `./gradlew runGametest` must print "All N required tests passed". It must also log no `[Server thread/ERROR]`, because an error caught by `safely(...)` doesn't fail a test.
4. **Screen tests:** `./gradlew runClientGametest` opens a real game window and saves screenshots to `build/clientgametest/screenshots`.
   - **Look at the screenshots** (the Read tool shows images). The check only proves the screens opened, not that they look right.
   - Use `--no-client` to skip this step when nothing on screen changed.
5. **Website:** `python3 website/build.py`.

Running it needs `JAVA_HOME` (the script defaults to Homebrew's `openjdk@25`). It takes about two minutes, or one with `--no-client`. Run it in the background and keep working.

## 2. What to run for which change

| The change touches | Run | Also |
|---|---|---|
| Only text in `en_us.json` | `python3 tools/check_text.py` | Read the new text in context: does it point players to the Chronicle, not to commands? |
| Game logic in `src/main` | `tools/check.sh --no-client` | Add or adjust a game test (section 4). |
| Screens in `src/client` (scrolls, Chronicle) or GUI sprites | `tools/check.sh` | Look at every screenshot. Check the text fits and nothing overlaps, in both the mod's screens and the vanilla fallback. |
| Structures (`tools/generate_*.py`, worldgen JSON, `FlatGround`) | Regenerate, then `tools/check.sh --no-client`, then `./gradlew runClientGametest -Pterrain=true` | `PlaceGameTests` place the inn and the crypt. The terrain check builds a normal world with and without `FlatGround`, finds crypts, inns and shrines, logs how far the land around each is from its ground level (`[terrain]` lines) and takes a screenshot of each (`terrain_*`). For looks, ask the user to `/locate` and look (test-script K, M). |
| Config (`BloodlineConfig`) | `tools/check.sh --no-client` | Update the server owners' page (`website/content/servers.html`). |
| The website | `python3 website/build.py`, then preview `website/dist` | Check desktop and phone width (375 px) in the browser pane. Nothing should scroll sideways. |
| Before a release | `tools/check.sh` | Ask the user for a **Full test** with the test script, plus sections S and T. |

## 3. Rules every change must meet

1. **Every bug fix gets a test** that fails without the fix. Put it in the class that fits, or in `LifeGameTests` for edge cases.
2. **Every new feature gets a test and test-script entries.**
   - Add game tests for its rules.
   - Add rows to `docs/test-script.html` (`GROUPS`), each with *Do* and *Expect*.
   - Mark a row `q: 1` if it belongs in the quick check.
   - Quote in-game text exactly as it is in `en_us.json`.
   - Republish the artifact (the same file path keeps its URL).
3. **Record it in `CHANGELOG.md`.** Every change a player or server owner would notice gets a line under **Unreleased**, in the group it fits: Added, Changed, Fixed or Removed. Write it for players, not developers.
4. **Keep the guides in sync.** If players would notice the change, update all three:
   - the website (`website/content/…`)
   - the Player Guide document (Claude Docs, `a169ea78-ff8d-4823-ab55-31c689dcafa3`)
   - the README
5. **Player-facing text points to the Chronicle, not commands.** Commands belong only on the server owners' page and in tester material.
6. **Choices open as windows.** Use `Screens.show` for any choice. It shows the parchment scroll, or Minecraft's own dialog for players without the mod.
7. **Think through the edge cases** below for any code that touches players, deaths, mobs or quests.

### Edge cases to think through

- **No family:** `Lineages.get(player)` can be `null` for other mods' fake players (Create deployers, ComputerCraft turtles). Never call a method on it unchecked.
- **Mourning:** a player waiting to choose an heir has `lineage.awaitingHeir`. Quests, traits and heirlooms are paused then.
- **Peaceful:** hostile mobs vanish at once. Anything that spawns a hostile mob goes through `Champions.canAppear`. Kill goals can't progress on Peaceful.
- **Multiplayer:**
  - Shared house quests add up every member's progress.
  - Only the head may swear oaths, invite, remove or rename.
  - Messages that concern the house go to every online member (`Lineages.tellHouse`).
- **Deaths anywhere:** in the Nether, in the End's void (no resting place), with no killer (no blood debt) and with `keepInventory` on.
- **Saving:** every change to family data calls `Lineages.markDirty`. Data that is only kept in memory (deadlines, cooldowns) must cope with a relog or restart.
- **Text:** new keys go in `en_us.json`. `Text.t` keys built at runtime must exist for every value they can take.

## 4. Writing game tests

The tests live in `src/gametest/java/…`, and every test class is listed in `src/gametest/resources/fabric.mod.json`. `TestSupport` has the helpers:

- `join(helper)` makes a new player who founds a house.
- `lineage(...)`, `needQuest(...)`, `quest(...)`, `completed(...)` look up families and quests.
- `check(...)` and `checkText(...)` fail the test with a message (`checkText` catches missing text keys).
- `field(...)` and `call(...)` reach private parts of the mod.

`QuestGameTests.age(...)` (a full life) and `dieToZombie(...)` are the usual way to start a new generation.

Known gotchas:

- **Test players can't be killed with `/kill`.** A mock player's game never finishes loading, which keeps them invulnerable to `kill()`. Call `player.die(source)` directly, then `getPlayerList().respawn(...)`.
- **All tests share one level and run at the same time.** World-wide state (difficulty, time, game rules) must be changed and restored within the same tick (`try`/`finally`).
- **The test world is deleted before every `runGametest`,** so tests can't rely on anything from an earlier run.
- **`Inventory.add` empties the stack you pass in.** Read its name and count first.
- **Loot functions sit under `"modifier"` in this version, not `"functions"`.**

## 5. What the automated tests cover

This table shows which test-script sections are also covered by game tests, and the gaps that only a person playing can check.

| Test script | Covered by | Only by hand |
|---|---|---|
| A Automated | `tools/check.sh` | — |
| B First join | `founderGetsHouseGoalsAndChronicle`, `placingABannerRaisesTheHouseBanner`, `sleepingInABedFulfilsTheRoofGoal`, `slayingMonstersMakesAName`, `progressCountsOnlyFromWhenTheQuestStarted` | the title, sound and names in chat and the tab list |
| C Chronicle | `theChronicleBookHasAllItsText`, `ChronicleScreenTest` (screenshots) | page turning, bookmarks, a replacement copy |
| D Scrolls | `ScrollScreenTest` (screenshots) | clicking, hovering, Escape |
| E Heirs | `deathLeavesDutiesAndHeirsToChooseFrom`, `aBriefLifeLeavesBriefTasks`, `theFamilyChoosesAnHeirIfThePlayerDoesnt`, `heirsNeverHaveTooManyTraitsAndAlwaysSomethingNew`, `howALifeWasLivedPicksTheEpithet` | relogging while mourning, traits felt in play |
| F Duties | `bloodDebtIsSettledBySlayingTheKiller`, `strongAndRareKillersOnlyNeedSlayingOnce`, `visitingTheRestingPlaceFulfilsIt`, `unpaidDebtsPassDownAndCostRenown`, `everyUnfinishedBusinessTaskIsValid`, `aBodyLostToTheVoidLeavesNoRestingPlace` | crafting from the creative inventory not counting |
| G Renown | covered inside most tests | the chat line |
| H Heirlooms | `gearCarriedByTwoGenerationsBecomesAnHeirloom`, `aDestroyedHeirloomCanBeTakenBack` | tooltips, rank bonuses |
| I Oath | `oathOfTheSwordFromStartToEnd`, `oathOfTheDeepFromStartToEnd`, `oathOfTheHearthFromStartToEnd`, `theSwordOathForgesTheOathblade`, `theNextActWaitsForEnoughRenown`, `finishingAPreparationWeakensTheChampion` | champions at real structures |
| J Sacrifice | `theSacrificeNeedsAnActFulfilled`, `aSacrificeKeepsEveryBoonAndOwesNoDebt` | the full-moon ritual end to end, Blood-Sworn |
| K Inns | `innBoardsPostEveryKindOfJob`, `everyInnJobCanBeTakenWithoutTrouble`, `roadsideInnsPayHalfAsMuchAgain`, `aHuntIsDoneAfterEnoughKills`, `aDeliveryIsHandedInToAnInnkeeper`, `aLostSatchelAppearsWhenYouArrive`, `aRoadsideInnGetsAnInnkeeperBehindTheBar`, `aBountyChampionAppearsAtItsPlace` | courier and rumour trips, the inn's looks |
| L Steward | **none yet** | everything: hiring, the vault, moving |
| M Crypts | `aCryptCanBeSolvedOpenedAndLaidToRest` | wrong order, protection, the looks |
| U Shrines | `aShrineBlessesOnceADayAndCountsAFirstVisit`, the terrain check (screenshots) | the looks, the welcome message, the barrel |
| N Events | `sicknessIsCuredByAGoldenApple`, `aFeudAcceptedSendsTheRivalChampion`, `aFeudBoughtOffCostsRenown`, `aTributeYouCantPayStartsTheFeud`, `aWeddingBlessesTheNextChildren`, `aCousinBringsAnHeirloom`, `aRestlessSpiritHauntsAnAncestorsGrave` | night timing, expiry |
| O Multiplayer | `aPlayerCanJoinAnotherHouseAndShareItsDuties`, `joiningFreshStartsANewCharacter`, `leavingAHouseFoundsANewOne` | real two-client play |
| P Edge cases | `championsWaitWhileTheWorldIsPeaceful`, `aBossKilledByAnotherModsFakePlayerDoesNotCrash` | keepInventory, saving after a restart |
| Q Admin | none | the settings file, reset |
| R Text | `tools/check_text.py`, `checkText` in tests | the vanilla-client fallback |
| S Install, T Website | none | all |

When you add tests, update this table. Good next tests to write: the steward (hire, vault slots by renown, moving), a crypt solved in the wrong order, and an event that expires.

## 6. Things only the user can check

Some checks need a person in the game, so ask the user to do them:

- **Looks:** structures, how the scrolls feel to use.
- **Real travel:** champions at real structures, courier trips.
- **Two real clients** for multiplayer.
- **Installing** outside the dev setup.

Name the test-script IDs to run, for example "please do the Quick check, plus F2 and M4". They can then paste the report back.

## 7. Reporting to the user

After testing, tell the user plainly:

- What ran, and the result: "49 of 49 game tests passed", "screen tests passed, screenshots look right".
- Any test added, and what it guards against.
- What wasn't covered, and which test-script IDs they should play.
- Anything that failed, with the error, and never described as done.

## 8. Release checklist

1. Raise `version` in `gradle.properties`.
2. Run `tools/check.sh` (with the screen tests), and look at the screenshots.
3. Have the user do a Full test in the test script, including S (install) and T (website).
4. Rebuild the release files:
   - copy `build/libs/bloodline-chronicles-<version>.jar` to `release/`
   - zip it as `release/bloodline-chronicles-<version>.zip`
5. In `CHANGELOG.md`, turn **Unreleased** into the new version's section (`## 0.2.0`), and start a new, empty Unreleased section above it. That section is the changelog text for Modrinth and CurseForge.
6. Update the guides (website, Player Guide, README) and the test script.
7. Push on the user's "push it". The website publishes itself through GitHub Actions.
8. The user uploads the jar to Modrinth and CurseForge, with the version's changelog section.
