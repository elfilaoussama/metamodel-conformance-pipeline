#!/usr/bin/env python3
"""Reconstruct immutable pins for the paper's recovered 224-repository membership.

This script does NOT claim to recover the original ingestion SHAs. The historical
study recorded repository membership but omitted each clone's HEAD revision from
tracked exports. It therefore resolves the latest commit on each repository's
current canonical default branch at or before an explicit historical cutoff and
labels every result as reconstructed provenance.

The recovered selection itself is treated as an immutable study artifact: its Git
blob SHA-1 must match the historical tracked blob before any pinning begins.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_INPUT = Path("corpus/paper-224-selected.csv")
DEFAULT_OUTPUT = Path("corpus/paper-224-reconstructed-pins.csv")
DEFAULT_CUTOFF = "2026-07-29T12:09:33Z"
EXPECTED_SELECTION_BLOB_SHA1 = "d6818780078944b5b12063c780a480dc8c8686eb"
API_ROOT = "https://api.github.com"
PROVENANCE = "RECONSTRUCTED_SELECTION_CUTOFF"


def parse_cutoff(value: str) -> str:
    text = value.strip()
    if text.endswith("Z"):
        parsed = datetime.fromisoformat(text[:-1] + "+00:00")
    else:
        parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        raise argparse.ArgumentTypeError("cutoff must include a timezone")
    return parsed.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def git_blob_sha1(path: Path) -> str:
    data = path.read_bytes()
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def repository_coordinate(normalized: str) -> str:
    owner, separator, name = normalized.partition("__")
    if not separator or not owner or not name:
        raise ValueError(f"invalid historical repository key: {normalized!r}")
    return f"{owner}/{name}"


def api_get(path: str, token: str | None) -> object:
    request = urllib.request.Request(
        API_ROOT + path,
        headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "metamodel-conformance-pipeline-paper-corpus-recovery",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
    )
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)


def validate_selection(rows: list[dict[str, str]]) -> None:
    if len(rows) != 224:
        raise ValueError(f"expected 224 historical selections, found {len(rows)}")
    language_counts = Counter(row["lang"] for row in rows)
    expected = {"Java": 75, "Python": 75, "C++": 74}
    if dict(language_counts) != expected:
        raise ValueError(f"unexpected language counts: {dict(language_counts)} != {expected}")
    keys = [row["repo"] for row in rows]
    if len(keys) != len(set(keys)):
        raise ValueError("historical selection contains duplicate repository keys")
    for row in rows:
        if row["stratum"] not in {"small", "medium", "large"}:
            raise ValueError(f"invalid stratum for {row['repo']}: {row['stratum']}")
        int(row["typeCount"])
        repository_coordinate(row["repo"])


def resolve(row: dict[str, str], cutoff: str, token: str | None) -> dict[str, str]:
    requested = repository_coordinate(row["repo"])
    encoded_requested = "/".join(urllib.parse.quote(part, safe="") for part in requested.split("/", 1))
    base = {
        "repo": row["repo"],
        "requestedRepository": requested,
        "lang": row["lang"],
        "historicalTypeCount": row["typeCount"],
        "stratum": row["stratum"],
        "cutoff": cutoff,
        "provenance": PROVENANCE,
    }
    try:
        metadata = api_get(f"/repos/{encoded_requested}", token)
        assert isinstance(metadata, dict)
        canonical = str(metadata["full_name"])
        default_branch = str(metadata["default_branch"])
        encoded_canonical = "/".join(
            urllib.parse.quote(part, safe="") for part in canonical.split("/", 1)
        )
        query = urllib.parse.urlencode(
            {"sha": default_branch, "until": cutoff, "per_page": 1}
        )
        commits = api_get(f"/repos/{encoded_canonical}/commits?{query}", token)
        if not isinstance(commits, list) or not commits:
            return {
                **base,
                "canonicalRepository": canonical,
                "defaultBranch": default_branch,
                "commit": "",
                "commitDate": "",
                "status": "NO_COMMIT_AT_OR_BEFORE_CUTOFF",
                "error": "",
            }
        commit = commits[0]
        assert isinstance(commit, dict)
        commit_date = str(commit["commit"]["committer"]["date"])
        return {
            **base,
            "canonicalRepository": canonical,
            "defaultBranch": default_branch,
            "commit": str(commit["sha"]),
            "commitDate": commit_date,
            "status": "PINNED",
            "error": "",
        }
    except (urllib.error.HTTPError, urllib.error.URLError, KeyError, TypeError, AssertionError) as failure:
        if isinstance(failure, urllib.error.HTTPError):
            detail = f"HTTP {failure.code}"
        else:
            detail = failure.__class__.__name__
        return {
            **base,
            "canonicalRepository": "",
            "defaultBranch": "",
            "commit": "",
            "commitDate": "",
            "status": "UNRESOLVED",
            "error": detail,
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--cutoff", type=parse_cutoff, default=DEFAULT_CUTOFF)
    parser.add_argument("--token-env", default="GITHUB_TOKEN")
    parser.add_argument(
        "--validate-selection-only",
        action="store_true",
        help="Verify the recovered historical selection bytes and structure without GitHub API calls.",
    )
    parser.add_argument(
        "--allow-unauthenticated",
        action="store_true",
        help="Allow GitHub's low unauthenticated API rate limit; unsuitable for all 224 rows.",
    )
    parser.add_argument("--sleep-seconds", type=float, default=0.0)
    args = parser.parse_args()

    observed_selection_blob = git_blob_sha1(args.input)
    if observed_selection_blob != EXPECTED_SELECTION_BLOB_SHA1:
        print(
            "historical selection integrity failure: "
            f"git blob {observed_selection_blob} != expected {EXPECTED_SELECTION_BLOB_SHA1}",
            file=sys.stderr,
        )
        return 65

    with args.input.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    validate_selection(rows)
    print(f"historical selection blob verified: {observed_selection_blob}")
    print("historical selection structure verified: 224 repositories (Java=75, Python=75, C++=74)")

    if args.validate_selection_only:
        return 0

    token = os.environ.get(args.token_env)
    if not token and not args.allow_unauthenticated:
        print(
            f"{args.token_env} is required for a 224-repository pinning run; "
            "pass --allow-unauthenticated only for a small diagnostic run",
            file=sys.stderr,
        )
        return 64

    resolved: list[dict[str, str]] = []
    for index, row in enumerate(rows, start=1):
        result = resolve(row, args.cutoff, token)
        resolved.append(result)
        print(
            f"[{index:3d}/{len(rows)}] {result['requestedRepository']} "
            f"{result['status']} {result['commit'][:12]}",
            flush=True,
        )
        if args.sleep_seconds > 0:
            time.sleep(args.sleep_seconds)

    fieldnames = [
        "repo",
        "requestedRepository",
        "canonicalRepository",
        "lang",
        "historicalTypeCount",
        "stratum",
        "defaultBranch",
        "commit",
        "commitDate",
        "cutoff",
        "provenance",
        "status",
        "error",
    ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    with temporary.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(resolved)
    temporary.replace(args.output)

    statuses = Counter(row["status"] for row in resolved)
    print("status summary:", dict(sorted(statuses.items())))
    if statuses.get("PINNED", 0) != 224:
        print(
            "pinning is incomplete; do not use this manifest as the corrected empirical corpus",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
