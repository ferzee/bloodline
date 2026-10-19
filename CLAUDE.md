# Bloodline Chronicles

A Fabric mod for Minecraft 26.3 (Java 25) where you play a family across generations. The mod id is `bloodline`; the display name is Bloodline Chronicles.

## Testing: read docs/TESTING.md before calling any change done

- `tools/check.sh` runs every automated check: text, build, server game tests, screen tests and the website. Add `--no-client` when no screens changed.
- `docs/TESTING.md` says what to run for which kind of change, the rules each change must meet (a test for every bug fix, test-script rows for every feature, guides kept in sync) and the edge cases to think through.
- `docs/test-script.html` is the in-game checklist for the user. Add rows when features change, and republish it as the "Bloodline Test Script" artifact (https://claude.ai/artifact/7HqjQGxbbG22Gy4QeqLfY4).

## Working here

- Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew build`.
- Push only when the user says "push it".
- Player-facing text points to the Chronicle, never to commands. Commands belong only on the server owners' page and in tester material.
- Choices are shown as windows through `Screens.show`.
- Record every change players or server owners would notice in `CHANGELOG.md` under **Unreleased** (Added, Changed, Fixed, Removed). At a release it becomes the version's section, which is pasted on Modrinth and CurseForge.
- When players would notice a change, keep the guides in sync:
  - the website (`website/content/`)
  - the Player Guide document (Claude Docs `a169ea78-ff8d-4823-ab55-31c689dcafa3`)
  - the README
