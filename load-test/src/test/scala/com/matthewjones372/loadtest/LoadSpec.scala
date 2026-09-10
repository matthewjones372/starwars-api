package com.matthewjones372.loadtest

import com.matthewjones372.http.api.SWHttpServer
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.RunResultKt
import io.github.matthewjones372.proofload.java.Goals
import io.github.matthewjones372.proofload.java.Simulations
import io.github.matthewjones372.proofload.report.HtmlReportKt
import io.github.matthewjones372.proofload.report.MarkdownKt
import io.github.matthewjones372.proofload.report.PagesKt
import io.github.matthewjones372.proofload.report.StepSummaryKt
import io.github.matthewjones372.proofload.scala.apply
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.step
import io.github.matthewjones372.proofload.scala.http as load
import io.github.matthewjones372.proofload.ziotest.metItsGoals
import io.github.matthewjones372.proofload.ziotest.proofload
import kotlin.jvm.functions.Function1
import zio.*
import zio.http.Server
import zio.test.*
import java.nio.file.Path

object LoadSpec extends ZIOSpecDefault:

  private val person  = step("GET /people/{id}")
  private val ladder  = List(1000, 2000, 4000, 6000, 8000)
  private val perRung = 20.seconds
  private val reports = Path.of("load-test/target/reports")

  // Interleaved rung by rung, and repeated. Removing CPU work per request buys
  // headroom rather than latency: below the knee the server is not CPU-bound
  // and the two are within noise of each other, so what this compares is where
  // each variant stops holding, not what either costs at a rate both hold.
  //
  // A rate, not a latency. The JVM sweep in FINDINGS.md is the negative result
  // that came of comparing latency at the knee, where a queue is bistable; the
  // rung a variant last held is a step on a ladder and survives a repeat.
  private val comparisonLadder = List(2000, 4000, 6000, 8000)
  private val comparisonRung   = 10.seconds
  private val repetitions      = 3

  def spec = suite("load")(
    test("holds its failure rate up the ladder")(sweep()),
    test("pre-encoding the response is worth what the profile said it was")(comparison()),
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(40.minutes)

  private def sweep() =
    ZIO.scoped:
      for
        port <- serving(preEncoded = true)
        _    <- Console.printLine(s"# ladder - localhost:$port, ${perRung.toSeconds}s a rung")
        _    <- proofload.run(Simulations.at(lookups(port), 1000.perSecond, 60.seconds))
        runs <- ZIO.foreach(ladder)(rate => measure(port, rate).map(rate -> _))
        _    <- written("ladder", runs)
      yield runs.map(_._2).map(_.metItsGoals).reduce(_ && _)

  private def comparison() =
    ZIO.scoped:
      for
        preEncoded <- serving(preEncoded = true)
        encoding   <- serving(preEncoded = false)
        _          <- Console.printLine(
                        s"# comparison - pre-encoded on :$preEncoded, encoding on :$encoding, " +
                          s"${comparisonRung.toSeconds}s a rung, $repetitions passes",
                      )
        _          <- warm(preEncoded) *> warm(encoding)
        passes     <- ZIO.foreach(1 to repetitions)(pass => climbed(pass, preEncoded, encoding))
        _          <- reported(passes.toList)
      yield assertCompletes

  private def climbed(pass: Int, preEncoded: Int, encoding: Int) =
    ZIO.foreach(comparisonLadder): rate =>
      for
        fast <- at(preEncoded, rate, comparisonRung)
        slow <- at(encoding, rate, comparisonRung)
        _    <- Console.printLine(
                  s"  pass $pass, $rate/s: pre-encoded ${describe(fast)}, encoding ${describe(slow)}",
                )
      yield (rate, fast, slow)

  // Its own `Server` layer per call, built into this scope. Two servers sharing
  // one layer would be one port answering for both, and an A/B against itself;
  // `provideSome` around the effect that starts one releases it when that effect
  // finishes, which takes the server down before the load arrives.
  private def serving(preEncoded: Boolean): ZIO[Scope, Throwable, Int] =
    for
      env    <- Server.defaultWithPort(0).build
      server <- SWHttpServer.measuring(preEncoded)
      _      <- server.start.provideEnvironment(env).forkScoped
      port   <- env.get[Server].port
      _      <- awaitBound(port)
    yield port

  private def awaitBound(port: Int) =
    ZIO
      .attemptBlocking(java.net.Socket("localhost", port).close())
      .retry(Schedule.spaced(100.millis) && Schedule.recurs(100))

  private def lookups(port: Int) =
    val api = load.baseUrl(s"http://localhost:$port")
    scenario("person by id")(exec(person, api.get("/people/1").expecting(200)))

  private def warm(port: Int) =
    proofload.run(Simulations.at(lookups(port), 1000.perSecond, 60.seconds))

  private def at(port: Int, rate: Int, over: Duration) =
    proofload.run(Simulations.at(lookups(port), rate.perSecond, over, Goals.failureRateUnder(0.1)))

  private def measure(port: Int, rate: Int) =
    Console.printLine(s"  rung $rate/s ...") *> at(port, rate, perRung)

  private def micros(result: RunResult) = result(person).serviceTime.p50.toMicros

  // A rung counts as held when nothing failed and the generator kept its own
  // schedule. A rung it lost ground on measured this machine's ceiling, and
  // reporting that as the API's would be the mistake the whole page is about.
  private def held(result: RunResult) =
    result(person).failed == 0L && !RunResultKt.lostGround(result)

  private def describe(result: RunResult) =
    s"${micros(result)}us, ${result(person).failed} failed${if held(result) then "" else ", not held"}"

  private def highest(runs: List[(Int, RunResult)]) =
    runs.filter((_, result) => held(result)).map(_._1).maxOption

  private def reported(passes: List[List[(Int, RunResult, RunResult)]]) =
    val rows = passes.zipWithIndex.flatMap { case (pass, index) =>
      pass.map { case (rate, fast, slow) =>
        s"| ${index + 1} | $rate | ${micros(fast)}us | ${micros(slow)}us | " +
          s"${if held(fast) then "held" else "no"} | ${if held(slow) then "held" else "no"} |"
      }
    }
    val knees = passes.zipWithIndex.map { case (pass, index) =>
      val fast = highest(pass.map((rate, run, _) => rate -> run))
      val slow = highest(pass.map((rate, _, run) => rate -> run))
      s"| pass ${index + 1} | ${fast.getOrElse("none")} | ${slow.getOrElse("none")} |"
    }
    Console.printLine(
      (List(
        "### service time p50, and whether the rung held",
        "",
        "| pass | rate | pre-encoded | encoding | pre-encoded held | encoding held |",
        "|-----:|-----:|------------:|---------:|:-----------------|:--------------|",
      ) ++ rows ++ List(
        "",
        "### highest rung held",
        "",
        "| pass | pre-encoded | encoding |",
        "|-----:|------------:|---------:|",
      ) ++ knees).mkString("\n"),
    )

  private def written(label: String, runs: List[(Int, RunResult)]) =
    ZIO.attemptBlocking {
      val environment: Function1[String, String] = name => java.lang.System.getenv(name)
      runs.foreach: (rate, result) =>
        HtmlReportKt.writeHtmlReport(result, reports.resolve(s"$label-$rate.html"), null, null, java.util.List.of())
        StepSummaryKt.appendToStepSummary(result, null, null, environment)
      PagesKt.writePagesIndex(reports)
      runs.map((rate, result) => s"### $rate/s\n\n" + MarkdownKt.markdown(result, null, null))
    }.flatMap(markdown => Console.printLine(markdown.mkString("\n")))
