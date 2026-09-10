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

# Test 1 — pre-encoding the response

The profile above said turning a `Character` into bytes was roughly three
quarters of the on-CPU samples. The data is read from a resource at startup and
never changes, so `SWHttpServer` now encodes each character and each film once
and serves those bytes.

Two servers in one JVM, `preEncoded` on one and off the other, rungs alternating
between them so a drift in JIT state or in what else the machine is doing lands
on both. 10s a rung, three passes, generator in the same JVM on 4 shared cores.

| pass | rate | pre-encoded | encoding per request | ratio |
|-----:|-----:|------------:|---------------------:|------:|
| 1 | 2,000/s | 282us | 317us | 1.12 |
| 1 | 4,000/s | 194us | 247us | 1.27 |
| 1 | 6,000/s | 238us | 252us | 1.06 |
| 1 | 8,000/s | 230us | 266us | 1.16 |
| 2 | 2,000/s | 274us | 325us | 1.19 |
| 2 | 4,000/s | 240us | 280us | 1.17 |
| 2 | 6,000/s | 253us | 258us | 1.02 |
| 2 | 8,000/s | 231us | 259us | 1.12 |
| 3 | 2,000/s | 274us | 292us | 1.07 |
| 3 | 4,000/s | 216us | 248us | 1.15 |
| 3 | 6,000/s | 232us | 268us | 1.16 |
| 3 | 8,000/s | 254us | 290us | 1.14 |

## Reading it

**It is real, and it is small.** Pre-encoding is ahead in twelve rungs out of
twelve, by between 2% and 27%, median about 13%. Unlike the JVM flags, the
ordering survives every repeat, which is what makes this a result rather than a
reading. It is nothing like the three-to-four times the profile's three quarters
might have suggested.

**A profile says where the CPU goes when the CPU is the constraint.** At the
rates this arrangement can actually offer, the server is not CPU-bound: a
request spends most of its service time somewhere other than encoding, so
removing the encoding takes a tenth off rather than three quarters. The profile
was taken at 6,000/s with the target in a JVM of its own, which is a busier
server than any rung here.

**Whether it moves the knee is unanswered.** Every rung above 4,000/s is marked
"not held" for *both* variants, and the reason is the generator: it lost ground
at 6,000/s either way. Above 4,000/s this arrangement measures the injector, so
the ladder cannot say whether the API's own ceiling moved. That needs the
generator on another machine, and it is the measurement that would decide
whether this change is worth more than the 13%.

## Repeated

The same comparison on `0.1.0-rc4`, three fresh passes: ahead in twelve rungs
out of twelve again, ratios between 1.08 and 1.18. Two independent runs of
twelve rungs each, twenty-four for twenty-four, is what makes this a result
rather than a reading.

One pass of the three had the 6,000/s rung held pre-encoded and not held with
the encoding, which is the first hint that the knee might move after all. One
pass in three is not a claim, and the arrangement cannot make it one: the
generator is what loses ground there. It is the reason to want the measurement
with the generator off-box rather than the reason to stop.

## What was checked, and what it cost

`PreEncodedSpec` runs both servers and asserts the status, the content type and
the body are identical for a character, a film, and a miss of each. An
optimisation that changes a response is a behaviour change wearing a performance
argument.

The encoding is built once, on first request, from a suspended and memoized
effect, as the character graph already was: nothing touches the repo until a
request needs it. It applies to the bundled data only. `SWHttpServer.layer`
takes whatever repo it is handed, which has made no promise to be immutable, so
that one still asks per request.

# Test 2 — the list endpoint, where the profile was right

`GET /people` encodes ten characters a request rather than one. The repo still
chooses and orders the page, which is a sort and a slice over values already in
memory; what the page skips now is the encoding, because the bytes of each
character are already in the map and a page is those bytes joined.

Both endpoints, same arrangement, three passes each. These are Proofload's own
comparison, which is p99 of **response time**: the clock that carries the
injector's backlog as well as the target's work.

