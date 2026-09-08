#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
(cd "$here/.." && sbt -batch "load-test/writeClasspath" > /dev/null)
classpath=$(cat "$here/target/load-test-cp.txt")
java=${JAVA_HOME:+$JAVA_HOME/bin/java}
java=${java:-java}
ladder=${LADDER:-4000,6000,8000}
rung=${RUNG:-20}
port=8110

mkdir -p "$here/target"

variant() {
  local label=$1; shift
  port=$((port + 1))
  echo "== $label =="
  "$java" "$@" -cp "$classpath" com.matthewjones372.loadtest.LoadTestServer "$port" quiet \
    > "$here/target/jvm-$label-server.log" 2>&1 &
  local server=$!
  trap 'kill '"$server"' 2>/dev/null || true' EXIT
  local ready=
  for _ in $(seq 1 90); do
    curl -sf -o /dev/null "http://localhost:$port/people/1" && { ready=yes; break; }
    sleep 1
  done
  [ -n "$ready" ] || { echo "server did not start: $label" >&2; exit 1; }
  "$java" -Dfile.encoding=UTF-8 -cp "$classpath" com.matthewjones372.loadtest.RateSweep \
    "http://localhost:$port" "jvm-$label" "$ladder" "$rung" "$here/target/reports" \
    | tee "$here/target/jvm-$label.md"
  kill "$server" 2>/dev/null || true
  wait "$server" 2>/dev/null || true
  trap - EXIT
}

variant baseline
variant heap     -Xms2g -Xmx2g -XX:+AlwaysPreTouch
variant parallel -Xms2g -Xmx2g -XX:+AlwaysPreTouch -XX:+UseParallelGC
variant zgc      -Xms2g -Xmx2g -XX:+AlwaysPreTouch -XX:+UseZGC
