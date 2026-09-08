#!/usr/bin/env bash
# What the server spends its time on at a rate near the knee.
#
# A load test finds where the wall is. It does not say what the wall is made
# of — that needs a profiler on the server's own JVM while the load is on it.
# This holds one rate and takes a JFR recording across it.
#
#   sbt "load-test/writeClasspath" && load-test/profile.sh [rate] [seconds]
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
rate=${1:-6000}
seconds=${2:-60}
port=8090
classpath=$(cat "$here/target/load-test-cp.txt")
java=${JAVA_HOME:+$JAVA_HOME/bin/java}
java=${java:-java}
jcmd=${JAVA_HOME:+$JAVA_HOME/bin/jcmd}
jcmd=${jcmd:-jcmd}
jfr=${JAVA_HOME:+$JAVA_HOME/bin/jfr}
jfr=${jfr:-jfr}

mkdir -p "$here/target"
recording="$here/target/server.jfr"

"$java" -XX:StartFlightRecording=name=swapi,settings=profile,disk=true \
  -cp "$classpath" com.matthewjones372.loadtest.LoadTestServer "$port" quiet \
  > "$here/target/server-profile.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT

for _ in $(seq 1 90); do
  curl -sf -o /dev/null "http://localhost:$port/people/1" && break
  sleep 1
done

"$java" -Dfile.encoding=UTF-8 -cp "$classpath" com.matthewjones372.loadtest.RateSweep \
  "http://localhost:$port" "profile-${rate}" "$rate" "$seconds"

"$jcmd" "$server" JFR.dump name=swapi filename="$recording"
kill "$server" 2>/dev/null || true
trap - EXIT

echo
echo "== hottest methods (leaf frame of each execution sample) =="
"$jfr" print --events jdk.ExecutionSample --stack-depth 1 "$recording" \
  | grep -oE '^\s+[a-zA-Z0-9_.$]+\.[a-zA-Z0-9_$<>]+\(' \
  | sed 's/($//; s/[( ]//g' \
  | sort | uniq -c | sort -rn | head -25
