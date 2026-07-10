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

```bash
gpg --export --armor <KEY_ID> > KEYS      # commit this to the repo and attach to releases
```

Users then run, once:

```bash
sudo rpm --import KEYS         # Fedora/RHEL
# or, for apt-based verification of the .deb:  gpg --import KEYS
```
