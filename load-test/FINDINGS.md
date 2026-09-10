# Test 0 — where the wall is, and whose it is

Run on 4 cores / 15 GB, JDK 25, generator and target in separate JVMs on the
same machine. 60s warm-up discarded, 20s a rung. `GET /people/{id}`, which is a
lookup in a `Map` held in memory.

A record of what was measured and when, so the commands below are named as they
were run: `sweep.sh`, `profile.sh` and `jvm-sweep.sh` were shell scripts that
`LoadSpec` has since replaced, and they are not in the tree any more.

## The headline

**`Middleware.debug` costs this API between three and four times its capacity.**

| | knee | safe rate | at 4,000/s |
|---|---|---|---|
| as shipped (`Middleware.debug` on) | 2,000–4,000/s | ~2,000/s | 25,559us, 120 in flight |
| with it off | 8,000–12,000/s | ~6,000/s | 774us, 4.5 in flight |

Same code, same machine, one line of difference: 33× the service time at
4,000 requests a second.

## With `Middleware.debug` — as shipped

| asked/s | left | reqs | failed | svc p50 | svc p99 | behind | lost | L obs | L pred |
|--------:|-----:|-----:|-------:|--------:|--------:|:-------|:-----|------:|-------:|
| 1000 | 1000/s | 20000 | 0 | 819us | 16908us | yes | yes | 1.2 | 1.6 |
| 2000 | 2000/s | 40000 | 0 | 877us | 6160us | yes | no | 3.3 | 2.4 |
| 4000 | 4000/s | 80000 | 0 | 25559us | 150995us | no | yes | 120.5 | 151.8 |
| 6000 | 6000/s | 120000 | 60460 | 767558us | 16844325us | yes | yes | 11049.9 | 22127.1 |
| 8000 | 7619/s | 160000 | 84297 | 1954546us | 13421773us | no | yes | 15511.5 | 25215.0 |
| 12000 | 11429/s | 240000 | 178402 | 198us | 13757317us | no | yes | 15987.5 | 24293.8 |
| 16000 | 15238/s | 320000 | 284565 | 126us | 21340619us | no | yes | 28054.9 | 42963.4 |

## Without it

| asked/s | left | reqs | failed | svc p50 | svc p99 | behind | lost | L obs | L pred |
|--------:|-----:|-----:|-------:|--------:|--------:|:-------|:-----|------:|-------:|
| 1000 | 1000/s | 20000 | 0 | 664us | 2327us | yes | no | 1.0 | 0.7 |
| 2000 | 2000/s | 40000 | 0 | 602us | 1630us | yes | no | 1.3 | 1.3 |
| 4000 | 4000/s | 80000 | 0 | 774us | 6291us | yes | yes | 4.5 | 3.9 |
| 6000 | 5714/s | 120000 | 0 | 1696us | 31850us | yes | yes | 28.8 | 18.9 |
| 8000 | 7619/s | 160000 | 0 | 9241us | 42729us | yes | yes | 120.5 | 89.5 |
| 12000 | 11429/s | 240000 | 142537 | 109576us | 16642998us | yes | yes | 17410.1 | 25766.9 |
| 16000 | 15238/s | 320000 | 235772 | 319us | 15367930us | yes | yes | 18029.7 | 27758.6 |

## Reading it

**The API collapses rather than degrades.** With the middleware on, 2,000 a
second is 877us and three requests in flight; 4,000 is 25.6ms and a hundred and
twenty. The rate still leaves in full and nothing fails — the queue is inside
the server, and Little's law says so independently: 4,000 x 25.6ms = 102
predicted against 120.5 observed. There is no gentle slope between those two
rungs, which is what a capacity plan has to be built around.

**A tax at low load is a cliff at high load.** Measured at 1,500 a second, the
debug middleware looks like a 16% overhead — an easy thing to leave on. It is
the same middleware at 4,000, where it is the difference between 774us and
25.6ms. This is the argument for sweeping to saturation rather than measuring
at one comfortable rate: the cost that matters is the headroom it consumes, and
that is invisible until the headroom runs out.

**At the knee it is the server, not the generator.** At 4,000 a second with the
middleware on, `left` is the full 4,000 and nothing failed: the generator
delivered what was asked and the latency is the target's. Above 12,000 both
variants cap near 15,238/s, which is the generator's own ceiling on this box,
and those rungs say nothing about the API.

**`behind: yes` on the healthy rungs is not a warning.** Proofload asks whether
the generator's p99 lateness is larger than the precision it quotes the target's
p99 to — 0.78% of it. At sub-millisecond latencies that trips almost always;
Proofload's own ceiling page reports `yes` at every rate but one for the same
reason. `lostGround` and `left` are the columns that carry the attribution.

## What this does not say

- **Not a number for dedicated hardware.** The generator competes with the
  server for the same four cores, so both knees would move on a machine where
  the API had them to itself.
- **Not an attribution for the failures above 6,000/s.** Server overload and
  ephemeral-port exhaustion look identical in this table. Proofload records the
  cause and this sweep does not read it yet.
- **Not a claim about the sorted or graph endpoints.** This is the cheapest
  handler in the API. `sortBy` and `path-to` do real work per request and get
  their own tests.

## Next

1. Read failure causes into the table, so a `2,000` that fails is distinguished
   from a socket that ran out.
