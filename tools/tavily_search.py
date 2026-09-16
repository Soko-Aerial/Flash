#!/usr/bin/env python3
"""Tavily web-search helper for this repo (developer tool, not part of the product).

Why it exists: AGENTS.md §13 requires platform facts (Compose Desktop density, window APIs,
Android adaptive APIs, ...) to be checked against *current* sources instead of recalled from
training data, and the plan docs require the source URL to be recorded next to the fact.
This script is the offline-friendly way to do that from a shell.

Key resolution order (first hit wins):
  1. --key <value>            (command line)
  2. TAVILY_API_KEY           (environment variable)
  3. tools/.tavily_api_key    (local file, git-ignored — never commit)
  4. ~/.flash/tavily_api_key  (machine-local fallback)

Usage:
  python tools/tavily_search.py "Compose Multiplatform desktop LocalDensity system scale"
  python tools/tavily_search.py --depth basic --max 3 "Compose Desktop window minimum size"
  python tools/tavily_search.py --json "androidx material3 adaptive currentWindowAdaptiveInfo"

Dependencies: none (uses `tavily-python` when it is installed, otherwise the public REST API
through `urllib`). `pip install tavily-python` is optional.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
KEY_FILE = Path(__file__).resolve().parent / ".tavily_api_key"
HOME_KEY_FILE = Path.home() / ".flash" / "tavily_api_key"
ENDPOINT = "https://api.tavily.com/search"


def resolve_key(cli_key: str | None) -> str | None:
    if cli_key:
        return cli_key.strip()
    env = os.environ.get("TAVILY_API_KEY")
    if env and env.strip():
        return env.strip()
    for path in (KEY_FILE, HOME_KEY_FILE):
        try:
            if path.is_file():
                value = path.read_text(encoding="utf-8").strip()
                if value:
                    return value
        except OSError:
            continue
    return None


def search(query: str, depth: str, max_results: int, key: str, include_domains: list[str] | None,
           topic: str) -> dict:
    """Prefer the official client; fall back to plain HTTP so the script never needs pip."""
    try:
        from tavily import TavilyClient  # type: ignore
    except ImportError:
        payload = {
            "api_key": key,
            "query": query,
            "search_depth": depth,
            "max_results": max_results,
            "topic": topic,
        }
        if include_domains:
            payload["include_domains"] = include_domains
        request = urllib.request.Request(
            ENDPOINT,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            raise SystemExit(f"Tavily HTTP {error.code}: {body}") from error
        except urllib.error.URLError as error:
            raise SystemExit(f"Tavily request failed: {error}") from error

    client = TavilyClient(api_key=key)
    return client.search(
        query=query,
        search_depth=depth,
        max_results=max_results,
        topic=topic,
        include_domains=include_domains or None,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Search the web with Tavily (dev helper).")
    parser.add_argument("query", nargs="+", help="Search query; quote it if it has spaces.")
    parser.add_argument("--depth", default="advanced", choices=["basic", "advanced"])
    parser.add_argument("--max", type=int, default=5, dest="max_results")
    parser.add_argument("--topic", default="general", choices=["general", "news"])
    parser.add_argument("--domains", default=None,
                        help="Comma-separated domain allow-list (e.g. developer.android.com,jetbrains.com)")
    parser.add_argument("--key", default=None, help="API key (overrides env/file)")
    parser.add_argument("--json", action="store_true", help="Print the raw JSON response")
    args = parser.parse_args()

    key = resolve_key(args.key)
    if not key:
        print(
            "No Tavily API key found. Set TAVILY_API_KEY, or write it to tools/.tavily_api_key "
            "(git-ignored), or pass --key.",
            file=sys.stderr,
        )
        return 2

    query = " ".join(args.query)
    domains = [d.strip() for d in args.domains.split(",") if d.strip()] if args.domains else None
    result = search(query, args.depth, args.max_results, key, domains, args.topic)

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0

    answer = result.get("answer")
    if answer:
        print(f"ANSWER: {answer}\n")

    hits = result.get("results") or []
    if not hits:
        print("(no results)")
        return 0
    for index, hit in enumerate(hits, start=1):
        print(f"[{index}] {hit.get('title', '(untitled)')}")
        print(f"    {hit.get('url', '')}")
        content = (hit.get("content") or "").strip().replace("\n", " ")
        if content:
            print(f"    {content[:900]}")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
