#!/usr/bin/env bash
# Mirror the maintainer's working copies of the two endpoints into this repository,
# keep the version numbers in README.md in step with the file headers, and refuse to
# proceed if anything that must not be published shows up in the source.
#
# The source location is never hard-coded. Provide it as FOOTPRINT_SRC or as $1.
#
#   FOOTPRINT_SRC=/path/to/working/dir ./tools/sync-source.sh
#   ./tools/sync-source.sh /path/to/working/dir
#
# Expected layout under the source directory:
#   jiraDCappFootprint.groovy
#   confluenceDCappFootprint.groovy
#   tests/jiraDCappFootprint.tests.groovy
#   tests/confluenceDCappFootprint.tests.groovy
#   tests/parsecheck.groovy
#
# The script copies, verifies byte identity by SHA-256, and reports. It never pushes.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${1:-${FOOTPRINT_SRC:-}}"

if [ -z "$SRC" ]; then
    echo "error: source directory not given. Set FOOTPRINT_SRC or pass it as \$1." >&2
    exit 2
fi
if [ ! -d "$SRC" ]; then
    echo "error: source directory does not exist: $SRC" >&2
    exit 2
fi

# path-in-source : path-in-repo
FILES=(
    "jiraDCappFootprint.groovy:jira/jiraDCappFootprint.groovy"
    "confluenceDCappFootprint.groovy:confluence/confluenceDCappFootprint.groovy"
    "tests/jiraDCappFootprint.tests.groovy:jira/tests/jiraDCappFootprint.tests.groovy"
    "tests/confluenceDCappFootprint.tests.groovy:confluence/tests/confluenceDCappFootprint.tests.groovy"
    "tests/parsecheck.groovy:tools/parsecheck.groovy"
)

# ---------------------------------------------------------------- gate 1: secrets
# A publication step that trusts a past scan is not a check. Rescan every time,
# against the bytes about to be copied.
echo "== secret scan =="
LEAK=0
for pair in "${FILES[@]}"; do
    from="$SRC/${pair%%:*}"
    [ -f "$from" ] || { echo "error: missing source file: $from" >&2; exit 2; }
    hits=$(grep -nEi \
        'password[[:space:]]*=|passwd|secret[[:space:]]*=|api[_-]?key[[:space:]]*=|bearer[[:space:]]+[A-Za-z0-9]|BEGIN (RSA|OPENSSH|DSA|EC|PRIVATE)|xox[baprs]-|ATATT[A-Za-z0-9]|ghp_[A-Za-z0-9]|gho_[A-Za-z0-9]|AKIA[0-9A-Z]{16}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' \
        "$from" || true)
    if [ -n "$hits" ]; then
        echo "  POSSIBLE LEAK in ${pair%%:*}:"
        echo "$hits" | sed 's/^/    /'
        LEAK=1
    fi
done
if [ "$LEAK" -ne 0 ]; then
    echo "aborted: review the findings above before publishing." >&2
    exit 1
fi
echo "  clean"

# ------------------------------------------------------------ gate 2: internal refs
echo "== internal reference scan =="
INTERNAL=0
for pair in "${FILES[@]}"; do
    from="$SRC/${pair%%:*}"
    hits=$(grep -nEi 'plugin-dev|\.m2/repository|192\.168\.|10\.[0-9]+\.[0-9]+\.[0-9]+|[A-Za-z]:\\' "$from" || true)
    if [ -n "$hits" ]; then
        echo "  INTERNAL REFERENCE in ${pair%%:*}:"
        echo "$hits" | sed 's/^/    /'
        INTERNAL=1
    fi
done
[ "$INTERNAL" -eq 0 ] && echo "  clean"

# --------------------------------------------------------------------- copy + verify
echo "== copy and verify =="
for pair in "${FILES[@]}"; do
    from="$SRC/${pair%%:*}"
    to="$REPO/${pair##*:}"
    mkdir -p "$(dirname "$to")"
    cp "$from" "$to"
    a=$(sha256sum < "$from" | cut -d' ' -f1)
    b=$(sha256sum < "$to"   | cut -d' ' -f1)
    if [ "$a" != "$b" ]; then
        echo "  MISMATCH after copy: ${pair##*:}" >&2
        exit 1
    fi
    printf '  %s  %s\n' "${a:0:16}" "${pair##*:}"
done

# ------------------------------------------------------- doc / code version lockstep
# A stale version number in the README is how a document starts describing a state the
# code never had. The header of each script is the single source of truth.
echo "== version lockstep =="
# The version lives in exactly one place per script, the VERSION constant. The file
# header deliberately carries no number any more: a header and a constant are two
# places to change and one of them is always forgotten.
jver=$(grep -m1 -oE 'VERSION *= *"[0-9]+\.[0-9]+"' "$REPO/jira/jiraDCappFootprint.groovy" | grep -oE '[0-9]+\.[0-9]+')
cver=$(grep -m1 -oE 'VERSION *= *"[0-9]+\.[0-9]+"' "$REPO/confluence/confluenceDCappFootprint.groovy" | grep -oE '[0-9]+\.[0-9]+')
if [ -z "$jver" ] || [ -z "$cver" ]; then
    echo "  error: could not read a VERSION constant from one of the scripts." >&2
    exit 1
fi
echo "  jira=$jver  confluence=$cver"

python3 "$REPO/tools/readme-version-lockstep.py" "$REPO/README.md" "$jver" "$cver"

echo
echo "== result =="
git -C "$REPO" status --short
echo
echo "Nothing has been committed or pushed. Review the diff, then commit."
