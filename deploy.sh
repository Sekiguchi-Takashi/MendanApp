#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
TOKEN="$(git config --global github.token)"
GHUSER="Sekiguchi-Takashi"
REPO="MendanApp"
if [ ! -d .git ]; then git init -b main; fi
git remote remove origin 2>/dev/null || true
git remote add origin "https://${GHUSER}:${TOKEN}@github.com/${GHUSER}/${REPO}.git"
git add -A
if [ -f debug.keystore ]; then git add -f debug.keystore; fi
git commit -m "update"
git pull --rebase origin main
git push -u origin main
LATEST="$(curl -s -H "Authorization: token ${TOKEN}" "https://api.github.com/repos/${GHUSER}/${REPO}/releases/latest" | grep '"tag_name"' | head -1 | sed -E 's/.*"v?([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')"
if [ -z "$LATEST" ]; then NEXT="1.0.0"; else NEXT="$(echo "$LATEST" | awk -F. '{printf "%d.%d.%d", $1, $2, $3+1}')"; fi
git tag "v${NEXT}"
git push origin "v${NEXT}"
