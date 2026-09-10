package com.matthewjones372.http.api

import zio.*
import zio.http.*
import zio.test.*

/**
 * The UI is served from the classpath, which is the part worth a test: the page
 * itself is exercised in a browser, but whether the packaged artifact can still
 * find `web/index.html` is a packaging question that only shows up at runtime.
 */
object UiRouteSpec extends ZIOSpecDefault:

  private def serving: ZIO[Scope, Throwable, Int] =
    for
      env    <- Server.defaultWithPort(0).build
      server <- SWHttpServer.default
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

  def spec = suite("the browser UI")(
    test("is served as html at the root"):
      ZIO.scoped:
        for
          port                    <- serving
          (status, contentType, body) <- get(port, "/")
        yield assertTrue(
          status == Status.Ok,
          contentType.exists(_.mediaType == MediaType.text.html),
          body.contains("<title>Star Wars Character Graph</title>")
        )
    ,
    test("does not shadow the api it is served alongside"):
      ZIO.scoped:
        for
          port              <- serving
          (person, _, body) <- get(port, "/people/1")
          (docs, _, _)      <- get(port, "/docs/openapi")
        yield assertTrue(
          person == Status.Ok,
          docs == Status.Ok,
          body.contains("Luke Skywalker")
        )
  ).provide(Client.default) @@ TestAspect.withLiveClock
