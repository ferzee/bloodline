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
- **The Chronicle.** A book that writes itself: births, deaths, heirlooms, first steps into the Nether and the End, and bosses slain.

## Commands

| Command | What it does |
| --- | --- |
| `/bloodline` | Your family, current character and traits |
| `/bloodline info <player>` | Someone else's family |
| `/bloodline tree [player]` | The full family tree |
| `/bloodline chronicle` | A new copy of the family chronicle |
| `/bloodline house <name>` | Rename your house |

## Building

Requires Java 25.

```
./gradlew build
```

The mod jar ends up in `build/libs/`. It needs [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://modrinth.com/mod/fabric-api).
