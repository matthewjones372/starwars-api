package com.matthewjones372.http.api

import zio.*
import zio.http.*
import zio.test.*

/**
 * The pre-encoded route must answer exactly as the codec did, byte for byte: it
 * is an optimisation, and an optimisation that changes a response is a
 * behaviour change wearing a performance argument.
 *
 * Two real servers over the bundled data rather than a unit test of the map,
 * because what a caller receives is the status, the headers and the body
 * together.
 */
object PreEncodedSpec extends ZIOSpecDefault:

  // Each server's own `Server` layer, built into this scope rather than provided
  // around the effect that starts it: `provideSome` releases the layer when that
  // effect finishes, which takes the server down before a request arrives.
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

  // The expected status is named rather than inferred, so a miss that quietly
  // became a hit still fails: two servers agreeing on the wrong answer is the
  // one way this test could pass and mean nothing.
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
    sameBothWays("/films/9999", Status.NotFound)
  ).provide(Client.default) @@ TestAspect.withLiveClock
