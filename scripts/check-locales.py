#!/usr/bin/env python3
"""Gate every locale against the English strings.

For each `app/src/main/res/values-*/strings.xml`, demands:

- well-formed XML with no duplicate `name` attributes (a duplicate silently
  wins by position and the loser is never shown),
- every English key present, unless English marks it `translatable="false"`
  (currently only `about_repo_label`, a repository identifier rather than
  copy — German and Spanish leave it out on purpose),
- no keys English does not have (a misspelled key falls back to English at
  runtime with nothing visible to the user, which is exactly how a
  half-translated screen ships unnoticed),
- the identical format-specifier sequence per key (`%s`, `%d`, `%1$s`,
  `%%`, ...). The app formats with the English argument list, so a missing
  or reordered specifier crashes with MissingFormatArgumentException only
  when that screen is opened in that locale — the failure this check exists
  to move to CI.

Stdlib only, so the workflow needs nothing but a checkout and Python.

The build-time twin lives in app/build.gradle.kts (checkLocales, wired to
preBuild): same rules, same wording, reimplemented in Kotlin so a local
build does not depend on an interpreter. Keep the two in sync.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = REPO_ROOT / "app/src/main/res"
BASE = RES_DIR / "values/strings.xml"

SPEC = re.compile(r"%(?:\d+\$)?[a-zA-Z%]")
NAME = re.compile(r'<string\s[^>]*name="([^"]+)"')


def display(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def load(path: Path) -> tuple[dict[str, str], dict[str, bool], list[str], bool]:
    """Returns (texts, non_translatable, errors, usable) for a strings.xml file.

    `usable` is False when the file cannot be read or parsed: the caller then
    reports the errors as-is instead of also listing every key as missing.
    """
    errors: list[str] = []
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        return {}, {}, ["cannot read %s: %s" % (display(path), error)], False
    try:
        root = ET.fromstring(text)
    except ET.ParseError as error:
        return {}, {}, ["%s is not well-formed XML: %s" % (display(path), error)], False
    names = NAME.findall(text)
    seen: set[str] = set()
    for name in names:
        if name in seen:
            errors.append("%s: duplicate key %s" % (display(path), name))
        seen.add(name)
    texts: dict[str, str] = {}
    non_translatable: dict[str, bool] = {}
    for entry in root.iter("string"):
        name = entry.get("name")
        if name is None:
            continue
        texts[name] = "".join(entry.itertext())
        non_translatable[name] = entry.get("translatable") == "false"
    return texts, non_translatable, errors, True


def main() -> int:
    base_texts, base_locked, errors, usable = load(BASE)
    if not usable or errors:
        print("\n".join(errors))
        return 1
    locales = sorted(
        p for p in RES_DIR.glob("values-*/strings.xml") if p.parent.name != "values"
    )
    if not locales:
        print("no values-*/strings.xml found under %s" % RES_DIR)
        return 1
    failures = 0
    for path in locales:
        locale = path.parent.name.replace("values-", "")
        texts, _, locale_errors, usable = load(path)
        problems = list(locale_errors)
        if not usable:
            print("%s: %d problem(s)" % (locale, len(problems)))
            for problem in problems:
                print("  %s" % problem)
            failures += 1
            continue
        for key in base_texts:
            if base_locked.get(key):
                continue
            if key not in texts:
                problems.append("missing key %s" % key)
        for key in texts:
            if key not in base_texts:
                problems.append("extra key %s (not in English)" % key)
            elif SPEC.findall(texts[key]) != SPEC.findall(base_texts[key]):
                problems.append(
                    "placeholder mismatch in %s: %s != English %s"
                    % (key, SPEC.findall(texts[key]), SPEC.findall(base_texts[key]))
                )
        if problems:
            print("%s: %d problem(s)" % (locale, len(problems)))
            for problem in problems:
                print("  %s" % problem)
            failures += 1
        else:
            print("OK %s (%d keys)" % (locale, len(texts)))
    print("%d locale(s) checked, %d failing" % (len(locales), failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
