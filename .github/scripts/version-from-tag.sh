#!/usr/bin/env bash
# Derives the Android versionName and versionCode of a release from its git tag and prints them as step outputs:
#   name=<versionName>   code=<versionCode>   prerelease=<true|false>
#
# Accepted tags (semver without leading zeros):
#   vMAJOR.MINOR.PATCH                       final release, e.g. v1.4.2
#   vMAJOR.MINOR.PATCH-alpha.N / -beta.N / -rc.N   pre-release, e.g. v1.4.2-rc.1
#
#   versionName = the tag without the leading "v"
#   versionCode = MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + S, where
#                 S = N for alpha.N, 30 + N for beta.N, 60 + N for rc.N, and 99 for the final release
#
# Bounds: MINOR, PATCH <= 99; N <= 29; MAJOR <= 2099 (versionCode stays below Google Play's 2100000000).
# Within these bounds versionCode follows semver precedence, so every later version updates the installed one:
#   1.2.2 (1020299) < 1.2.3-alpha.0 (1020300) < 1.2.3-beta.1 (1020331) < 1.2.3-rc.2 (1020362) < 1.2.3 (1020399)
# The result depends only on the tag, so a re-run for the same tag gives the same versionCode.
#
# Usage: version-from-tag.sh TAG >> "$GITHUB_OUTPUT"   (errors go to stderr, the exit status is 1)
set -euo pipefail

tag="${1:?usage: version-from-tag.sh TAG}"

fail() {
  echo "::error::Tag '$tag': $1" >&2
  exit 1
}

re='^v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(-(alpha|beta|rc)\.(0|[1-9][0-9]?))?$'
if [[ ! "$tag" =~ $re ]]; then
  fail "expected vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-{alpha|beta|rc}.N without leading zeros (MINOR, PATCH <= 99)"
fi
major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))
kind="${BASH_REMATCH[5]}"
n="${BASH_REMATCH[6]:-0}"
n=$((10#$n))

((major <= 2099)) || fail "MAJOR must be <= 2099"
((n <= 29)) || fail "the pre-release number must be <= 29"

case "$kind" in
  alpha) suffix=$n ;;
  beta) suffix=$((30 + n)) ;;
  rc) suffix=$((60 + n)) ;;
  *) suffix=99 ;;
esac
code=$((major * 1000000 + minor * 10000 + patch * 100 + suffix))
((code >= 1)) || fail "versionCode must be positive"

echo "name=${tag#v}"
echo "code=$code"
if [ -n "$kind" ]; then echo "prerelease=true"; else echo "prerelease=false"; fi
