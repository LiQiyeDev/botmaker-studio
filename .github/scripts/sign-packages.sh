#!/usr/bin/env bash
# GPG-signs the built .rpm and .deb packages under the "LiQiyeDev" identity so Fedora/RHEL and Debian stop
# warning that the package is unsigned/untrusted (once the user imports the public key). It does NOT remove
# the polkit/sudo password on a system-wide install — that is required by the OS for any /usr package; the
# AppImage and tarball are the password-free channels.
#
# Expects the secret key already imported into the runner's gpg keyring and $GPG_KEY_ID exported. No-op (exit
# 0) when signing isn't configured, so unsigned dev builds still succeed.
set -euo pipefail

if [ "${BOTMAKER_SIGN:-0}" != "1" ] || [ -z "${GPG_KEY_ID:-}" ]; then
  echo "Signing not configured (BOTMAKER_SIGN/GPG_KEY_ID unset) — leaving packages unsigned."
  exit 0
fi

shopt -s nullglob

# --- RPM: rpmsign uses the %_gpg_name macro to select the key. ---
for rpm in target/dist/*.rpm; do
  echo "Signing $rpm"
  rpm --define "_gpg_name ${GPG_KEY_ID}" --addsign "$rpm"
done

# --- DEB: dpkg-sig attaches a detached signature (verified with `dpkg-sig --verify`). ---
if command -v dpkg-sig >/dev/null 2>&1; then
  for deb in target/dist/*.deb; do
    echo "Signing $deb"
    dpkg-sig -k "${GPG_KEY_ID}" --sign builder "$deb"
  done
else
  echo "dpkg-sig not installed — skipping .deb signing." >&2
fi
