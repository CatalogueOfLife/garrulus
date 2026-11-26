#!/bin/sh
# Rebuilds common-words-<lang>.txt: the top 20,000 lower-case, letters-only words per language
# from hermitdave/FrequencyWords (content/2018/<lang>/<lang>_50k.txt, "word count" per line, by descending frequency).
# Usage: build-common-words.sh <outdir>
set -e
OUT=${1:-.}
for l in en de fr es pt; do
  curl -sSL "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/$l/${l}_50k.txt" \
    | awk '{print $1}' \
    | LC_ALL=en_US.UTF-8 grep -E '^[[:alpha:]]+$' \
    | awk '{ if ($0 == tolower($0)) print }' \
    | head -n 20000 > "$OUT/common-words-$l.txt"
done
