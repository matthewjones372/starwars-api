# Load test

A load test for this API, written with
[Proofload](https://github.com/matthewjones372/proofload).

This module is deliberately **outside** the root aggregate, so `sbt test` and CI
never reach it: generating load takes minutes and nobody wants that in a unit
test run they did not ask for.

## Running it

```sh
sbt "load-test/test"
```

It is a `ProofloadSpec`, which is a `ZIOSpecDefault` that already carries what a
load spec needs. Extending it brings `TestAspect.sequential` and
`TestAspect.withLiveClock`; the second is the one to know about, because
zio-test hands a spec a `TestClock` and the readiness retry below would never
advance under one. The timeout is the spec's own, since the right one is the
length of what is being run.

The server runs in this JVM, started by `SWHttpServer.measuring` and held by the
test's `Scope`, so there is no second process, no classpath file and no shell
script. The comparison below runs two servers at once, each with a `Server`
layer of its own: one layer between them would be one port answering for both,
which is an A/B against itself.

`measured(name)(simulation)` runs a rung, writes its page under `reportsTo` and
appends its table to the GitHub Actions job summary; off Actions that call
writes nothing rather than throwing, so the same run works on a laptop. The
index over the directory is written once, after the last test, and is generated
from what is on disk rather than from a list kept here, so a report that stops
being written stops being linked. Every number is Proofload's own rendering of
the run.

`.github/workflows/load-test.yml` runs it weekly and on demand, and uploads the
directory.

Generator and target share this JVM's heap and this machine's cores. Both knees
in FINDINGS.md were measured with the target in a JVM of its own, which is the
truer arrangement and is why those numbers are the ones recorded.

## Test 0 — where the wall is, and whose it is

`LoadSpec` walks a ladder of rates against `GET /people/{id}`, the cheapest
handler the API has: a lookup in a `Map` held in memory. The latency of a map
lookup is not the point. The point is the three questions any capacity number
is worthless without.

**Did the load actually leave?** `left` against `asked`, plus `behind` and
`lost`. A generator that falls behind queues requests internally and reports the
wait as the server's latency — coordinated omission, the default bug in a load
generator. Proofload times every request from when it was *meant* to depart and
says on every run whether it kept its own schedule. A rung marked `behind: yes`
found **this tool's** ceiling on this machine, not the API's, and must not be
quoted as an API capacity.

**What did the target do at the load that reached it?** `svc` is service time,
measured from the departure that actually happened. It stays a true measurement
of the server even on a rung where the generator fell behind — of the server at
`left`, which is a smaller experiment than the one that was asked for, and a
real one.

**Where was the queue?** Little's law — `L = λW` — is arithmetic, not a model.
Proofload measures all three sides independently, so `L obs` against `L pred` is
a free consistency check, and `backlog` is the gap between the two predictions:
the queue the generator itself was holding, counted in requests.

### What the ladder found, and what came of it

`Middleware.debug` logs a line per request, so it sat on the path every response
took. The first sweep put it at three to four times this API's capacity, and it
is gone from the route stack now rather than behind a flag: a measurement that
argues for removing something and then leaves it switchable has not been acted
on. FINDINGS.md keeps both tables.

## Test 1 — pre-encoding the response

A JFR profile of what was left put UTF-8 encoding and zio-schema's case-class
encoder at roughly three quarters of the on-CPU samples, with no line of this
repository in the profile at all. The data is read from a resource at startup
and never changes, so that encoding is work this API does once and then repeats
on every request.

`SWHttpServer.measuring(preEncoded)` is the seam. The two servers run in one
JVM and the rounds alternate between them, so a drift in JIT state or in what
else the machine is doing lands on both. The rate is below the knee and each
round is repeated: the knee is the one rate where a queue is bistable, and the
JVM sweep in FINDINGS.md is the negative result that came of comparing there.

`PreEncodedSpec` in `http-api` is the other half. It runs both servers and
asserts the status, the content type and the body are identical, because an
optimisation that changes a response is a behaviour change wearing a
performance argument.

It is ahead in twelve rungs out of twelve, by a median of 13%, and that is all
it is: a profile says where the CPU goes when the CPU is the constraint, and at
the rates this arrangement can offer the server is not CPU-bound. Whether the
knee moved is unanswered, because above 4,000/s the generator loses ground for
both variants and the ladder is measuring the injector. FINDINGS.md has the
table.

## What these numbers are not

- **Not a number about your production hardware.** Here the generator and the
  server share this JVM and this machine's cores; the numbers in FINDINGS.md
  were taken with the server in a JVM of its own, which is the truer
  arrangement. Either way they compete for the same cores. Proofload's own
  [ceiling
  page](https://github.com/matthewjones372/proofload/blob/main/docs/what-it-costs.md)
  puts its HTTP step at *at least* 2,500 requests a second on four shared cores,
  and this API answers from memory. Expect to find the generator before the
  server, and read the `behind` column before quoting anything.
- **Not a measure of the data layer.** There isn't one to measure: `people_data
  .json` is parsed once at startup into a `Map`. Every rung here is framework
  overhead, JSON encoding, and whatever middleware is on the stack.
- **Not a regression gate.** Comparing a run against a stored baseline needs
  `Difference.notWorseThan`, which takes a `Share` and so carries a value-class
  hash in its JVM name — no Scala caller can name it. That waits on a
  Java-facing baselines facade in Proofload.
