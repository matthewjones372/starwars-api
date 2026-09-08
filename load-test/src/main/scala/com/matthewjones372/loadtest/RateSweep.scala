package com.matthewjones372.loadtest

import io.github.matthewjones372.kestrel.Concurrency
import io.github.matthewjones372.kestrel.ConcurrencyKt
import io.github.matthewjones372.kestrel.OfferedKt
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.RunResultKt
import io.github.matthewjones372.kestrel.java.Simulations
import io.github.matthewjones372.kestrel.scala.apply
import io.github.matthewjones372.kestrel.scala.exec
import io.github.matthewjones372.kestrel.scala.given
import io.github.matthewjones372.kestrel.scala.http
import io.github.matthewjones372.kestrel.scala.perSecond
import io.github.matthewjones372.kestrel.scala.scenario
import io.github.matthewjones372.kestrel.scala.step
import io.github.matthewjones372.kestrel.ziotest.kestrel
import zio.Console
import zio.ZIOAppDefault
import zio.ZIO
import java.util.concurrent.TimeUnit
import _root_.scala.concurrent.duration.FiniteDuration
import _root_.scala.language.implicitConversions

/**
 * Test 0: where the wall is, and whose it is.
 *
 * The cheapest handler the API has — `GET /people/{id}` is a lookup in a `Map`
 * held in memory — swept up a ladder of rates. The point is not the latency of
 * a map lookup. It is the three questions a capacity number is worthless
 * without:
 *
 *   - did the load actually leave (`share`, `fellBehind`, `lostGround`)
 *   - what did the target do at the load that reached it (`service`)
 *   - where was the queue (Little's law: `observed` against `predicted`)
 *
 * A rung where the generator fell behind found *this tool's* ceiling on this
 * machine, not the server's, and the row says so rather than being quoted as
 * an API capacity.
 */
object RateSweep extends ZIOAppDefault:

  private[loadtest] val person = step("GET /people/{id}")

  private val defaultLadder = List(1000, 2000, 4000, 6000, 8000, 12000, 16000)

  private val defaultRung = 20

  def run =
    for
      args   <- getArgs
      baseUrl = args.headOption.getOrElse("http://localhost:8080")
      label   = args.lift(1).getOrElse("run")
      ladder  = args.lift(2).map(_.split(",").toList.flatMap(_.trim.toIntOption)).filter(_.nonEmpty)
                  .getOrElse(defaultLadder)
      perRung = FiniteDuration(args.lift(3).flatMap(_.toIntOption).getOrElse(defaultRung), TimeUnit.SECONDS)
      _      <- Console.printLine(s"# $label — $baseUrl, ${perRung.toSeconds}s a rung")
      _      <- warmUp(baseUrl)
      rows   <- ZIO.foreach(ladder)(rate => measure(baseUrl, rate, perRung))
      _      <- Console.printLine(table(rows))
    yield ()

  /**
   * Discarded, and long on purpose.
   *
   * A first pass at this warmed for ten seconds at 200 a second and the ladder
   * that followed reported service time *falling* as the rate climbed, by a
   * factor of three from end to end — a JVM still compiling, read as a server
   * getting faster under load. Sixty thousand requests is past the thresholds
   * that were still being crossed inside the measurement.
   */
  private def warmUp(baseUrl: String) =
    Console.printLine("warming up 60s (discarded)") *>
      kestrel.run(Simulations.at(lookups(baseUrl), 1000.perSecond, FiniteDuration(60, TimeUnit.SECONDS))).unit

  private def lookups(baseUrl: String) =
    val api = http.baseUrl(baseUrl)
    scenario("person by id")(exec(person, api.get("/people/1").expecting(200)))

  private def measure(baseUrl: String, rate: Int, perRung: FiniteDuration) =
    for
      _      <- Console.printLine(s"  rung $rate/s ...")
      result <- kestrel.run(Simulations.at(lookups(baseUrl), rate.perSecond, perRung))
    yield Rung(rate, result)

  private def table(rows: List[Rung]) =
    val header = List(
      "asked/s", "left", "reqs", "failed", "svc p50", "svc p99", "rsp p50", "rsp p99",
      "behind", "lost", "L obs", "L pred", "backlog", "agrees",
    )
    val body = rows.map(_.cells)
    val widths = (header :: body).transpose.map(_.map(_.length).max)
    def line(cells: List[String]) =
      cells.zip(widths).map((cell, width) => cell.reverse.padTo(width, ' ').reverse).mkString("| ", " | ", " |")
    val rule = widths.map("-" * _).mkString("|-", "-|-", "-|")
    (line(header) :: rule :: body.map(line)).mkString("\n", "\n", "\n")

/** One rung of the ladder, read off the run rather than recomputed. */
final class Rung(asked: Int, result: RunResult):

  private val measured   = result(RateSweep.person)
  private val offered    = Option(OfferedKt.getOffered(result))
  private val concurrent = ConcurrencyKt.getConcurrency(result)

  private def micros(duration: FiniteDuration) = f"${duration.toNanos / 1000.0}%.0fus"

  private def measuredConcurrency = concurrent match
    case m: Concurrency.Measured => Some(m)
    case _                       => None

  def cells: List[String] = List(
    asked.toString,
    offered.fold("-")(o => f"${asked * o.getShare}%.0f/s"),
    measured.count.toString,
    measured.failed.toString,
    micros(measured.serviceTime.p50),
    micros(measured.serviceTime.p99),
    micros(measured.responseTime.p50),
    micros(measured.responseTime.p99),
    if RunResultKt.fellBehind(result) then "yes" else "no",
    if RunResultKt.lostGround(result) then "yes" else "no",
    measuredConcurrency.fold("-")(m => f"${m.getObserved}%.1f"),
    measuredConcurrency.fold("-")(m => f"${m.getFromServiceTime}%.1f"),
    measuredConcurrency.fold("-")(m => f"${m.getBacklog}%.1f"),
    measuredConcurrency.fold("-")(m => if m.getAgrees then "yes" else "no"),
  )
