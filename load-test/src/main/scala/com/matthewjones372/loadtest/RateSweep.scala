package com.matthewjones372.loadtest

import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.java.Simulations
import io.github.matthewjones372.proofload.report.HtmlReportKt
import io.github.matthewjones372.proofload.report.MarkdownKt
import io.github.matthewjones372.proofload.report.StepSummaryKt
import kotlin.jvm.functions.Function1
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.given
import io.github.matthewjones372.proofload.scala.http
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.step
import io.github.matthewjones372.proofload.ziotest.proofload
import zio.Console
import zio.ZIOAppDefault
import zio.ZIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import _root_.scala.concurrent.duration.FiniteDuration
import _root_.scala.language.implicitConversions

object RateSweep extends ZIOAppDefault:

  private val person = step("GET /people/{id}")

  private val defaultLadder = List(1000, 2000, 4000, 6000, 8000, 12000, 16000)

  private val defaultRung = 20

  def run =
    for
      args   <- getArgs
      baseUrl = args.headOption.getOrElse("http://localhost:8080")
      label   = args.lift(1).getOrElse("run")
      ladder = args
                 .lift(2)
                 .map(_.split(",").toList.flatMap(_.trim.toIntOption))
                 .filter(_.nonEmpty)
                 .getOrElse(defaultLadder)
      perRung = FiniteDuration(args.lift(3).flatMap(_.toIntOption).getOrElse(defaultRung), TimeUnit.SECONDS)
      into    = Path.of(args.lift(4).getOrElse("load-test/target/reports"))
      _      <- Console.printLine(s"# $label - $baseUrl, ${perRung.toSeconds}s a rung")
      _      <- warmUp(baseUrl)
      runs   <- ZIO.foreach(ladder)(rate => measure(baseUrl, rate, perRung).map(rate -> _))
      _      <- reports(label, into, runs)
    yield ()

  private def reports(label: String, into: Path, runs: List[(Int, RunResult)]) =
    ZIO.attemptBlocking {
      Files.createDirectories(into)
      runs.foreach: (rate, result) =>
        HtmlReportKt.writeHtmlReport(result, into.resolve(s"$label-$rate.html"), null, null, java.util.List.of())
      val fromEnvironment: Function1[String, String] = name => System.getenv(name)
      runs.foreach((_, result) => StepSummaryKt.appendToStepSummary(result, null, null, fromEnvironment))
      val written = runs.map((rate, result) => s"### $rate/s\n\n" + MarkdownKt.markdown(result, null, null))
      Files.writeString(into.resolve(s"$label.md"), written.mkString("\n"))
      written
    }.flatMap(written => Console.printLine(written.mkString("\n")))

  private def warmUp(baseUrl: String) =
    Console.printLine("warming up 60s (discarded)") *>
      proofload.run(Simulations.at(lookups(baseUrl), 1000.perSecond, FiniteDuration(60, TimeUnit.SECONDS))).unit

  private def lookups(baseUrl: String) =
    val api = http.baseUrl(baseUrl)
    scenario("person by id")(exec(person, api.get("/people/1").expecting(200)))

  private def measure(baseUrl: String, rate: Int, perRung: FiniteDuration) =
    for
      _      <- Console.printLine(s"  rung $rate/s ...")
      result <- proofload.run(Simulations.at(lookups(baseUrl), rate.perSecond, perRung))
    yield result
