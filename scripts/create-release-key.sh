#!/usr/bin/env bash
#
# Create the release signing key and upload it as repository secrets.
#
# This is not a build step and CI never runs it. It runs once, on the machine
# that will hold the key, because the key is the app's identity: Android refuses
# to install an update signed with a different one, so regenerating it is never
# a repair — it is a fork in the install base.
#
# The password is read without echo and handed to `keytool` through the
# environment (`-storepass:env`), not on the command line, where any process on
# the machine could read it from the argument list. It is then piped into
# `gh secret set`, which reads stdin. It never reaches this file, the shell
# history, or the transcript.
#
# Usage: scripts/create-release-key.sh [owner/repo]
#
# Run it from the repository root. The keystore lands beside you and is ignored
# by git (*.jks), which is the only thing standing between it and a commit.

set -euo pipefail

# Work from the repository root wherever this is invoked from, so the keystore
# lands beside the .gitignore that protects it rather than in whatever directory
# the terminal happened to open in. Launching it by absolute path is then safe.
cd "$(dirname "$0")/.." || { echo "Cannot locate the repository root." >&2; exit 1; }

repo="${1:-padreset/e-padreset}"
keystore="${KEYSTORE:-release.jks}"
alias_name="${RELEASE_KEY_ALIAS:-epadreset}"

if [ -e "$keystore" ]; then
    echo "Refusing to overwrite $keystore." >&2
    echo "A key that has already signed a published build must not be replaced." >&2
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "gh is not authenticated. Run: gh auth login" >&2
    exit 1
fi

# Nothing is echoed while the password is typed, which is the point and also why
# a terminal that cannot deliver it looks exactly like a working one. Both reads
# are checked: a read that fails means this shell has no terminal behind it, not
# that the password was empty. Saying which one happened saves the guessing.
echo "Type the password and press Enter. Nothing appears as you type."

if ! read -r -s -p "Keystore password: " store_pass; then
    echo
    echo "This terminal could not deliver a password." >&2
    echo "Open Git Bash from the Start menu and run the script there." >&2
    exit 1
fi
echo
if ! read -r -s -p "Repeat: " repeat; then
    echo
    echo "This terminal could not deliver a password." >&2
    echo "Open Git Bash from the Start menu and run the script there." >&2
    exit 1
fi
echo

if [ -z "$store_pass" ]; then
    echo "The password was empty. Nothing was created." >&2
    exit 1
fi

if [ "$store_pass" != "$repeat" ]; then
    echo "Passwords differ." >&2
    exit 1
fi
if [ "${#store_pass}" -lt 12 ]; then
    echo "Use at least 12 characters; 20 or more is better." >&2
    exit 1
fi

export STORE_PASS="$store_pass"
keytool -genkeypair -v \
    -keystore "$keystore" \
    -alias "$alias_name" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass:env STORE_PASS \
    -keypass:env STORE_PASS \
    -dname "CN=PadReset Release"

# Portable single-line base64: BSD and GNU both wrap, and both accept this.
base64 < "$keystore" | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64 -R "$repo"
printf '%s' "$store_pass" | gh secret set RELEASE_STORE_PASSWORD -R "$repo"
printf '%s' "$alias_name" | gh secret set RELEASE_KEY_ALIAS -R "$repo"
printf '%s' "$store_pass" | gh secret set RELEASE_KEY_PASSWORD -R "$repo"

echo
gh secret list -R "$repo"
echo
echo "Back up $keystore and its password separately, and outside this repository."
echo "Losing either means no later build can be installed over the last one."
