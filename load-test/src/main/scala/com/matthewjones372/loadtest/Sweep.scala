package com.matthewjones372.loadtest

import zio.*
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

object Sweep extends ZIOAppDefault:

  private val reports = Path.of("load-test/target/reports")

  def run =
    getArgs.flatMap: args =>
      args.headOption.getOrElse("compare") match
        case "compare" => compare
        case "profile" => profile(args.lift(1).flatMap(_.toIntOption).getOrElse(6000), args.lift(2).flatMap(_.toIntOption).getOrElse(60))
        case "jvm"     => jvmVariants
        case other     => ZIO.fail(new IllegalArgumentException(s"unknown mode '$other': compare, profile or jvm"))

  private def compare =
    for
      _ <- against("logging-on", 8080, "logging")(RateSweep.sweep(_, "logging-on", RateSweep.defaultLadder, 20, reports))
      _ <- against("logging-off", 8081, "quiet")(RateSweep.sweep(_, "logging-off", RateSweep.defaultLadder, 20, reports))
    yield ()

  private def profile(rate: Int, seconds: Int) =
    val recording = Path.of("load-test/target/server.jfr")
    val flags = List(
      s"-XX:StartFlightRecording=name=swapi,settings=profile,dumponexit=true,filename=$recording",
    )
    against("profile", 8090, "quiet", flags)(RateSweep.sweep(_, s"profile-$rate", List(rate), seconds, reports)) *>
      Profile.hottest(recording, 25).flatMap(rows => Console.printLine(rows.mkString("\n")))

  private def jvmVariants =
    val heap = List("-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch")
    val variants = List(
      "baseline" -> Nil,
      "heap"     -> heap,
      "parallel" -> (heap :+ "-XX:+UseParallelGC"),
      "zgc"      -> (heap :+ "-XX:+UseZGC"),
    )
    ZIO.foreachDiscard(variants.zipWithIndex):
      case ((label, flags), index) =>
        against(label, 8110 + index, "quiet", flags)(
          RateSweep.sweep(_, s"jvm-$label", List(4000, 6000, 8000), 20, reports),
        )

  private def against[A](label: String, port: Int, mode: String, flags: List[String] = Nil)(
    use: String => Task[A],
  ): Task[A] =
    ZIO.scoped:
      for
        _ <- Console.printLine(s"== $label ==")
        _ <- serving(label, port, mode, flags)
        a <- use(s"http://localhost:$port")
      yield a

  private def serving(label: String, port: Int, mode: String, flags: List[String]) =
    ZIO
      .acquireRelease(start(label, port, mode, flags))(stop)
      .zipRight(ready(port))

  private def stop(process: Process) =
    ZIO
      .attemptBlocking:
        process.destroy()
        process.waitFor(30, TimeUnit.SECONDS)
      .ignore

  private def start(label: String, port: Int, mode: String, flags: List[String]) =
    ZIO.attemptBlocking:
      val jvm       = ProcessHandle.current.info.command.orElse("java")
      val classpath = java.lang.System.getProperty("java.class.path")
      val command = (jvm :: flags) ++
        List("-cp", classpath, LoadTestServer.getClass.getName.stripSuffix("$"), port.toString, mode)
      ProcessBuilder(command.asJava)
        .redirectErrorStream(true)
        .redirectOutput(Path.of(s"load-test/target/server-$label.log").toFile)
        .start()

  private def ready(port: Int) =
    ZIO
      .attemptBlocking:
        val connection = URI.create(s"http://localhost:$port/people/1").toURL.openConnection()
        connection.setConnectTimeout(500)
        connection.setReadTimeout(500)
        connection.getInputStream.close()
      .retry(Schedule.spaced(1.second) && Schedule.recurs(90))
