#!/usr/bin/env python3
"""Writes the release file of a release: the JSON the app reads when GitHub's API gives no usable answer.

It has the shape of GitHub's `releases/latest` answer, cut to what the app reads:

    {"tag_name": "v0.1.0",
     "assets": [{"name": "Heavenfall-android.apk", "size": 123, "digest": "sha256:<64 hex>",
                 "browser_download_url": "https://github.com/<owner>/<name>/releases/download/v0.1.0/Heavenfall-android.apk"}]}

Usage: make_release_json.py --repository OWNER/NAME --tag vX.Y.Z --out Heavenfall-android-release.json FILE [FILE ...]
"""
import argparse
import hashlib
import json
import os
import re
import sys

TAG = re.compile(r"v\d+\.\d+\.\d+")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
NAME = re.compile(r"[A-Za-z0-9_.-]{1,96}")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def release(repository, tag, files):
    """The release's JSON as a dict: the files' names, sizes and SHA-256, and where the release serves them."""
    if not REPOSITORY.fullmatch(repository):
        raise ValueError(f"not a repository (owner/name): {repository}")
    if not TAG.fullmatch(tag):
        raise ValueError(f"not a version tag (vX.Y.Z): {tag}")
    if not files:
        raise ValueError("no files")
    assets = []
    for path in files:
        name = os.path.basename(path)
        if not NAME.fullmatch(name):
            raise ValueError(f"not a name a release can serve: {name}")
        if any(a["name"] == name for a in assets):
            raise ValueError(f"two files named {name}")
        assets.append({
            "name": name,
            "size": os.path.getsize(path),
            "digest": "sha256:" + sha256(path),
            "browser_download_url": f"https://github.com/{repository}/releases/download/{tag}/{name}",
        })
    return {"tag_name": tag, "assets": assets}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repository", required=True, help="owner/name of the repository that holds the release")
    ap.add_argument("--tag", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("files", nargs="+", help="the files the release carries")
    args = ap.parse_args(argv)
    try:
        data = release(args.repository, args.tag, args.files)
    except (ValueError, OSError) as e:
        print(f"make_release_json: {e}", file=sys.stderr)
        return 1
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
