#!/usr/bin/env bash
set -euo pipefail

# tools/build-release.sh — Build Linux release packages (.deb, .tar.gz) for Flash desktop.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT"

# Extract desktopAppVersion from desktop/build.gradle.kts
VERSION=$(grep -E 'val desktopAppVersion\s*=\s*"[^"]+"' desktop/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/' || echo "2.2.0")
if [ -z "$VERSION" ]; then
    VERSION="2.2.0"
fi

DIST_DIR="$REPO_ROOT/dist/$VERSION"
mkdir -p "$DIST_DIR"

echo "=================================================="
echo " Building Flash Linux Release: v$VERSION"
echo "=================================================="

# 1. Build .deb package and standalone distributable
echo "--> Running Gradle packaging tasks..."
./gradlew :desktop:packageDeb :desktop:createDistributable --console=plain

# 2. Collect .deb
DEB_SRC="$REPO_ROOT/desktop/build/compose/binaries/main/deb/flash_${VERSION}_amd64.deb"
if [ ! -f "$DEB_SRC" ]; then
    # Fallback in case package name casing varies
    DEB_SRC=$(find "$REPO_ROOT/desktop/build/compose/binaries/main/deb" -name "*.deb" | head -n 1)
fi

if [ -f "$DEB_SRC" ]; then
    cp -f "$DEB_SRC" "$DIST_DIR/flash_${VERSION}_amd64.deb"
    echo "--> Created Debian package: $DIST_DIR/flash_${VERSION}_amd64.deb"
else
    echo "ERROR: .deb file not found in desktop/build/compose/binaries/main/deb" >&2
    exit 1
fi

# 3. Create portable .tar.gz from createDistributable
APP_DIR="$REPO_ROOT/desktop/build/compose/binaries/main/app"
if [ -d "$APP_DIR/Flash" ]; then
    TAR_OUT="$DIST_DIR/flash-${VERSION}-linux-x64.tar.gz"
    echo "--> Creating portable archive: $TAR_OUT..."
    tar -czf "$TAR_OUT" -C "$APP_DIR" Flash
    echo "--> Created portable tarball: $TAR_OUT"
else
    echo "ERROR: Distributable directory $APP_DIR/Flash not found" >&2
    exit 1
fi

# 4. Generate SHA256SUMS
echo "--> Generating SHA256 checksums..."
cd "$DIST_DIR"
sha256sum "flash_${VERSION}_amd64.deb" "flash-${VERSION}-linux-x64.tar.gz" > SHA256SUMS
cat SHA256SUMS

echo "=================================================="
echo " Release build complete!"
echo " Artifacts located in: $DIST_DIR"
echo "=================================================="
