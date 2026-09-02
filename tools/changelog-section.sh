#!/usr/bin/env bash
#
# changelog-section.sh [--allow-unreleased] <version> [changelog-file]
#   — print the `## [<version>]` section of CHANGELOG.md.
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
# --allow-unreleased: fall back to the `## [Unreleased]` section when there is none for <version>.
#
# THE TWO READERS SIT ON OPPOSITE SIDES OF A RENAME, WHICH IS WHY EXACTLY ONE OF THEM PASSES IT.
# A changelog is written under `## [Unreleased]` as the work happens, because the version number is not
# known then — it is what the release COMPUTES (release.sh bumps each module off its own latest tag). So:
#
#   * release.sh's gate runs BEFORE the rename and passes the flag. It is asking "is there prose for the
#     release I am about to cut?", and `[Unreleased]` is that prose.
#   * release.sh then stamps the heading to `## [<version>] — <date>` in that module's release commit, so
#     the TAGGED tree names the version.
#   * ci.yml runs on that tag, AFTER the rename, and does NOT pass the flag. It is asking "what was the
#     prose for the version that was cut?", and by then the section is named.
#
# Both read the same section; they name it differently only because time passed. Keeping CI strict is what
# makes a failed stamp a red job rather than a release whose notes silently say "Unreleased" — and it is why
# this is one flag rather than two scripts, which would let the two rules drift apart unnoticed.
#
# Exit 1 with nothing on stdout when there is no such section; that is what the gate reads as a refusal.
# An `## [Unreleased]` heading with an empty body is therefore still a refusal, which is correct: a heading
# is not a description.
set -euo pipefail

allow_unreleased=0
if [[ "${1:-}" == "--allow-unreleased" ]]; then allow_unreleased=1; shift; fi

ver="${1:?usage: changelog-section.sh [--allow-unreleased] <version> [changelog-file]}"
file="${2:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/CHANGELOG.md}"
[[ -f "$file" ]] || { echo "changelog-section.sh: no such changelog: $file" >&2; exit 1; }

# section <heading-key> — the body under `## [<heading-key>]`, blank lines trimmed off both ends.
#
# The heading match is anchored on `## [<key>]` and deliberately ignores everything after it, so the date
# separator (— / - / nothing at all) is not part of the contract. The second awk trims blank lines off both
# ends without collapsing the ones in between.
section() {
  awk -v ver="$1" '
      /^## / { if (inside) exit; inside = ($0 ~ "^## \\[" ver "\\]"); next }
      inside { print }
    ' "$file" | awk 'NF || seen { seen = 1; line[++n] = $0; if (NF) last = n }
                     END { for (i = 1; i <= last; i++) print line[i] }'
}

# The exact version always wins, so a changelog carrying BOTH a stamped section and a fresh `[Unreleased]`
# — the ordinary state one release after another — publishes the right one, and a resumed release re-reads
# what it already stamped rather than the next release's notes.
body="$(section "$ver")"
if [[ -z "$body" && $allow_unreleased -eq 1 ]]; then
  body="$(section 'Unreleased')"
fi
[[ -n "$body" ]] || exit 1
printf '%s\n' "$body"
