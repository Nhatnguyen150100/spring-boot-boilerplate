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

# grep exits 1 when nothing matches; under pipefail that would abort the
# script silently, so a no-match is turned into an empty list instead.
used=$(cat src/main/resources/application*.yml docker-compose*.yml \
  | sed -E 's/\$\$//g; s/(^|[[:space:]])#.*$//' \
  | { grep -oE '\$\{[A-Z][A-Z0-9_]*' || true; } | cut -c3- | sort -u)
declared=$({ grep -oE '^[[:space:]]*#?[[:space:]]*[A-Z][A-Z0-9_]*=' "$env_file" || true; } \
  | sed -E 's/^[[:space:]]*#?[[:space:]]*//; s/=$//' | sort -u)

# One name per line, no blank line for an empty list (comm would report it).
lines() { printf '%s\n' "$1" | sed '/^$/d'; }
missing=$(comm -23 <(lines "$used") <(lines "$declared"))
unused=$(comm -13 <(lines "$used") <(lines "$declared"))

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
