package com.matthewjones372.http.api

import zio.*
import zio.http.*
import zio.test.*

object PreEncodedSpec extends ZIOSpecDefault:

  private def serving(preEncoded: Boolean): ZIO[Scope, Throwable, Int] =
    for
      env    <- Server.defaultWithPort(0).build
      server <- ApiServer.measuring(preEncoded)
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
    sameBothWays("/starwars/people/1", Status.Ok),
    sameBothWays("/starwars/people/9999", Status.NotFound),
    sameBothWays("/starwars/films/1", Status.Ok),
    sameBothWays("/starwars/films/9999", Status.NotFound),
    sameBothWays("/starwars/people", Status.Ok),
    sameBothWays("/starwars/people?page=2", Status.Ok),
    sameBothWays("/starwars/people?page=9", Status.Ok),
    sameBothWays("/starwars/people?page=99", Status.Ok),
    sameBothWays("/starwars/people?sortBy=name:ASC", Status.Ok),
    sameBothWays("/starwars/people?sortBy=height:DESC,name:ASC", Status.Ok),
    sameBothWays("/starwars/people?sortBy=garbage", Status.Ok),
    sameBothWays("/starwars/people?page=2&sortBy=name:ASC", Status.Ok),
    sameBothWays("/starwars/people?page=0", Status.BadRequest),
    sameBothWays("/starwars/people?page=abc", Status.BadRequest),
    sameBothWays("/starwars/people?page=1&page=2", Status.BadRequest)
  ).provide(Client.default) @@ TestAspect.withLiveClock
