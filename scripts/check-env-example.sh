#!/usr/bin/env bash
# Verifies that an env catalogue file (default: .env.example) lists exactly the
# ${VAR} names used by the committed Spring config
# (src/main/resources/application*.yml) and the Compose files.
# Commented-out entries (`# VAR=`) count as listed. YAML comments are ignored
# when collecting the used names. Prints nothing and exits 0 when both sides
# agree.
#
# Usage: scripts/check-env-example.sh [env-file]   (default: .env.example)
set -euo pipefail
export LC_ALL=C   # identical collation for sort and comm

env_file="${1:-.env.example}"
case "$env_file" in /*) ;; *) env_file="$PWD/$env_file" ;; esac
cd "$(dirname "$0")/.."

if [ ! -r "$env_file" ]; then
  echo "Cannot read $env_file" >&2
  exit 2
fi

used=$(cat src/main/resources/application*.yml docker-compose*.yml \
  | sed -E 's/\$\$//g; s/(^|[[:space:]])#.*$//' \
  | grep -oE '\$\{[A-Z][A-Z0-9_]*' | cut -c3- | sort -u)
declared=$(grep -oE '^[[:space:]]*#?[[:space:]]*[A-Z][A-Z0-9_]*=' "$env_file" \
  | sed -E 's/^[[:space:]]*#?[[:space:]]*//; s/=$//' | sort -u)

missing=$(comm -23 <(printf '%s\n' "$used") <(printf '%s\n' "$declared"))
unused=$(comm -13 <(printf '%s\n' "$used") <(printf '%s\n' "$declared"))

status=0
if [ -n "$missing" ]; then
  echo "Used but missing from $(basename "$env_file"):"
  printf '%s\n' "$missing" | sed 's/^/  /'
  status=1
fi
if [ -n "$unused" ]; then
  echo "Listed in $(basename "$env_file") but used nowhere:"
  printf '%s\n' "$unused" | sed 's/^/  /'
  status=1
fi
exit "$status"
