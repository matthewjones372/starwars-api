package com.matthewjones372.loadtest

import com.matthewjones372.http.api.SWHttpServer
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.java.Goals
import io.github.matthewjones372.proofload.java.Simulations
import io.github.matthewjones372.proofload.report.HtmlReportKt
import io.github.matthewjones372.proofload.report.MarkdownKt
import io.github.matthewjones372.proofload.report.PagesKt
import io.github.matthewjones372.proofload.report.StepSummaryKt
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

  def spec = suite("load")(
    test("holds its failure rate up the ladder, without the debug middleware")(sweep("logging-off", logging = false)),
    test("holds its failure rate up the ladder, with the debug middleware")(sweep("logging-on", logging = true)),
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(40.minutes)

  private def sweep(label: String, logging: Boolean) =
    ZIO.scoped:
      (for
        port <- serving(logging)
        _    <- Console.printLine(s"# $label - localhost:$port, ${perRung.toSeconds}s a rung")
        _    <- proofload.run(Simulations.at(lookups(port), 1000.perSecond, 60.seconds))
        runs <- ZIO.foreach(ladder)(rate => measure(port, rate).map(rate -> _))
        _    <- written(label, runs)
      yield runs.map(_._2).map(_.metItsGoals).reduce(_ && _))
        .provideSome[Scope](Server.defaultWithPort(0))

  private def serving(logging: Boolean) =
    for
      server <- SWHttpServer.withRequestLogging(logging)
      _      <- server.start.forkScoped
      port   <- ZIO.serviceWithZIO[Server](_.port)
      _      <- awaitBound(port)
    yield port

  private def awaitBound(port: Int) =
    ZIO
      .attemptBlocking(java.net.Socket("localhost", port).close())
      .retry(Schedule.spaced(100.millis) && Schedule.recurs(100))

  private def lookups(port: Int) =
    val api = load.baseUrl(s"http://localhost:$port")
    scenario("person by id")(exec(person, api.get("/people/1").expecting(200)))

  private def measure(port: Int, rate: Int) =
    Console.printLine(s"  rung $rate/s ...") *>
      proofload.run(Simulations.at(lookups(port), rate.perSecond, perRung, Goals.failureRateUnder(0.1)))

  private def written(label: String, runs: List[(Int, RunResult)]) =
    ZIO.attemptBlocking {
      val environment: Function1[String, String] = name => java.lang.System.getenv(name)
      runs.foreach: (rate, result) =>
        HtmlReportKt.writeHtmlReport(result, reports.resolve(s"$label-$rate.html"), null, null, java.util.List.of())
        StepSummaryKt.appendToStepSummary(result, null, null, environment)
      PagesKt.writePagesIndex(reports)
      runs.map((rate, result) => s"### $rate/s\n\n" + MarkdownKt.markdown(result, null, null))
    }.flatMap(markdown => Console.printLine(markdown.mkString("\n")))
