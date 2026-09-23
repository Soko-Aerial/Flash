"""Regenerates config/aboutlibraries/ (audit C1, ADR-043). Developer tool, not product code.

The AboutLibraries plugin runs with offlineMode = true so a build never depends on the network, which
means every licence TEXT must already be in config/aboutlibraries/licenses/. This script fetches those
texts from each project's own repository and writes them there, together with the library overrides
that attach the texts of NATIVE code (libwebrtc, Skia, SQLCipher, SQLite3MultipleCiphers) to the Maven
artifact that ships it. Those native payloads carry no licence data of their own.

Run it when a dependency changes licence or a native payload changes:

    python tools/licenses/fetch_license_texts.py

then review the diff like any other change. Every source URL is recorded in the generated JSON
("url") and in the tables below, so each text is traceable.
"""

import html
import json
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "config" / "aboutlibraries"
GH = "https://raw.githubusercontent.com/"

# WebRTC's own generated notice (tools_webrtc/libs/generate_licenses.py output) as shipped by the
# webrtc-sdk project, whose AAR Flash uses on Android. It was generated at m92. The sections below
# that are missing from it were added upstream by M125 (branch-heads/6422) and are fetched separately.
WEBRTC_MD = GH + "webrtc-sdk/android/master/Licenses/WEBRTC.md"
# Build-time tools in that notice, not linked into the shipped library.
WEBRTC_BUILD_ONLY = {"android_ndk", "android_sdk", "ijar", "nasm"}
WEBRTC_M125_EXTRA = [
    ("dav1d", "https://code.videolan.org/videolan/dav1d/-/raw/master/COPYING"),
    ("jsoncpp", GH + "open-source-parsers/jsoncpp/master/LICENSE"),
    ("cpu_features", GH + "google/cpu_features/main/LICENSE"),
    ("libunwind", GH + "llvm/llvm-project/main/libunwind/LICENSE.TXT"),
    ("jni_zero", GH + "chromium/chromium/main/third_party/jni_zero/LICENSE"),
    ("webrtc PATENTS", GH + "webrtc-sdk/webrtc/master/PATENTS"),
]

# Skia as built by JetBrains skia-pack for skiko (script/build.py: every skia_use_system_* = false, so
# these are compiled in). Over-inclusion is harmless; omission is not.
SKIA = [
    ("skia", GH + "google/skia/main/LICENSE"),
    ("expat", GH + "libexpat/libexpat/master/expat/COPYING"),
    ("freetype (FTL)", "https://gitlab.freedesktop.org/freetype/freetype/-/raw/master/docs/FTL.TXT"),
    ("harfbuzz", GH + "harfbuzz/harfbuzz/main/COPYING"),
    ("icu", GH + "unicode-org/icu/main/LICENSE"),
    ("libjpeg-turbo", GH + "libjpeg-turbo/libjpeg-turbo/main/LICENSE.md"),
    ("libjpeg-turbo (IJG)", GH + "libjpeg-turbo/libjpeg-turbo/main/README.ijg"),
    ("libpng", GH + "pnggroup/libpng/libpng16/LICENSE"),
    ("libwebp", GH + "webmproject/libwebp/main/COPYING"),
    ("libwebp PATENTS", GH + "webmproject/libwebp/main/PATENTS"),
    ("zlib", GH + "madler/zlib/master/LICENSE"),
    ("wuffs", GH + "google/wuffs/main/LICENSE"),
    ("D3D12MemoryAllocator", GH + "GPUOpen-LibrariesAndSDKs/D3D12MemoryAllocator/master/LICENSE.txt"),
]

# (hash, display name, source url). The hash is what libraries reference. Hex hashes are the ones the
# plugin derives from a POM that names a licence without an SPDX id; if a POM changes, the notices task
# fails on the missing text and this table is where to fix it.
SINGLE = [
    ("Apache-2.0", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"),
    ("45546b23f3f1354cdc022f8f8e4a7bb1", "SQLCipher License (BSD-style)", GH + "sqlcipher/sqlcipher-android/master/LICENSE"),
    ("73252b46f36df25ef51a7994de439aea", "Bouncy Castle Licence", GH + "bcgit/bc-java/main/LICENSE.md"),
    ("9dc0f4a991981fff819381cac34478fb", "FreeBSD License (JCodec)", GH + "jcodec/jcodec/master/LICENSE"),
    ("slf4j-mit", "MIT License (SLF4J)", GH + "qos-ch/slf4j/master/LICENSE.txt"),
    ("protobuf-bsd", "BSD 3-Clause License (Protocol Buffers)", GH + "protocolbuffers/protobuf/main/LICENSE"),
    ("sqlite-blessing", "SQLite (public domain)", GH + "sqlite/sqlite/master/LICENSE.md"),
    ("libtomcrypt", "LibTomCrypt (public domain / Unlicense)", GH + "libtom/libtomcrypt/develop/LICENSE"),
    ("sqlite3mc-mit", "MIT License (SQLite3 Multiple Ciphers)", GH + "utelle/SQLite3MultipleCiphers/main/LICENSE"),
    ("webrtc-java-notice", "webrtc-java NOTICE", GH + "devopvoid/webrtc-java/main/NOTICE"),
    # Apache-2.0 section 4(d): NOTICE files of redistributed works must travel with them. A scan of every
    # shipped jar/aar (2026-09-23) found exactly one, in jakarta.inject-api 2.0.1; pinned to that tag.
    ("jakarta-inject-notice", "Jakarta Dependency Injection NOTICE", GH + "jakartaee/inject/2.0.1/NOTICE.md"),
]


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "flash-license-fetch"})
    last = None
    for _ in range(4):
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                return r.read().decode("utf-8", errors="replace").replace("\r\n", "\n").strip("\n")
        except Exception as e:  # network hiccups are common; a missing text must still fail loudly
            last = e
    sys.exit(f"could not fetch {url}: {last}")


