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
- **Inns.** Every village gets an innkeeper by its bell (each inn has its own name, like *The Sleeping Griffin*). Talk to them to open the quest board, with new jobs every day. You can hold two inn jobs at a time:
  - **Bounties:** a named beast roams nearby. You get a map; slay it for emeralds and renown.
  - **Deliveries:** bring goods to any innkeeper.
  - **Rumours:** pay emeralds for a map to a structure nobody has found yet.
- **Shared houses.** Players can join each other's houses. Each member plays their own branch of the family, with their own character, heirs and family tree. The house shares its name, oath, renown, legendary traits, history, heirlooms and quests: oath steps, preparations and blood debts count every member's progress. The head of the house invites, removes and renames; when joining, a player either brings their own family along as a new branch or starts fresh as a newcomer.
- **The Sacrifice.** Under a full moon, at the resting place of an ancestor, a member may give their life for the house. It is a real death (their belongings fall where they knelt), but the heir inherits every boon, the house gains 10 renown, and the next act of the oath can begin. Three sacrifices make the house **Blood-Sworn**, a legendary trait.
- **The Steward.** Hire a steward at any inn (10 emeralds). They wait at your home and keep the family vault, shared by every member of the house: 3 slots, 6 at 25 renown, 9 at 50.
- **The Chronicle.** A book that writes itself: births, deaths, heirlooms, first steps into the Nether and the End, and bosses slain.

## Commands

| Command | What it does |
| --- | --- |
| `/bloodline` | Your family, current character and traits |
| `/bloodline info <player>` | Someone else's family |
| `/bloodline tree [player]` | The full family tree |
| `/bloodline quests` | Your family's duties and progress |
| `/bloodline oath [sword\|deep\|hearth]` | See or swear your family's oath |
| `/bloodline invite <player>` | Invite a player into your house (head only) |
| `/bloodline accept keep\|fresh` / `decline` | Answer an invitation: bring your family along, or start fresh |
| `/bloodline leave` | Leave your house and found a new one |
| `/bloodline remove <player>` | Send a member away (head only) |
| `/bloodline head <player>` | Pass leadership to another member (head only) |
| `/bloodline sacrifice` | Give your life for the house (full moon, at an ancestor's grave) |
| `/bloodline chronicle` | A new copy of the family chronicle |
| `/bloodline house <name>` | Rename your house |

Operators can use `/bloodline debug complete` to finish the newest quest and `/bloodline debug champion` to spawn a test champion.

## Testing multiplayer on one computer

`./gradlew runClient` and `./gradlew runClient2` start two clients with different names. Open a world to LAN in the first and join it from the second.

## Building

Requires Java 25.

```
./gradlew build
```

The mod jar ends up in `build/libs/`. It needs [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://modrinth.com/mod/fabric-api).
