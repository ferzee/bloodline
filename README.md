# Bloodline

**You don't play one hero. You play a family.**

Bloodline is a Fabric mod for Minecraft 26.3. When your character dies, they're gone for good, and their heir carries on. The heir inherits some of their parent's traits, the gear the family has carried for generations, and a chronicle of everything the family has done.

It runs entirely on the server, so players can join a Bloodline server with a vanilla client.

## Features

- **Families.** The first time you join, you found a house, e.g. *Elske Ashford, founder of House Ashford*. Your character's name is shown in chat, the tab list and death messages.
- **Heirs.** When you die, your character becomes an ancestor and you respawn as their child, one generation later.
- **Traits.** Each family member is born with traits such as *Stalwart*, *Swift*, *Long-Armed*, *Frail* or *Tall*. Heirs inherit some of their parent's traits and gain one new one.
- **Epithets.** When someone dies they get a name based on how they lived: *the Wanderer*, *the Delver*, *the Slayer*, *the Brief*...
- **Heirlooms.** Gear remembers who carried it. Once it has been carried by two generations it becomes a named heirloom (*"Oathkeeper"*). Heirlooms stay with the family when their bearer dies and grow stronger with each generation.
- **Family duties (quests).** Founders get a few goals to start their house. When someone dies, their heir inherits duties from them:
  - **Blood debt:** avenge them by killing whatever killed them. Unsettled debts pass down the generations. Settling one gives the heir a new trait that their children can inherit.
  - **Resting place:** visit the spot where they fell (and pick up what they dropped).
  - **Unfinished business:** finish something they cared about, based on how they lived. A Delver leaves a mine to finish, a Wanderer leaves a road to walk.
- **The Founder's Oath.** Each house swears one of three oaths, each a questline of four acts. A family can finish at most one act per generation, so an oath takes at least four lives:
  - **Oath of the Sword:** forge the Oathblade, temper it on Blazes, defeat the Captain of a real Nether Fortress, and slay the Wither with it.
  - **Oath of the Deep:** dig deep, find a real Ancient City, defeat the Lost Delver who haunts it, and slay a Warden.
  - **Oath of the Hearth:** befriend villagers, win raids, and defeat the Warlord at a real Pillager Outpost.

  Finishing an oath gives the family a **legendary trait** that every later heir is born with.
- **Renown.** Fulfilling duties earns your house renown (blood debts are worth the most); leaving debts unpaid costs it. Each act of the oath needs more renown to begin (Act II: 10, Act III: 25, Act IV: 45), so side duties drive the main quest.
- **Preparations.** Before each champion fight the family gets three optional preparation duties. Each one weakens the champion: no bodyguards, 30% less health, or glowing and slowed.
- **Champions.** Named bosses with extra health, gear, bodyguards and a boss bar. They wait at real structures in your world, and quests give you a map to find them.
- **The Stolen Heirloom.** If an heirloom is destroyed (burned, despawned, lost in the void), it turns out thieves took it. Track their captain to a pillager outpost and take it back.
- **The Chronicle.** A book that writes itself: births, deaths, heirlooms, first steps into the Nether and the End, and bosses slain.

## Commands

| Command | What it does |
| --- | --- |
| `/bloodline` | Your family, current character and traits |
| `/bloodline info <player>` | Someone else's family |
| `/bloodline tree [player]` | The full family tree |
| `/bloodline quests` | Your family's duties and progress |
| `/bloodline oath [sword\|deep\|hearth]` | See or swear your family's oath |
| `/bloodline chronicle` | A new copy of the family chronicle |
| `/bloodline house <name>` | Rename your house |

Operators can use `/bloodline debug complete` to finish the newest quest and `/bloodline debug champion` to spawn a test champion.

## Building

Requires Java 25.

```
./gradlew build
```

The mod jar ends up in `build/libs/`. It needs [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://modrinth.com/mod/fabric-api).