2. Refine the knee: 2,000–4,000 with the middleware on, 8,000–12,000 without.
   `sustainable()` bisects for exactly this and leaves the curve behind.
3. Then the differential tests — `sortBy=name:ASC` against `sortBy=films:ASC`,
   and `path-to` — run with the middleware off, since leaving it on would make
   every one of them a measurement of the logger.

# After the middleware — what the server actually spends its time on

`load-test/profile.sh 6000 60`: one rate held for a minute, JFR at `settings=profile`
on the server's own JVM. The run itself was healthy — 5,902/s delivered, 360,000
requests, nothing failed, 1,434us p50, 13.3 in flight against 14.9 predicted,
Little's law agreeing.

Leaf frame of each execution sample, grouped:

| what | samples | frames |
|---|---:|---|
| **JSON serialization** | ~286 | `UTF_8$Encoder.encodeBufferLoop` 105, `zio.json…unsafeEncode` 86+12, `zio.schema.codec.JsonCodec…caseClassEncoder` 83 |
| ZIO `Chunk` allocation | ~85 | `ClassTag$.apply` 58, `Chunk.isEmpty` 27 |
| zio-http text codec | ~54 | `RichTextCodec.loop` 21, `.transform` 19, `.string` 14 |
| Netty | ~48 | `ReferenceCountUtil.touch` 19, `writeAndFlush` 15, `DefaultHeaders.<init>` 14 |

**Turning a `Character` into bytes is the bottleneck, by roughly three to one
over anything else.** Nothing else is close, and the `Map` lookup this endpoint
exists to do does not appear in the profile at all — neither does any other line
of this repository. What is left after the logging middleware is the framework's
response path.

The `ClassTag$.apply` at number four is not a re-derivation bug in this code, and
was worth checking rather than assuming: its callers are `Chunk$.fromArray`,
`Chunk$Arr.<init>` and `Chunk$.fromByteBuffer`, which is zio-http allocating its
own chunks.

## What that suggests

**Pre-encode the responses.** The data is read from a resource once at startup
and never changes: `orderedPeople` and `peopleById` are immutable for the life of
the process. Encoding each `Character` to bytes once and serving those bytes
would remove most of the top three rows rather than making them faster. It is the
one change here with a large ceiling, and it is available precisely because this
API has no writes.

**Payload size is the lever underneath it.** A person carries `homeworld` and
four collections of URLs — `films`, `species`, `vehicles`, `starships`. Every one
is a string to encode on every response, and this clone serves none of the
resources they point at.

**Do not look for a fix in this repository's own code.** There is nothing on the
hot path to optimise; the cost is zio-schema, zio-json and the encoder beneath
them. The remaining choices are architectural — pre-encoded bytes, a smaller
payload, or accepting ~6,000/s as what this stack costs on four shared cores.

# JVM flags: a negative result

`load-test/jvm-sweep.sh` runs the same ladder against four server JVMs: stock,
`-Xms2g -Xmx2g -XX:+AlwaysPreTouch`, that plus `-XX:+UseParallelGC`, and that
plus `-XX:+UseZGC`. It was run twice.

Service time p50 at 8,000 a second:

| variant | run 1 | run 2 |
|---|---:|---:|
| stock | 157,286us | 1,720us |
| fixed heap | 5,964us | 5,734us |
| ParallelGC | 2,327us | 2,540us |
| ZGC | 3,424us | 8,651us, and 6,855 failures |

Run 1 says the stock JVM collapses and ZGC holds the best tail. Run 2 says the
stock JVM is the fastest of the four and ZGC is the one that falls over. The
ordering does not survive a repeat, so neither run is a result. The first
reading of this — a 26x win from sizing the heap — was an artifact of one bad
stock run and is withdrawn.

**8,000 a second was the wrong place to measure.** It is the knee, and a queue
at saturation is bistable: it drains or it grows without bound, and very little
decides which. Comparing configurations at the one rate where the system is
least stable measures the coin flip.

Below the knee it is well behaved, and there the four are the same:

| variant | run 1 | run 2 |
|---|---:|---:|
| stock | 737us | 614us |
| fixed heap | 647us | 668us |
| ParallelGC | 561us | 598us |
| ZGC | 696us | 655us |

Every configuration lands in 561-737us, and the gaps between them are no larger
than the stock JVM's own spread between its two runs. Two samples cannot
separate them.

## Why this was predictable

The profile above says three quarters of the on-CPU samples are UTF-8 encoding
and zio-schema turning a `Character` into bytes. Heap and collector settings
change pause behaviour and allocation headroom; they do not change how much CPU
it takes to encode a string. There was no mechanism by which these flags could
have moved the number that matters, and the measurement agrees.

ParallelGC was the lowest at 4,000 in both runs, and it and the fixed heap were
the only two that never had a catastrophic run. That is worth a default of
`-Xms2g -Xmx2g -XX:+AlwaysPreTouch -XX:+UseParallelGC` on stability grounds, and
it is not a measured claim. ZGC is worth avoiding on this evidence: it is the
only variant that dropped requests.

## What a real answer would need

Measure below the knee, five or more repetitions a variant, interleaved rather
than one variant at a time, comparing distributions rather than points — and
with the generator on another machine, since it is competing for these four
cores and is a large part of why the knee is so unstable.
