#!/usr/bin/env bash
# Prints the body of the "## [VERSION]" section of a Keep a Changelog file: the lines after that heading up to the next
# "## " heading or the end of the file, without leading and trailing blank lines. Trailing link reference definitions of
# the file's own "## [...]" headings ("[0.1.0]: https://...", the block at the end of a Keep a Changelog file) are
# dropped too. Every other line is kept, including link reference and footnote definitions inside the section.
# Prints nothing when the file or the section is missing or empty; the release workflow then uses generated notes.
#
# Usage: changelog-section.sh VERSION [CHANGELOG.md]
set -euo pipefail

version="${1:?usage: changelog-section.sh VERSION [CHANGELOG.md]}"
file="${2:-CHANGELOG.md}"
[ -f "$file" ] || exit 0

awk -v want="$version" '
  # True if line is "[label]: ..." and label is the label of a "## [label]" heading. Labels match case-insensitively,
  # as in Markdown: "[unreleased]: ..." is the link of "## [Unreleased]".
  function heading_link(line,    label) {
    if (line !~ /^\[[^]]+\]:/) return 0
    label = line
    sub(/^\[/, "", label)
    sub(/\]:.*$/, "", label)
    return (tolower(label) in headings)
  }
  # state: 0 before the section, 1 inside it, 2 after it. The whole file is read for the heading labels.
  /^## / {
    if (state == 1) state = 2
    label = $0
    if (sub(/^## \[/, "", label) && sub(/\].*$/, "", label)) {
      headings[tolower(label)] = 1
      if (state == 0 && label == want) state = 1
    }
    next
  }
  state == 1 { lines[++n] = $0 }
  END {
    first = 1
    while (first <= n && lines[first] ~ /^[[:space:]]*$/) first++
    last = n
    while (last >= first && (lines[last] ~ /^[[:space:]]*$/ || heading_link(lines[last]))) last--
    for (i = first; i <= last; i++) print lines[i]
  }
' "$file"
