#!/usr/bin/env python3
"""
Static checks that need no game:  python3 tools/check_text.py

- every JSON file under src/ parses (language file, loot tables, structures, pools, tags, ...)
- every text key the code asks for with Text.t("...") exists in the English language file
  (keys built at runtime, such as Text.t("trait." + id), are skipped)
- language values only use placeholders Minecraft understands (%s, %1$s, %%)

Exits with 1 if anything is wrong, so tools/check.sh stops.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
LANG = os.path.join(ROOT, "src/main/resources/assets/bloodline/lang/en_us.json")

problems = []

# 1. JSON files parse.
for path in glob.glob(os.path.join(ROOT, "src/**/*.json"), recursive=True) + glob.glob(os.path.join(ROOT, "src/**/*.mcmeta"), recursive=True):
	try:
		with open(path, encoding="utf-8") as f:
			json.load(f)
	except ValueError as e:
		problems.append(f"broken JSON: {os.path.relpath(path, ROOT)}: {e}")

# 2. Literal text keys exist.
with open(LANG, encoding="utf-8") as f:
	lang = json.load(f)
code = ""
for path in glob.glob(os.path.join(ROOT, "src/**/*.java"), recursive=True):
	with open(path, encoding="utf-8") as f:
		code += f.read()
used = set(re.findall(r'Text\.(?:t|has)\(\s*"([^"]+)"', code))
for key in sorted(used):
	if key.endswith(".") or key.endswith("_"):
		continue  # completed at runtime
	if "bloodline." + key not in lang:
		problems.append(f"missing text: bloodline.{key}")

# 3. Placeholders. Minecraft reads %s, %1$s and %%; any other % followed by a letter, or one at the very end,
# breaks the text. A % followed by a space ("+8% speed") shows as a plain percent sign.
for key, value in lang.items():
	stray = re.sub(r"%(\d+\$)?s|%%", "", value)
	if re.search(r"%(\d+\$)?[A-Za-z]|%$", stray):
		problems.append(f"broken placeholder in {key}: {value!r}")

for p in problems:
	print("  " + p)
print(f"Text check: {len(used)} keys used in code, {len(lang)} in the language file, {len(problems)} problem(s).")
sys.exit(1 if problems else 0)
