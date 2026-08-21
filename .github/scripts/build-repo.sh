#!/usr/bin/env bash
# Builds the static dnf + apt repository that gets published to GitHub Pages, out of the .rpm/.deb this
# release already produced and signed. Users then get Studio updates from `dnf upgrade` / `apt upgrade`
# instead of downloading a file from the Releases page by hand.
#
#   build-repo.sh <artifacts-dir> <site-dir> <tag>
#
# The two formats are hosted very differently, and the reason is size: each package is ~240 MB while a Pages
# site has a ~1 GB soft limit.
#
#   dnf — METADATA ONLY. createrepo_c reads the .rpm to checksum it, then the .rpm is deleted again and
#         `--baseurl` points every location href at the GitHub Release download URL. Pages serves a few KB
#         and GitHub's release CDN serves the payload. dnf follows the xml:base without complaint.
#   apt — THE .deb IS HOSTED. APT resolves `Filename:` relative to the archive root and has no equivalent of
#         xml:base, so there is no way to offload it; the .deb goes in pool/ and costs its full ~240 MB.
#
# Only ever the LATEST release, both formats. This is an upgrade channel, not an archive — every older
# version stays downloadable as a GitHub Release asset. It is also what keeps the site an order of magnitude
# under the Pages limit: the deploy is a fresh artifact each time (see the `pages` job in ci.yml), so nothing
# accumulates the way it would on a gh-pages branch.
#
# Signing is the same key that already signed the packages themselves (see .github/SIGNING.md). What is
# added here is REPOSITORY-level signing — repodata/repomd.xml.asc for dnf, a clearsigned InRelease plus a
# detached Release.gpg for apt — which is what lets a client verify the *index* and not just the payload.
# With signing unconfigured the repo is still built, but the generated install snippets turn the checks off
# so that what is published stays self-consistent; that path is for dry runs, not for a real release.
set -euo pipefail

ARTIFACTS="${1:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
SITE="${2:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
TAG="${3:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"

REPO_SLUG="${GITHUB_REPOSITORY:-LiQiyeDev/botmaker-studio}"
RELEASE_URL="https://github.com/${REPO_SLUG}/releases/download/${TAG}"
# Pages serves <owner>.github.io/<repo> lowercased.
PAGES_URL="${PAGES_URL:-https://$(echo "${REPO_SLUG%%/*}" | tr '[:upper:]' '[:lower:]').github.io/${REPO_SLUG##*/}}"

