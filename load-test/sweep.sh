#!/usr/bin/env bash
# Test 0: the same rate ladder against the same API, twice — once with
# `Middleware.debug` on the response path and once without it.
#
# Both JVMs are started here rather than from sbt, so the generator and the
# target never share a heap. They do share this machine's cores, which is what
# a laptop run looks like and is a caveat on every number below.
#
#   sbt "load-test/writeClasspath" && load-test/sweep.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
cp_file="$here/target/load-test-cp.txt"
[ -f "$cp_file" ] || { echo "run: sbt \"load-test/writeClasspath\"" >&2; exit 1; }
classpath=$(cat "$cp_file")
java=${JAVA_HOME:+$JAVA_HOME/bin/java}
java=${java:-java}

sweep() {
  local label=$1 mode=$2 port=$3
  echo "== $label (port $port) =="
  "$java" -cp "$classpath" com.matthewjones372.loadtest.LoadTestServer "$port" "$mode" \
    > "$here/target/server-$label.log" 2>&1 &
  local server=$!
  trap 'kill '"$server"' 2>/dev/null || true' EXIT

  local ready=
  for _ in $(seq 1 90); do
    if curl -sf -o /dev/null "http://localhost:$port/people/1"; then ready=yes; break; fi
    sleep 1
  done
  [ -n "$ready" ] || { echo "server did not come up; see target/server-$label.log" >&2; exit 1; }

  "$java" -cp "$classpath" com.matthewjones372.loadtest.RateSweep \
    "http://localhost:$port" "$label" | tee "$here/target/sweep-$label.md"

  kill "$server" 2>/dev/null || true
  wait "$server" 2>/dev/null || true
  trap - EXIT
}

mkdir -p "$here/target"
sweep "logging-on" logging 8080
sweep "logging-off" quiet 8081
