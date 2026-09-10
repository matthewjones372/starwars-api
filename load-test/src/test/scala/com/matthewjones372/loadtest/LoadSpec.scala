package com.matthewjones372.loadtest

import com.matthewjones372.http.api.SWHttpServer
import io.github.matthewjones372.proofload.ComparisonKt
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.scala.at
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.expecting
import io.github.matthewjones372.proofload.scala.failureRate
import io.github.matthewjones372.proofload.scala.markdown
import io.github.matthewjones372.proofload.scala.percent
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.step
import io.github.matthewjones372.proofload.scala.http as load
import io.github.matthewjones372.proofload.ziotest.ProofloadSpec
import io.github.matthewjones372.proofload.ziotest.metItsGoals
import io.github.matthewjones372.proofload.ziotest.proofload
import zio.*
import zio.http.Server
import zio.test.*
import java.nio.file.Path

object LoadSpec extends ProofloadSpec:

  override val reportsTo: Path = Path.of("load-test/target/reports")

  private val person = step("GET /people/{id}")
  private val people = step("GET /people")

  private val ladder  = List(1000, 2000, 4000, 6000, 8000)
  private val perRung = 20.seconds

  private val comparisonLadder = List(2000, 4000, 6000, 8000)
  private val comparisonRung   = 10.seconds
  private val repetitions      = 3

  def spec = suite("load")(
    test("holds its failure rate up the ladder")(sweep()),
    test("pre-encoding one character is worth what the profile said it was")(
      comparison("GET /people/{id}", lookups)
    ),
    test("pre-encoding a page of ten is worth more")(comparison("GET /people", pages))
  ) @@ TestAspect.timeout(40.minutes)

  private def sweep() =
    ZIO.scoped:
      for
        port <- serving(preEncoded = true)
        _    <- Console.printLine(s"# ladder - localhost:$port, ${perRung.toSeconds}s a rung")
        _    <- warm(port, lookups)
        runs <- ZIO.foreach(ladder)(rate => measure(port, rate))
        _    <- ZIO.foreach(runs)(result => proofload.markdown(result).flatMap(Console.printLine(_)))
      yield runs.map(_.metItsGoals).reduce(_ && _)

  private def comparison(label: String, sending: Int => Scenario) =
    ZIO.scoped:
      for
        preEncoded <- serving(preEncoded = true)
        encoding   <- serving(preEncoded = false)
        _          <- Console.printLine(
                        s"# $label - pre-encoded on :$preEncoded, encoding on :$encoding, " +
                          s"${comparisonRung.toSeconds}s a rung, $repetitions passes"
                      )
        _          <- warm(preEncoded, sending) *> warm(encoding, sending)
        _          <- ZIO.foreachDiscard(1 to repetitions)(pass => climbed(label, pass, preEncoded, encoding, sending))
      yield assertCompletes

  private def climbed(label: String, pass: Int, preEncoded: Int, encoding: Int, sending: Int => Scenario) =
    ZIO.foreachDiscard(comparisonLadder): rate =>
      for
        fast <- at(preEncoded, sending, rate, comparisonRung)
        slow <- at(encoding, sending, rate, comparisonRung)
        _    <- Console.printLine(s"## $label, pass $pass, $rate/s: pre-encoded against encoding per request")
        _    <- Console.printLine(fast.markdown(Some(ComparisonKt.against(fast, slow, 99.0)), None))
      yield ()

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

  private def pages(port: Int) =
    val api = load.baseUrl(s"http://localhost:$port")
    scenario("a page of people")(exec(people, api.get("/people?page=2").expecting(200)))

  private def warm(port: Int, sending: Int => Scenario) =
    proofload.run(sending(port).at(1000.perSecond, over = 60.seconds))

  private def at(port: Int, sending: Int => Scenario, rate: Int, over: Duration) =
    proofload.run(sending(port).at(rate.perSecond, over = over).expecting(failureRate under 0.1.percent))

  private def measure(port: Int, rate: Int): ZIO[Any, Throwable, RunResult] =
    Console.printLine(s"  rung $rate/s ...") *>
      measured(s"ladder-$rate"):
        lookups(port).at(rate.perSecond, over = perRung).expecting(failureRate under 0.1.percent)