shopt -s nullglob
rpms=("${ARTIFACTS}"/*.rpm)
debs=("${ARTIFACTS}"/*.deb)
[ ${#rpms[@]} -gt 0 ] || { echo "::error::no .rpm found in ${ARTIFACTS}"; exit 1; }
[ ${#debs[@]} -gt 0 ] || { echo "::error::no .deb found in ${ARTIFACTS}"; exit 1; }
RPM="${rpms[0]}"
DEB="${debs[0]}"

SIGNING=0
if [ "${BOTMAKER_SIGN:-0}" = "1" ] && [ -n "${GPG_KEY_ID:-}" ]; then
  SIGNING=1
else
  echo "::warning::signing not configured — publishing a repository nothing can verify."
fi

# gpg in batch/loopback mode, matching how ci.yml imports the key.
gpg_run() { gpg --batch --yes --pinentry-mode loopback --passphrase "${GPG_PASSPHRASE:-}" -u "${GPG_KEY_ID}" "$@"; }

mkdir -p "${SITE}/rpm" "${SITE}/deb/pool/main/b/botmaker-studio" "${SITE}/deb/dists/stable/main/binary-amd64"

# --- dnf ------------------------------------------------------------------------------------------------
# The .rpm is staged only so createrepo_c can hash it, then removed; --baseurl rewrites where clients fetch.
cp "${RPM}" "${SITE}/rpm/"
# --general-compress-type, NOT --compress-type: the latter only covers the *additional* metadata (comps,
# updateinfo) and would leave primary.xml on whatever the default is. gz is pinned because the check below
# globs for primary.xml.gz — and because every dnf can read it, zstd/zck being newer.
createrepo_c --general-compress-type gz --baseurl "${RELEASE_URL}/" "${SITE}/rpm"
rm -f "${SITE}/rpm/$(basename "${RPM}")"

# The silent failure mode is a createrepo_c whose --baseurl spelling differs: the metadata builds fine and
# every client then 404s on a package that was deleted a line ago. Prove the URL landed in primary.xml.
primary=("${SITE}"/rpm/repodata/*primary.xml.gz)
[ ${#primary[@]} -gt 0 ] || { echo "::error::createrepo_c produced no primary.xml.gz"; exit 1; }
gzip -cd "${primary[0]}" | grep -qF "${RELEASE_URL}/" || {
  echo "::error::primary.xml does not point at ${RELEASE_URL}/ — the --baseurl rewrite did not take."
  exit 1
}
echo "dnf metadata points at ${RELEASE_URL}/$(basename "${RPM}")"

if [ "${SIGNING}" = "1" ]; then
  gpg_run --detach-sign --armor -o "${SITE}/rpm/repodata/repomd.xml.asc" "${SITE}/rpm/repodata/repomd.xml"
fi

# --- apt ------------------------------------------------------------------------------------------------
cp "${DEB}" "${SITE}/deb/pool/main/b/botmaker-studio/"
(
  cd "${SITE}/deb"
  # Filename: in Packages is relative to this directory, which is why apt-ftparchive runs from here.
  apt-ftparchive packages pool > dists/stable/main/binary-amd64/Packages
  gzip -9cf dists/stable/main/binary-amd64/Packages > dists/stable/main/binary-amd64/Packages.gz
  cat > dists/stable/main/binary-amd64/Release <<EOF
Archive: stable
Component: main
Origin: BotMaker
Label: BotMaker Studio
Architecture: amd64
EOF
  # Hashes every file under dists/stable, so it must run after the two above — and must NOT write its
  # output there while doing so. Redirecting straight into dists/stable/Release makes apt-ftparchive walk
  # over its own partially-flushed output and hash it, putting a bogus, run-to-run-varying `Release` entry
  # inside the very file that then gets signed. Build it outside the tree and move it in.
  apt-ftparchive \
    -o APT::FTPArchive::Release::Origin=BotMaker \
    -o APT::FTPArchive::Release::Label="BotMaker Studio" \
    -o APT::FTPArchive::Release::Suite=stable \
    -o APT::FTPArchive::Release::Codename=stable \
    -o APT::FTPArchive::Release::Architectures=amd64 \
    -o APT::FTPArchive::Release::Components=main \
    -o APT::FTPArchive::Release::Description="BotMaker Studio release channel" \
    release dists/stable > "${TMPDIR:-/tmp}/Release.$$"
  mv "${TMPDIR:-/tmp}/Release.$$" dists/stable/Release
)
if [ "${SIGNING}" = "1" ]; then
  gpg_run --clearsign -o "${SITE}/deb/dists/stable/InRelease" "${SITE}/deb/dists/stable/Release"
  gpg_run --detach-sign --armor -o "${SITE}/deb/dists/stable/Release.gpg" "${SITE}/deb/dists/stable/Release"
fi

# --- the public key, and the two install snippets ---------------------------------------------------------
# `signed-by=` accepts an ASCII-armored key as long as the file is named .asc, so one export serves apt and
# rpm --import both; there is no second, dearmored copy to keep in step.
if [ "${SIGNING}" = "1" ]; then
  gpg --export --armor "${GPG_KEY_ID}" > "${SITE}/botmaker.asc"
  rpm_gpg=$'gpgcheck=1\nrepo_gpgcheck=1\ngpgkey='"${PAGES_URL}/botmaker.asc"
  apt_opts="[signed-by=/etc/apt/keyrings/botmaker.asc] "
else
  rpm_gpg=$'gpgcheck=0\nrepo_gpgcheck=0'
  apt_opts="[trusted=yes] "
fi

# --- the one-command installer -----------------------------------------------------------------------
# Copied, never generated: it is committed at packaging/linux/install.sh so that what gets reviewed is
# byte-for-byte what users pipe into a root shell. Its BASE_URL default is hardcoded rather than
# substituted here for the same reason — which makes drift possible, so it is checked instead of trusted.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="${SCRIPT_DIR}/../../packaging/linux/install.sh"
[ -f "${INSTALLER}" ] || { echo "::error::installer not found at ${INSTALLER}"; exit 1; }
grep -qF "BOTMAKER_REPO_URL:-${PAGES_URL}}" "${INSTALLER}" || {
  echo "::error::install.sh's BASE_URL default does not match ${PAGES_URL} — update packaging/linux/install.sh."
  exit 1
}
install -m 755 "${INSTALLER}" "${SITE}/install.sh"

cat > "${SITE}/botmaker-studio.repo" <<EOF
[botmaker-studio]
name=BotMaker Studio
baseurl=${PAGES_URL}/rpm
enabled=1
${rpm_gpg}
EOF

ONELINER="curl -fsSL ${PAGES_URL}/install.sh | sudo bash"

# No `rpm --import` here: with repo_gpgcheck=1 dnf fetches the key from the repo's own gpgkey= and offers
# to import it on the first metadata read, which an interactive user can simply accept. (install.sh DOES
# import it explicitly — piped into a root shell there is no terminal to answer that prompt on.)
DNF_SNIPPET="sudo curl -fsSL -o /etc/yum.repos.d/botmaker-studio.repo ${PAGES_URL}/botmaker-studio.repo
sudo dnf install botmaker-studio"

APT_SNIPPET="sudo install -d -m 755 /etc/apt/keyrings
sudo curl -fsSL -o /etc/apt/keyrings/botmaker.asc ${PAGES_URL}/botmaker.asc
echo \"deb ${apt_opts}${PAGES_URL}/deb stable main\" | sudo tee /etc/apt/sources.list.d/botmaker-studio.list
sudo apt-get update && sudo apt-get install botmaker-studio"

# --- landing page -----------------------------------------------------------------------------------------
# Deliberately one self-contained file with no assets: the whole point of this site is that it is metadata,
# and a stylesheet request would be one more thing to keep alive for a page people visit once.
cat > "${SITE}/index.html" <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>BotMaker Studio — package repository</title>
<style>
  :root { color-scheme: light dark; --fg: #1a1a1a; --bg: #ffffff; --muted: #5f6368; --line: #e0e0e0; --code-bg: #f5f5f5; }
  @media (prefers-color-scheme: dark) {
    :root { --fg: #e8e8e8; --bg: #16181c; --muted: #9aa0a6; --line: #2c2f36; --code-bg: #1f2228; }
  }
  body { margin: 0 auto; padding: 3rem 1.25rem 5rem; max-width: 46rem; color: var(--fg); background: var(--bg);
         font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  h1 { font-size: 1.6rem; margin: 0 0 .25rem; }
  h2 { font-size: 1.15rem; margin: 2.5rem 0 .5rem; padding-top: 1.25rem; border-top: 1px solid var(--line); }
  p.sub { color: var(--muted); margin: 0 0 2rem; }
  pre { background: var(--code-bg); border: 1px solid var(--line); border-radius: 6px;
        padding: .9rem 1rem; overflow-x: auto; font-size: .875rem; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  footer { margin-top: 3rem; color: var(--muted); font-size: .875rem; }
  a { color: inherit; }
</style>
</head>
<body>
<h1>BotMaker Studio</h1>
<p class="sub">dnf and apt repositories for <strong>${TAG}</strong>. Install once, then update with your
package manager like anything else on the system.</p>

<h2>Install</h2>
<pre><code>${ONELINER}</code></pre>
<p>Works on Fedora/RHEL and Debian/Ubuntu — it registers the signed repository below and installs from
it. <a href="install.sh">Read it first</a> if you would rather see what you are piping into a root shell;
it is short, and it is the same file attached to every release.</p>

<h2>Or do it by hand</h2>
<p>Fedora / RHEL:</p>
<pre><code>${DNF_SNIPPET}</code></pre>
<p>Debian / Ubuntu:</p>
<pre><code>${APT_SNIPPET}</code></pre>
<p>Later updates, either way: <code>sudo dnf upgrade botmaker-studio</code> or
<code>sudo apt-get update &amp;&amp; sudo apt-get install --only-upgrade botmaker-studio</code>.</p>

<h2>Other platforms</h2>
<p>Windows (<code>.msi</code>), the portable zip/tarball and the AppImage are on the
<a href="https://github.com/${REPO_SLUG}/releases">Releases</a> page. The AppImage updates itself from
inside Studio and needs no root.</p>

<footer>
<p>This repository carries the <strong>latest release only</strong> — it is an upgrade channel, not an
archive. Every previous version stays downloadable from Releases.</p>
<p>The <code>.rpm</code> itself is served from the GitHub release; only its metadata lives here. The
<code>.deb</code> is hosted here because apt cannot be pointed at another host.</p>
</footer>
</body>
</html>
EOF

echo "Site built at ${SITE} ($(du -sh "${SITE}" | cut -f1)), advertising ${TAG} at ${PAGES_URL}"
