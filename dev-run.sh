#!/usr/bin/env bash
#
# dev-run.sh — (re)install the local BotMaker libraries, then launch the Studio against them.
#
# Why this exists: unlike the SDK, the Studio is *run*, not installed. Studio's committed pom pins
# `botmaker.shared.version` to the last released tag on purpose — so app-image/dist and JitPack builds
# resolve a *published* shared, not a snapshot that only exists on your machine. That means a plain
# `mvn javafx:run` loads the released shared and ignores your local changes.
#
# This wrapper first installs local shared + sdk into ~/.m2 (so bots resolve them), then overrides
# `botmaker.shared.version` for this run only (no pom edit) so `javafx:run` resolves the 0.0.0-SNAPSHOT.
# The committed pom stays untouched.
#
# Runs from either the umbrella root or botmaker-studio/ (paths are resolved from the script location; the
# umbrella root also carries a thin dev-run.sh wrapper that forwards here).
#
# Usage:
#   ./dev-run.sh                 # install (shared + sdk), then run the Studio
#   ./dev-run.sh --no-install    # skip install, just run
#   ./dev-run.sh -Dfoo=bar       # any unrecognised args are forwarded to javafx:run

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Resolve the Studio module dir whether this script is invoked from botmaker-studio/ or the umbrella root.
if [[ -f "$SCRIPT_DIR/pom.xml" && -d "$SCRIPT_DIR/src/main/java/com/botmaker/studio" ]]; then
  STUDIO_DIR="$SCRIPT_DIR"
elif [[ -d "$SCRIPT_DIR/botmaker-studio" ]]; then
  STUDIO_DIR="$SCRIPT_DIR/botmaker-studio"
else
  echo "error: could not locate the botmaker-studio module from $SCRIPT_DIR" >&2
  exit 1
fi
UMBRELLA="$(dirname "$STUDIO_DIR")"

# Flags consumed here; everything else is forwarded to javafx:run.
#   --no-install : skip the local install and just launch.
RUN_INSTALL=1
ARGS=()
for arg in "$@"; do
  case "$arg" in
    --no-install)     RUN_INSTALL=0 ;;
    *)                ARGS+=("$arg") ;;
  esac
done

if [[ "$RUN_INSTALL" == "1" ]]; then
  echo "==> installing local botmaker-shared + botmaker-sdk to ~/.m2"
  mvn -q -f "$UMBRELLA/botmaker-shared/pom.xml" install -DskipTests
  mvn -q -f "$UMBRELLA/botmaker-sdk/pom.xml" install -DskipTests
  echo
fi

echo "==> Studio -> local botmaker-shared:0.0.0-SNAPSHOT (override; committed pom untouched)"
mvn -f "$STUDIO_DIR/pom.xml" javafx:run -Dbotmaker.shared.version=0.0.0-SNAPSHOT "${ARGS[@]}"
