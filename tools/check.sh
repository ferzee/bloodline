#!/usr/bin/env bash
# Runs every automated check, in order, and stops at the first failure:  tools/check.sh
#
#   1. text and data files    (tools/check_text.py)
#   2. the mod builds         (./gradlew build)
#   3. server game tests      (./gradlew runGametest)
#   4. screen tests           (./gradlew runClientGametest; opens a game window; skip with --no-client)
#   5. the website builds     (python3 website/build.py)
#
# Logs go to build/check/. See docs/TESTING.md for what to do with the results.
set -uo pipefail

cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
CLIENT=1
[[ "${1:-}" == "--no-client" ]] && CLIENT=0
LOGS=build/check
mkdir -p "$LOGS"

step() { printf '\n== %s\n' "$1"; }
fail() { printf '\nFAILED: %s (log: %s)\n' "$1" "$2"; exit 1; }

step "1/5 Text and data files"
python3 tools/check_text.py || fail "text check" "the output above"

step "2/5 Build"
./gradlew build --console=plain > "$LOGS/build.log" 2>&1 || fail "build" "$LOGS/build.log"
ls build/libs/*.jar | grep -v sources

step "3/5 Server game tests"
./gradlew runGametest --console=plain > "$LOGS/gametest.log" 2>&1
RESULT=$(grep -E "required tests (passed|failed)" "$LOGS/gametest.log" | tail -1 | sed 's/.*(Minecraft) //')
echo "${RESULT:-no result found}"
grep -E "\(Minecraft\)    - " "$LOGS/gametest.log" | sed "s/.*(Minecraft)//"
grep -q "All .* required tests passed" "$LOGS/gametest.log" || fail "server game tests" "$LOGS/gametest.log"
# Errors that were logged without failing a test still count.
if grep -nE "\[Server thread/ERROR\]|Exception in server tick" "$LOGS/gametest.log" > "$LOGS/gametest-errors.txt"; then
	cat "$LOGS/gametest-errors.txt"
	fail "errors were logged during the game tests" "$LOGS/gametest.log"
fi

if [[ $CLIENT == 1 ]]; then
	step "4/5 Screen tests (a game window opens)"
	./gradlew runClientGametest --console=plain > "$LOGS/clientgametest.log" 2>&1 || fail "screen tests" "$LOGS/clientgametest.log"
	echo "Screenshots: $(ls build/clientgametest/screenshots | wc -l | tr -d ' ') in build/clientgametest/screenshots (look at them)"
else
	step "4/5 Screen tests: skipped (--no-client)"
fi

step "5/5 Website"
python3 website/build.py > "$LOGS/website.log" 2>&1 || fail "website build" "$LOGS/website.log"
cat "$LOGS/website.log"

printf '\nAll automated checks passed.\n'
