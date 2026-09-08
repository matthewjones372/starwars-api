package com.matthewjones372.loadtest

import com.matthewjones372.http.api.SWHttpServer
import zio.*
import zio.Runtime.removeDefaultLoggers
import zio.http.*
import zio.logging.LogFormat
import zio.logging.backend.SLF4J

/**
 * The API under load, in a JVM of its own.
 *
 * Separate from the generator on purpose: a load test that shares a heap with
 * the thing it measures cannot say whether a pause was the target's or its
 * own. The two still share this machine's cores, which is what a laptop run
 * looks like and is said out loud rather than left for a reader to discover.
 *
 * `args`: the port, then `logging` or `quiet`. The logger stack is identical
 * either way, so the only thing that changes between two runs of this is
 * whether `Middleware.debug` is on the path a response takes.
 */
object LoadTestServer extends ZIOAppDefault:

  def run =
    for
      args     <- getArgs
      port      = args.headOption.flatMap(_.toIntOption).getOrElse(8080)
      logging   = args.lift(1).forall(_ == "logging")
      _        <- ZIO.logInfo(s"serving on $port, request logging ${if logging then "on" else "off"}")
      server   <- SWHttpServer.withRequestLogging(logging)
      _        <- server.start.provide(Server.defaultWithPort(port))
    yield ()

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    removeDefaultLoggers >>> SLF4J.slf4j(LogFormat.colored)
