#!/usr/bin/env python3
"""Check shared/profile.schema.json against the example profiles in shared/fixtures/.

Every file in fixtures/valid/ must pass the schema, and every file in fixtures/invalid/ must fail it.
The schema itself must be valid JSON Schema draft 2020-12 and use only the keywords in ALLOWED_KEYWORDS,
so that small validators on every platform read it the same way.

The Android unit tests check the same fixtures against the app's own decoder (ProfileFixturesTest).

Needs the jsonschema package (MIT licence), which CI installs. Runs on Python 3.10 or later, because
jsonschema 4.26.0, the version CI installs, needs it.
Exit status: 0 when everything matches, 1 on any mismatch, 2 when the check can't run.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ALLOWED_KEYWORDS = {
    # Identifiers and annotations: they never change what passes.
    "$schema", "$comment", "title", "description", "default",
    # Validation.
    "type", "enum", "const", "minimum", "maximum", "pattern", "required",
    # Applicators.
    "properties", "additionalProperties", "oneOf", "$defs", "$ref",
}

# Keywords whose value maps names to subschemas, so the names themselves aren't keywords.
NAMED_SUBSCHEMAS = {"properties", "$defs"}
LIST_SUBSCHEMAS = {"oneOf"}
SINGLE_SUBSCHEMAS = {"additionalProperties"}


def find_unexpected_keywords(schema: object, where: str, problems: list[str]) -> None:
    if not isinstance(schema, dict):
        return  # booleans are valid schemas, e.g. "additionalProperties": false
    for key, value in schema.items():
        location = f"{where}/{key}"
        if key not in ALLOWED_KEYWORDS:
            problems.append(f"{location}: keyword {key!r} isn't on the core-keyword list")
            continue
        if key in NAMED_SUBSCHEMAS and isinstance(value, dict):
            for name, subschema in value.items():
                find_unexpected_keywords(subschema, f"{location}/{name}", problems)
        elif key in LIST_SUBSCHEMAS and isinstance(value, list):
            for index, subschema in enumerate(value):
                find_unexpected_keywords(subschema, f"{location}/{index}", problems)
        elif key in SINGLE_SUBSCHEMAS:
            find_unexpected_keywords(value, location, problems)


def load_json(path: Path) -> object:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--shared",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "shared",
        help="the shared/ folder (default: the one in this repository)",
    )
    args = parser.parse_args(argv)

    try:
        import jsonschema
    except ImportError:
        print("check_profile_schema: needs the jsonschema package (pip install jsonschema)", file=sys.stderr)
        return 2

    schema_path = args.shared / "profile.schema.json"
    valid_dir = args.shared / "fixtures" / "valid"
    invalid_dir = args.shared / "fixtures" / "invalid"

    try:
        schema = load_json(schema_path)
    except (OSError, ValueError) as error:
        print(f"check_profile_schema: can't read {schema_path}: {error}", file=sys.stderr)
        return 2

    problems: list[str] = []
    try:
        jsonschema.Draft202012Validator.check_schema(schema)
    except jsonschema.SchemaError as error:
        problems.append(f"{schema_path.name}: not a valid draft 2020-12 schema: {error.message}")
    find_unexpected_keywords(schema, "#", problems)
    if problems:
        for problem in problems:
            print(f"FAIL {problem}")
        return 1

    validator = jsonschema.Draft202012Validator(schema)
    valid_files = sorted(valid_dir.glob("*.json"))
    invalid_files = sorted(invalid_dir.glob("*.json"))
    if not valid_files or not invalid_files:
        print(f"check_profile_schema: expected fixtures in both {valid_dir} and {invalid_dir}", file=sys.stderr)
        return 2

    for path in valid_files:
        try:
            document = load_json(path)
        except ValueError as error:
            problems.append(f"valid/{path.name}: isn't valid JSON: {error}")
            continue
        error = jsonschema.exceptions.best_match(validator.iter_errors(document))
        if error is not None:
            where = "/".join(str(part) for part in error.absolute_path) or "(top level)"
            problems.append(f"valid/{path.name}: the schema refuses it at {where}: {error.message}")

    for path in invalid_files:
        try:
            document = load_json(path)
        except ValueError:
            continue  # a file that isn't JSON is refused by every reader
        if validator.is_valid(document):
            problems.append(f"invalid/{path.name}: the schema accepts it, but it should be refused")

    for problem in problems:
        print(f"FAIL {problem}")
    checked = len(valid_files) + len(invalid_files)
    if problems:
        print(f"{len(problems)} of {checked} fixtures don't match the schema")
        return 1
    print(f"OK: {len(valid_files)} valid and {len(invalid_files)} invalid fixtures match {schema_path.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
