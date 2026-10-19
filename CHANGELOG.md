# Changelog

Everything that changes in Bloodline Chronicles, version by version. The newest version is at the top.

When a version is released, its section is the changelog to paste on Modrinth and CurseForge. Until then, changes are collected under **Unreleased**.

## Unreleased

Nothing yet.

---

## 0.1.0: the first release

*Minecraft 26.3 · Fabric Loader 0.19.5 or newer · Fabric API · Java 25*

**You don't play one hero. You play a family.** When your character dies, they're gone for good, and one of their children carries on. The heir inherits some of the parent's traits, the gear the family has carried for generations, and the Chronicle: a book that tells your family's story.

### Your family

- **A house of your own.** The first time you join, you found a house. You get a character name and a house name (such as Elske Ashford of House Ashford), two traits, the Chronicle and a map to the nearest village inn.
  - Your character's name replaces your account name in chat, the tab list and death messages.
- **Death is final.** Your character becomes an ancestor and gets an epithet based on how they lived. There are 21 epithets:
  - from their deeds, such as *the Slayer*, *the Delver*, *the Wanderer*, *the Angler*, *the Merchant* or *the Scarred*
  - *the Elder* for a long life, *the Quiet* for an uneventful one
  - *the Brief* for a life shorter than a Minecraft day
  - *the Devoted* for a sacrifice
- **Choose your heir.** Pick one of three children from a scroll with a portrait card for each, showing their traits. If you wait more than two minutes, the family chooses for you. Until you choose you are in mourning, shown as "Heir of House …".
- **25 traits:**
  - 14 boons, such as Stalwart (+2 hearts), Swift, Mighty, Long-Armed or Waterborn
  - 5 flaws, such as Frail and Clumsy
  - 2 quirks: Tall and Small
  - 4 legendary traits that belong to the whole house
- **How traits pass on.**
  - Boons and quirks pass to each child with a 50% chance each, and flaws with 35%.
  - Every child also gets one new trait of their own, up to 4 traits in total.
  - A Brief life passes nothing on, so dying on purpose makes the family weaker.
- **Heirlooms.**
  - Gear carried by two members of the family becomes a named heirloom. It glows and lists every bearer.
  - Heirlooms stay with you when you die.
  - Each new bearer raises its rank (up to 3), making it stronger: more attack damage for weapons, more efficiency for tools, more toughness for armor.

### The Chronicle

- An illustrated book with two pages side by side and bookmark tabs:
  - **Main Quest:** the oath
  - **Duties:** what the house expects of you
  - **You & the House:** your character, traits and members
  - **The Sacrifice:** whether you can give your life now
  - **Family Tree**
  - **History:** every deed the house has done
- It starts in your first hotbar slot, stays with you when you die and updates every time you open it.
- Players without the mod installed see the same content as a plain written book.

### Duties and renown

- **Founding goals** for every new house: raise its banner, sleep in a bed, slay 15 monsters, and talk to an innkeeper.
- **Duties after every death:**
  - **A blood debt:** slay what killed your ancestor, or defeat the player who did.
  - **A visit** to their resting place.
  - **Unfinished business**, one of 32 tasks chosen by how they lived.
- **Blood debts are inherited.**
  - Unpaid debts pass down the line and cost renown.
  - A house carries at most 3; when a newer one pushes out the oldest, that debt is forgotten.
- **How many to slay:** a strong or rare killer (a Warden, an Elder Guardian, an Evoker) only has to be slain once, a tough one (such as a Hoglin) twice, and anything else five times.
- **Renown** is the house's standing. You earn it from duties, inn jobs, crypts and the oath, and lose it for unpaid debts. It's never spent, it unlocks the next acts of the oath, and it's shown on the Chronicle's first page.

### The Founder's Oath

- The house's main quest: **four acts** spread over at least four generations, ending in a **legendary trait** every future heir is born with. Each act begins when a new generation takes over and the house has enough renown (0, 10, 25 and 45).
- **Oath of the Sword:**
  - Act I: slay monsters and forge the **Oathblade**, a sworn sword that is an heirloom from the start
  - Act II: carry it into the Nether
  - Act III: defeat the Captain of the Fortress
  - Act IV: slay the Wither with it
  - Reward: **Oathbound**
- **Oath of the Deep:** go below Y -32, find an Ancient City, defeat the Lost Delver, slay a Warden. Reward: **Deepborn**.
- **Oath of the Hearth:** trade, breed animals, fight off pillagers, defeat the Warlord at an outpost, win raids. Reward: **Beloved**.
- **Champions** at real structures: named bosses with a boss bar, bodyguards and a map to find them.
  - Three optional **preparations** weaken each one: no bodyguards, less health, or glowing and slow.
  - If you die, the champion waits for your heir.
- A house that fulfils its oath may swear the other two, until it holds all three legendary traits.

### The Sacrifice

