# Test 0 — where the wall is, and whose it is

Run on 4 cores / 15 GB, JDK 25, generator and target in separate JVMs on the
same machine. 60s warm-up discarded, 20s a rung. `GET /people/{id}`, which is a
lookup in a `Map` held in memory.

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

**`behind: yes` on the healthy rungs is not a warning.** Kestrel asks whether
the generator's p99 lateness is larger than the precision it quotes the target's
p99 to — 0.78% of it. At sub-millisecond latencies that trips almost always;
Kestrel's own ceiling page reports `yes` at every rate but one for the same
reason. `lostGround` and `left` are the columns that carry the attribution.

## What this does not say

- **Not a number for dedicated hardware.** The generator competes with the
  server for the same four cores, so both knees would move on a machine where
  the API had them to itself.
- **Not an attribution for the failures above 6,000/s.** Server overload and
  ephemeral-port exhaustion look identical in this table. Kestrel records the
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
