package com.matthewjones372.loadtest

import com.matthewjones372.http.api.SWHttpServer
import zio.*
import zio.Runtime.removeDefaultLoggers
import zio.http.*
import zio.logging.LogFormat
import zio.logging.backend.SLF4J

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
