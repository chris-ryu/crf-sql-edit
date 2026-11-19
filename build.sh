#!/usr/bin/env bash
# macOS/Linux counterpart of build.ps1: compile src/CrfSqlEdit.java into dist/crf-sql-edit.jar (Java 7 target).
#   ./build.sh -l <folder with ClipReport*.jar>      (or set CRF_SQL_LIB)
# Needs a JDK 8-19 ($JAVA_HOME/bin/javac, else javac on PATH): the jar targets Java 7, which javac dropped in JDK 20.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
lib="${CRF_SQL_LIB:-}"
if [ "${1:-}" = "-l" ]; then lib="${2:-}"; shift 2; fi
[ -n "$lib" ] || { echo "usage: $0 -l <folder with ClipReport*.jar>" >&2; exit 2; }

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then bin="$JAVA_HOME/bin/"; else bin=""; fi
command -v "${bin}javac" > /dev/null || { echo "javac not found: set JAVA_HOME to a JDK" >&2; exit 2; }

shopt -s nullglob
jars=("$lib"/ClipReport*.jar)
[ ${#jars[@]} -gt 0 ] || { echo "no ClipReport*.jar found in '$lib'" >&2; exit 2; }
cp="$(IFS=:; echo "${jars[*]}")"

rm -rf "$here/build" && mkdir -p "$here/build" "$here/dist"
echo "javac -> $here/build (targeting Java 7 for broad runtime compatibility)"
"${bin}javac" -encoding UTF-8 -source 1.7 -target 1.7 -nowarn -cp "$cp" -d "$here/build" "$here/src/CrfSqlEdit.java"
"${bin}jar" cf "$here/dist/crf-sql-edit.jar" -C "$here/build" .
echo "built $here/dist/crf-sql-edit.jar"
