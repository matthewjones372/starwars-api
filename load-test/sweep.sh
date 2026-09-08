#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
cp_file="$here/target/load-test-cp.txt"
(cd "$here/.." && sbt -batch "load-test/writeClasspath" > /dev/null)
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

  "$java" -Dfile.encoding=UTF-8 -cp "$classpath" com.matthewjones372.loadtest.RateSweep \
    "http://localhost:$port" "$label" | tee "$here/target/sweep-$label.md"

  kill "$server" 2>/dev/null || true
  wait "$server" 2>/dev/null || true
  trap - EXIT
}

mkdir -p "$here/target"
sweep "logging-on" logging 8080
sweep "logging-off" quiet 8081
