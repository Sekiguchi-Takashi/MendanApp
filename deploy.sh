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
if [ "${1:-}" = "notag" ]; then exit 0; fi
LATEST="$(git tag --list 'v*' | sort -V | tail -1)"
if [ -z "$LATEST" ]; then NEXT="1.0.0"; else NEXT="$(echo "${LATEST#v}" | awk -F. '{printf "%d.%d.%d", $1, $2, $3+1}')"; fi
git tag "v${NEXT}"
git push origin "v${NEXT}"
