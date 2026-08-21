# Package signing (one-time maintainer setup)

The release workflow GPG-signs the `.rpm`/`.deb` (and, if a key is present, the AppImage) under an anonymous
"LiQiyeDev" identity so Fedora/Debian stop warning that the package is unsigned. **This is optional** — until
the secrets below are set, packages ship unsigned and the pipeline still succeeds.

> Note: signing removes the *"untrusted/unsigned"* warning (after users import the public key). It does **not**
> remove the sudo/polkit password when installing a system-wide `.rpm`/`.deb` — that is required by the OS for
> any `/usr` package. The **AppImage** and **tarball** are the password-free install paths.

## 1. Generate a key (locally, once)

```bash
gpg --batch --gen-key <<EOF
Key-Type: RSA
Key-Length: 4096
Name-Real: LiQiyeDev
Name-Email: liqiyedev@users.noreply.github.com
Expire-Date: 0
Passphrase: <choose-a-passphrase>
%commit
EOF

# Find the key id (the long hex after "sec   rsa4096/"):
gpg --list-secret-keys --keyid-format long
```

## 2. Add three GitHub Actions secrets (repo → Settings → Secrets → Actions)

| Secret            | Value                                                                 |
|-------------------|-----------------------------------------------------------------------|
| `GPG_KEY_ID`      | the key id / email from step 1 (used as `%_gpg_name`)                 |
| `GPG_PRIVATE_KEY` | `gpg --export-secret-keys --armor <KEY_ID> \| base64 -w0`  (paste it) |
| `GPG_PASSPHRASE`  | the passphrase chosen in step 1                                       |

## 3. Publish the public key so users can trust it

Nothing to do: the `pages` job exports it for you. `.github/scripts/build-repo.sh` runs
`gpg --export --armor` and publishes the result as **`botmaker.asc`** at the root of the GitHub Pages site, so
the key is served from the same place as the repository metadata it verifies and can never drift out of step
with the key CI actually signed with. Users import it with the snippet on that page —
`sudo rpm --import …/botmaker.asc` for dnf, or dropping it in `/etc/apt/keyrings/` for apt (`signed-by=`
accepts an armored key as long as the file is named `.asc`, which is why there is only one export and not a
second dearmored copy).

To publish it by hand anyway (e.g. for someone verifying a Release asset without adding the repo):

```bash
gpg --export --armor <KEY_ID> > KEYS
```

## 4. What the same three secrets sign

| Signed | By | Verified with |
|--------|----|---------------|
| the `.rpm` / `.deb` payload | `.github/scripts/sign-packages.sh`, in the `package` job | `gpgcheck=1` / `dpkg-sig --verify` |
| `repodata/repomd.xml.asc` (dnf index) | `.github/scripts/build-repo.sh`, in the `pages` job | `repo_gpgcheck=1` |
| `dists/stable/InRelease` + `Release.gpg` (apt index) | same | `apt-get update` |

Package signing proves the file wasn't tampered with; **repository** signing proves the *index* wasn't — that
nobody swapped in a different, also-validly-signed version. Both matter once updates arrive over a repo rather
than by hand, so the `pages` job imports the key too (it's a separate runner from `package`).
