#!/usr/bin/env bash
# macOS/Linux counterpart of crf-sql.ps1: run the crf-sql-edit tool against a .crf with your ClipReport engine jars.
#
#   ./crf-sql.sh -l <lib> list  report.crf
#   ./crf-sql.sh -l <lib> dump  report.crf ./sql_out
#   ./crf-sql.sh -l <lib> patch report.crf report.new.crf DS1=./sql_out/DS1.sql
#
# <lib> is the folder with the ClipReport*.jar files (or set CRF_SQL_LIB). Java: $JAVA_HOME/bin/java, else java on PATH.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
lib="${CRF_SQL_LIB:-}"
if [ "${1:-}" = "-l" ]; then lib="${2:-}"; shift 2; fi
[ -n "$lib" ] || { echo "usage: $0 -l <folder with ClipReport*.jar> <list|dump|patch|diff|set> ..." >&2; exit 2; }

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java="$JAVA_HOME/bin/java"
else
  java="$(command -v java || true)"
  [ -n "$java" ] || { echo "java not found: set JAVA_HOME or put java on PATH" >&2; exit 2; }
fi

tool="$here/dist/crf-sql-edit.jar"
[ -f "$tool" ] || { echo "missing $tool -- run ./build.sh -l '$lib' first" >&2; exit 2; }

# ClipReport 4 and 5 jars share package names, so the first on the classpath answers. patch needs the version 4
# writer (it stores SQL encrypted, as the Designer does); list, dump and diff work with either. Version 4 goes first.
cp="$tool"
v4=0
shopt -s nullglob
for j in "$lib"/ClipReport4*.jar; do cp="$cp:$j"; v4=1; done
others=("$lib"/ClipReport*.jar)
for j in "${others[@]}"; do case "$(basename "$j")" in ClipReport4*) ;; *) cp="$cp:$j" ;; esac; done
[ "$cp" != "$tool" ] || { echo "no ClipReport*.jar found in '$lib'" >&2; exit 2; }
if [ "$v4" = 0 ] && [ "${1:-}" = "patch" ]; then
  echo "WARNING: no ClipReport4.0-*.jar in '$lib': patch needs the version 4 engine and will stop (list, dump and diff are fine)." >&2
fi

exec "$java" -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$cp" CrfSqlEdit "$@"