| rate | `GET /people` was | now | | `GET /people/{id}` was | now |
|-----:|------------------:|----:|:--|----------------------:|----:|
| 2,000/s | 864us | 774us | better | 745us | 836us | worse |
| 4,000/s | 1.85ms | 618us | better | 664us | 606us | not distinguishable |
| 6,000/s | 4.36ms | 1.43ms | better | 2.00ms | 1.38ms | better |
| 8,000/s | 5.64ms | 5.73ms | not distinguishable | 2.98ms | 2.67ms | not distinguishable |
| 2,000/s | 1.05ms | 713us | better | 696us | 672us | not distinguishable |
| 4,000/s | 1.31ms | 664us | better | 672us | 623us | not distinguishable |
| 6,000/s | 4.33ms | 2.05ms | better | 1.47ms | 995us | better |
| 8,000/s | 12.7ms | 3.29ms | better | 5.51ms | 3.08ms | better |
| 2,000/s | 983us | 729us | better | 725us | 696us | not distinguishable |
| 4,000/s | 1.76ms | 655us | better | 623us | 602us | not distinguishable |
| 6,000/s | 4.82ms | 1.27ms | better | 1.37ms | 774us | better |
| 8,000/s | 8.45ms | 3.41ms | better | 2.54ms | 2.18ms | not distinguishable |

## Reading it

**The list endpoint is eleven better out of twelve, and by two to four times at
4,000/s and above.** The single-character endpoint over the same twelve rungs is
four better, seven not distinguishable and one worse. Ten encodes a request
against one is the whole difference between those two columns, and it is the
profile's three quarters finally showing up where there is enough of it to see.

**The clock is why the two tables disagree with Test 1.** Test 1 read service
time p50 and found a steady 13% on the single-character endpoint. This one reads
p99 response time, where the generator's own lateness is a large part of the
number, so the same 13% is inside the noise and reads "not distinguishable".
Neither is wrong: they are answers to different questions, and the response-time
one is what a client would feel.

**Ten times the work is what made it visible.** That is the useful general
lesson from both tests: a change that removes CPU per request shows up where
there is enough CPU per request for it to matter, and the way to find that is
the endpoint that does the most work, not the rate that is closest to the knee.

## Two things Proofload could not do here

- **`against` compares response time and nothing else.** There is no clock
  parameter, so the comparison above cannot be asked for service time, which is
  the honest clock when the generator is behind — and Proofload's own warning
  two lines above each table says it is behind. Test 1's table had to be read
  off the runs by hand for that reason.
- **The Scala surface renders a `Comparison` but cannot build one.**
  `writeHtmlReport` and `markdown` both take one; nothing makes one, so this
  spec names `ComparisonKt.against`, which is the last Kotlin file class left in
  this repository.

### Both are fixed upstream, and waiting on a release

Proofload spec 0141 adds the clock and the two front doors. It is merged into
`proofload` but not published: this module resolves from Maven Central, where
the newest version is `0.1.0-rc4`. When a release carrying 0141 lands, three
lines change here and nothing else:

1. `proofloadV` in `project/Dependencies.scala`.
2. `import io.github.matthewjones372.proofload.ComparisonKt` becomes
   `io.github.matthewjones372.proofload.scala.against`, and the last Kotlin file
   class leaves this repository.
3. `ComparisonKt.against(fast, slow, 99.0)` in `LoadSpec` becomes
   `fast.against(slow, percentile = 50.0, of = Clock.ServiceTime)`.

That was run here against a locally published build before being reverted, and
it is worth writing down what it changed, because it is the argument for the
spec. The same twelve rungs of `GET /people/{id}`, one clock against the other:

| read on | better | not distinguishable | worse |
|---|---:|---:|---:|
| p99 of response time | 4 | 7 | 1 |
| p50 of service time | 9 | 1 | 2 |

Nine clear readings where there were four. Not unanimous, and it should not be:
the effect on this endpoint is about 13%, the two rungs that read worse are
252us against 264us and 305us against 313us, and a machine shared with its own
generator moves by more than that between runs. What the clock buys is that the
question is now being asked of the target rather than of the queue in front of
it. The note under the table says `Compared at p50 of service time`, so a reader
cannot mistake which one they are looking at.

## Next

Payload size is the lever underneath all of this. A person carries `homeworld`
and four collections of urls, every one a string on every response, and this
clone serves none of the resources they point at. That is a smaller payload
rather than a faster encoder, and it is the change with the next largest
ceiling.
