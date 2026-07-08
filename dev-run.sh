#!/usr/bin/env bash
#
# dev-run.sh — (re)install the local BotMaker libraries, then launch the Studio against them.
#
# Why this exists: unlike the SDK, the Studio is *run*, not installed, so `dev-install.sh` can't route it
# to your local shared the way it does for the SDK. Studio's committed pom pins `botmaker.shared.version`
# to the last released tag (e.g. v0.0.3) on purpose — so app-image/dist and JitPack builds resolve a
# *published* shared, not a snapshot that only exists on your machine. That means a plain `mvn javafx:run`
# loads the released shared and ignores your local changes.
#
# This wrapper first runs the umbrella ./dev-install.sh (local shared + sdk into ~/.m2) so you don't have
# to do it by hand, then overrides `botmaker.shared.version` for this run only (no pom edit, nothing to
# revert) so `javafx:run` resolves the 0.0.0-SNAPSHOT you just installed. The committed pom stays untouched.
#
# Runs from either the umbrella root or botmaker-studio/ (paths are resolved from the script location; the
# umbrella root also carries a thin dev-run.sh wrapper that forwards here).
#
# Usage:
#   ./dev-run.sh                 # dev-install (shared + sdk local-SNAPSHOT), then run the Studio
#   ./dev-run.sh --sdk           # install the local SDK as 0.0.0-SNAPSHOT (latest snapshot) instead
#   ./dev-run.sh --no-install    # skip dev-install, just run
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
#   --sdk / --with-sdk : install the local SDK as 0.0.0-SNAPSHOT (latest local snapshot, matching shared)
#                        rather than the default local-SNAPSHOT dev label.
#   --no-install       : skip the umbrella dev-install and just launch.
RUN_INSTALL=1
SDK_VERSION="local-SNAPSHOT"
ARGS=()
for arg in "$@"; do
  case "$arg" in
    --sdk|--with-sdk) SDK_VERSION="0.0.0-SNAPSHOT" ;;
    --no-install)     RUN_INSTALL=0 ;;
    *)                ARGS+=("$arg") ;;
  esac
done

if [[ "$RUN_INSTALL" == "1" ]]; then
  echo "==> dev-install: local botmaker-shared (0.0.0-SNAPSHOT) + botmaker-sdk ($SDK_VERSION)"
  DEV_SDK_VERSION="$SDK_VERSION" "$UMBRELLA/dev-install.sh"
  echo
fi

echo "==> Studio -> local botmaker-shared:0.0.0-SNAPSHOT (override; committed pom untouched)"
mvn -f "$STUDIO_DIR/pom.xml" javafx:run -Dbotmaker.shared.version=0.0.0-SNAPSHOT "${ARGS[@]}"
