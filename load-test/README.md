# Load test

A load test for this API, written with [Kestrel](https://github.com/matthewjones372/kestrel).

This module is deliberately **outside** the root aggregate, so `sbt test` and CI
never reach it. Kestrel is not on Maven Central yet, and this module resolves it
from the local maven repository; a clean checkout would fail to resolve, which
is not a failure anybody wants in a test run they did not ask for.

## Running it

```sh
# once, in a checkout of the kestrel repo
./gradlew :kestrel-scala:publishToMavenLocal :kestrel-zio-test:publishToMavenLocal \
  :kestrel-report-html:publishToMavenLocal :kestrel-report-github:publishToMavenLocal

# here
load-test/sweep.sh          # the rate ladder, twice
load-test/profile.sh 6000   # JFR on the server at one rate
```

Both scripts refresh the classpath themselves before running.

Into `load-test/target/` each writes the comparison table, a self-contained HTML
report per rung, and one markdown file. On GitHub Actions each rung's table is
also appended to the job summary; off Actions that call writes nothing.

## Test 0 — where the wall is, and whose it is

`RateSweep` walks a ladder of rates against `GET /people/{id}`, the cheapest
handler the API has: a lookup in a `Map` held in memory. The latency of a map
lookup is not the point. The point is the three questions any capacity number
is worthless without.

**Did the load actually leave?** `left` against `asked`, plus `behind` and
`lost`. A generator that falls behind queues requests internally and reports the
wait as the server's latency — coordinated omission, the default bug in a load
generator. Kestrel times every request from when it was *meant* to depart and
says on every run whether it kept its own schedule. A rung marked `behind: yes`
found **this tool's** ceiling on this machine, not the API's, and must not be
quoted as an API capacity.

**What did the target do at the load that reached it?** `svc` is service time,
measured from the departure that actually happened. It stays a true measurement
of the server even on a rung where the generator fell behind — of the server at
`left`, which is a smaller experiment than the one that was asked for, and a
real one.

**Where was the queue?** Little's law — `L = λW` — is arithmetic, not a model.
Kestrel measures all three sides independently, so `L obs` against `L pred` is
a free consistency check, and `backlog` is the gap between the two predictions:
the queue the generator itself was holding, counted in requests.

### The variable

`sweep.sh` runs the same ladder twice against the same code, changing one thing:
whether `Middleware.debug` is on the route stack. It logs a line per request, so
it sits on the path every response takes — which is what it is for while a human
is reading the log, and a cost a measurement has to be able to subtract. The
delta between the two tables is what that middleware costs.

`SWHttpServer.default` no longer carries it — that is what this measurement
changed. `withRequestLogging(true)` puts it back for a human reading the log,
and is what the noisy half of the sweep runs.

## What these numbers are not

- **Not a number about your production hardware.** The generator and the server
  run in separate JVMs — they must not share a heap — but they share this
  machine's cores. Kestrel's own [ceiling
  page](https://github.com/matthewjones372/kestrel/blob/main/docs/what-it-costs.md)
  puts its HTTP step at *at least* 2,500 requests a second on four shared cores,
  and this API answers from memory. Expect to find the generator before the
  server, and read the `behind` column before quoting anything.
- **Not a measure of the data layer.** There isn't one to measure: `people_data
  .json` is parsed once at startup into a `Map`. Every rung here is framework
  overhead, JSON encoding, and whatever middleware is on the stack.
- **Not a regression gate.** Comparing a run against a stored baseline needs
  `Difference.notWorseThan`, which takes a `Share` and so carries a value-class
  hash in its JVM name — no Scala caller can name it. That waits on a
  Java-facing baselines facade in Kestrel.
