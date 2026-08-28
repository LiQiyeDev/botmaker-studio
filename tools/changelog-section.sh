#!/usr/bin/env bash
#
# changelog-section.sh <version> [changelog-file] — print the `## [<version>]` section of CHANGELOG.md.
#
# ONE extractor, TWO readers in TWO repositories, which is the whole reason this is a file rather than an
# awk pasted in both places:
#
#   * the umbrella's release.sh calls it in the decide pass (check_changelog) to REFUSE a release whose
#     changelog does not describe it;
#   * this repo's own ci.yml calls it on a version tag to produce the body JReleaser publishes.
#
# A release whose notes are extracted by a different rule than the one that gated it can pass the gate and
# then publish something else. The script lives in the repository being released — the one both readers can
# reach — and release.sh reaches into the submodule for it rather than keeping a copy.
#
# Exit 1 with nothing on stdout when there is no such section; that is what the gate reads as a refusal.
set -euo pipefail

ver="${1:?usage: changelog-section.sh <version> [changelog-file]}"
file="${2:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/CHANGELOG.md}"
[[ -f "$file" ]] || { echo "changelog-section.sh: no such changelog: $file" >&2; exit 1; }

# The heading match is anchored on `## [<version>]` and deliberately ignores everything after it, so the
# date separator (— / - / nothing at all) is not part of the contract. The second awk trims blank lines off
# both ends without collapsing the ones in between.
body="$(awk -v ver="$ver" '
    /^## / { if (inside) exit; inside = ($0 ~ "^## \\[" ver "\\]"); next }
    inside { print }
  ' "$file" | awk 'NF || seen { seen = 1; line[++n] = $0; if (NF) last = n }
                   END { for (i = 1; i <= last; i++) print line[i] }')"
[[ -n "$body" ]] || exit 1
printf '%s\n' "$body"