- A family member who finished an act of the oath may give their life for the house. It must be at night under a full moon, at an ancestor's resting place (a founder may use the steward's home).
- What it brings:
  - every child keeps all of the parent's boons
  - the house gains 10 renown
  - no blood debt is created
- After three sacrifices the house becomes **Blood-Sworn**, a legendary trait for every member.

### Inns and jobs

- **Every village has an inn.** Stand near the bell and an innkeeper appears, and the inn gets a name such as *The Sleeping Griffin*.
- **Quest boards** post five jobs that change every Minecraft day. You can hold two at a time.
- **Eight kinds of job:**
  - bounties on named beasts: Rotgut the Hungry, Old Bones, The Broodmother and The Deserter
  - deliveries
  - rumours of places you haven't found
  - courier letters to other villages
  - lost satchels
  - hunts
  - expeditions to far-off biomes
  - laying the dead to rest in a crypt
- **Roadside inns** stand alone in the wild. Each is a timber hall with a stone lantern tower and a signal fire you can see from afar. It has guest rooms, a stable with a horse and a donkey, a kitchen garden and a cat by the fire, and its jobs pay half again as much.
- **The steward** looks after the **family vault**, shared by the whole house: 3 slots, 6 at 25 renown and 9 at 50. You hire one for 10 emeralds.
- **The Stolen Heirloom.** A destroyed heirloom isn't gone: thieves took it to a pillager outpost. Win it back, a little stronger than before.

### Barrow crypts

- The tombs of houses that died out long ago, half dungeon and half puzzle. Look for a walled graveyard with a dark mausoleum.
- **The puzzle:**
  1. Read the epitaphs on four sarcophagi.
  2. Light four braziers in the order the founder's children died. Light one out of turn and the dead rise.
  3. Find the founder's sigil in the farthest chest of the catacombs.
  4. Open the seal.
- **The catacombs** are laid out differently every time, with arrow traps, spawner halls, an ossuary with graves to brush, and a cave-in with a secret room.
- **The Barrow-Lord** rises from the tomb. Lay them to rest to take up the fallen house's last heirloom, already rank 2, and gain 6 renown.
- Every crypt belongs to a different fallen house, with its own names and its own answer. The tomb can't be broken into before it opens.
- **Crypts, roadside inns and shrines look for level, dry ground.** Before one is built, the ground under its whole footprint is measured. If it's a steep slope or mostly water, a few spots nearby are tried; if none is level enough, it isn't built there. A structure that does appear is set at the middle height of its ground, so it doesn't hang over a slope on a block of dirt.

### Wayside shrines

- **Small chapels along the way,** fairly common in plains, forests, taiga, snowy lands, savanna, desert and badlands, in three styles: stone and oak, cobblestone and spruce, sandstone and acacia. Each has a weathered statue, an altar with candles, a little courtyard with benches, and a barrel of supplies for travellers.
- **An ancestor's blessing.** Light the altar candles with flint and steel and your most recent ancestor watches over you for ten minutes, with a blessing that matches how they lived: Strength for a Slayer, Haste for a Delver, Speed for a Wanderer, and so on. Once a Minecraft day per family member.
- **Every shrine has its own name** (such as the Shrine of the Last Traveller). A house's first visit to each one earns 1 renown and a line in the Chronicle.

### Family events

- Every few days something happens to your house:
  - **sickness**, cured with a golden apple
  - **a rival house** declares a feud: fight their champion or pay tribute
  - **a wedding proposal**, whose children are born with an extra boon
  - **a long-lost cousin** with an old heirloom
  - **the restless dead** at an ancestor's grave
- A new house gets 7 days of peace first.
- Enemies that come to you grow stronger as your house's renown grows.

### Playing together

- Players can join one house, each playing their own branch of the family. The house shares its oath, renown, history, steward and heirlooms.
- On shared quests everyone's progress adds up.
- The head of the house invites players, removes members, renames the house and swears the oath.
- Killing another player leaves their heir a blood debt against you.

### Screens

- Choices open as illustrated **parchment scrolls** with a wax seal: choosing an heir, feuds, weddings, the Sacrifice, invitations.
- The Chronicle opens as an **illustrated book**.
- **Works without the mod:** the mod runs on the server, so players with normal Minecraft can join. They see the same content as Minecraft's own windows and a plain book.

### For server owners

- **Settings:** `config/bloodline.json` covers inheritance chances, the number of heirs, renown rewards and thresholds, the Sacrifice, the steward, inns, house size, heirloom ranks, champion strength, event timing and structure search distance.
- **Commands:** every player action also has a command (`/bloodline …`). Operators get `/bloodline admin reset` and debug commands for testing.
- **Peaceful:** champions, the restless dead and the Barrow-Lord wait until the difficulty is raised, instead of appearing and vanishing.
- **Other mods:** the mod works with machines that act as players, such as Create's deployers and ComputerCraft's turtles.