def webrtc_bundle() -> str:
    text = fetch(WEBRTC_MD)
    parts = []
    for m in re.finditer(r"^# (\S+)\n```\n(.*?)\n```", text, re.S | re.M):
        name, body = m.group(1), html.unescape(m.group(2))
        if name not in WEBRTC_BUILD_ONLY:
            parts.append((name, body))
    if len(parts) < 20:
        sys.exit(f"WEBRTC.md parse found only {len(parts)} sections; format changed?")
    for name, url in WEBRTC_M125_EXTRA:
        parts.append((name, fetch(url)))
    return join_bundle(
        "libwebrtc (M125) is compiled together with the components below. Source of the per-component "
        "list: WebRTC tools_webrtc/libs/generate_licenses.py.",
        parts,
    )


def join_bundle(intro: str, parts) -> str:
    out = [intro]
    for name, body in parts:
        out.append(f"\n----- {name} -----\n{body.strip()}")
    return "\n".join(out) + "\n"


def write_license(hash_: str, name: str, url: str, content: str) -> None:
    (OUT / "licenses" / f"{hash_}.json").write_text(
        json.dumps({"hash": hash_, "name": name, "url": url, "content": content}, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def write_override(file: str, unique_id: str, licenses, **extra) -> None:
    body = {"uniqueId": unique_id, "licenses": licenses, **extra}
    (OUT / "libraries" / f"{file}.json").write_text(json.dumps(body, indent=2) + "\n", encoding="utf-8")


def exact(coordinate: str) -> str:
    """A regex override merges only into a resolved library and is never added on its own, so a
    desktop-only payload cannot leak into the Android notices (and vice versa)."""
    return "^" + re.escape(coordinate) + "$::regex"


def main() -> None:
    (OUT / "licenses").mkdir(parents=True, exist_ok=True)
    (OUT / "libraries").mkdir(parents=True, exist_ok=True)

    for hash_, name, url in SINGLE:
        write_license(hash_, name, url, fetch(url))
    write_license("webrtc-native", "WebRTC native library and bundled components", WEBRTC_MD, webrtc_bundle())
    write_license(
        "skia-native", "Skia and bundled components (skiko)", GH + "JetBrains/skia-pack",
        join_bundle("Skia, as built into skiko by JetBrains skia-pack, with its bundled components:",
                    [(n, fetch(u)) for n, u in SKIA]),
    )
    # JNA is dual-licensed LGPL-2.1-or-later OR Apache-2.0; Flash uses it under Apache-2.0.
    write_license(
        "jna-election", "JNA licence election", GH + "java-native-access/jna/master/LICENSE",
        fetch(GH + "java-native-access/jna/master/LICENSE")
        + "\n\nFlash uses JNA under the Apache License 2.0 (the full text is listed under Apache License 2.0).",
    )
    # sqlite-jdbc's own jar ships LICENSE.zentus next to its Apache LICENSE.
    write_license("sqlite-jdbc-zentus", "sqlite-jdbc (Zentus) notice", GH + "xerial/sqlite-jdbc/master/LICENSE.zentus",
                  fetch(GH + "xerial/sqlite-jdbc/master/LICENSE.zentus"))

    write_override("webrtc-sdk-android", exact("io.github.webrtc-sdk:android"), ["webrtc-native"])
    write_override("webrtc-java", exact("dev.onvoid.webrtc:webrtc-java"), ["webrtc-native", "webrtc-java-notice"])
    write_override("skiko-runtime", "^org\\.jetbrains\\.skiko:skiko-awt-runtime-.*$::regex", ["skia-native"])
    write_override("sqlcipher-android", exact("net.zetetic:sqlcipher-android"), ["sqlite-blessing", "libtomcrypt"])
    write_override("sqlite-jdbc", exact("io.github.willena:sqlite-jdbc"),
                   ["sqlite-jdbc-zentus", "sqlite3mc-mit", "sqlite-blessing"])
    write_override("jna", "^net\\.java\\.dev\\.jna:jna(-platform)?$::regex", ["jna-election", "Apache-2.0"])
    write_override("slf4j", exact("org.slf4j:slf4j-api"), ["slf4j-mit"])
    write_override("jakarta-inject", exact("jakarta.inject:jakarta.inject-api"), ["jakarta-inject-notice"])
    write_override("datastore-protobuf", exact("androidx.datastore:datastore-preferences-external-protobuf"),
                   ["protobuf-bsd"])
    # The vendored fork is substituted as a composite build, so no POM is resolved for it and the
    # plugin never sees it. It ships in both apps.
    write_override(
        "webrtc-kmp-fork", "com.shepeliev:webrtc-kmp", ["Apache-2.0"],
        name="WebRTC KMP (modified fork)",
        artifactVersion="0.125.11-flash-1",
        description="Fork of shepeliev/webrtc-kmp via aschulz90/webrtc-kmp, modified by the Flash project. "
                    "See third_party/webrtc-kmp/MODIFICATIONS.md.",
        website="https://github.com/shepeliev/webrtc-kmp",
    )
    print("wrote", sorted(p.name for p in (OUT / "licenses").iterdir()))


if __name__ == "__main__":
    main()
