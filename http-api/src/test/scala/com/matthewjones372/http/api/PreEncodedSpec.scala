package com.matthewjones372.http.api

import zio.*
import zio.http.*
import zio.test.*

object PreEncodedSpec extends ZIOSpecDefault:

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

  private def get(port: Int, path: String) =
    for
      client   <- ZIO.service[Client]
      response <- client(Request.get(URL.decode(s"http://localhost:$port$path").toOption.get))
      body     <- response.body.asString
    yield (response.status, response.header(Header.ContentType), body)
  private def sameBothWays(path: String, expected: Status) =
    test(s"$path is the same response with the encoding done once as with it done per request"):
      ZIO.scoped:
        for
          fast   <- serving(preEncoded = true)
          codec  <- serving(preEncoded = false)
          served <- get(fast, path)
          made   <- get(codec, path)
        yield assertTrue(served == made, served._1 == expected)

  def spec = suite("pre-encoded responses")(
    sameBothWays("/people/1", Status.Ok),
    sameBothWays("/people/9999", Status.NotFound),
    sameBothWays("/films/1", Status.Ok),
    sameBothWays("/films/9999", Status.NotFound),
    sameBothWays("/people", Status.Ok),
    sameBothWays("/people?page=2", Status.Ok),
    sameBothWays("/people?page=9", Status.Ok),
    sameBothWays("/people?page=99", Status.Ok),
    sameBothWays("/people?sortBy=name:ASC", Status.Ok),
    sameBothWays("/people?sortBy=height:DESC,name:ASC", Status.Ok),
    sameBothWays("/people?sortBy=garbage", Status.Ok),
    sameBothWays("/people?page=2&sortBy=name:ASC", Status.Ok),
    sameBothWays("/people?page=0", Status.BadRequest),
    sameBothWays("/people?page=abc", Status.BadRequest),
    sameBothWays("/people?page=1&page=2", Status.BadRequest)
  ).provide(Client.default) @@ TestAspect.withLiveClock
