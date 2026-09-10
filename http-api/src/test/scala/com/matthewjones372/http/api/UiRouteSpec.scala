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

  private def cacheControl(port: Int, path: String) =
    for
      client   <- ZIO.service[Client]
      response <- client(Request.get(URL.decode(s"http://localhost:$port$path").toOption.get))
    yield response.header(Header.CacheControl)

  def spec = suite("the browser UI")(
    test("is served as html at the root"):
      ZIO.scoped:
        for
          port                        <- serving
          (status, contentType, body) <- get(port, "/")
        yield assertTrue(
          status == Status.Ok,
          contentType.exists(_.mediaType == MediaType.text.html),
          body.contains("<title>Star Wars Character Graph</title>")
        )
    ,
    test("is revalidated rather than left to the browser's own guess"):
      // Without this the page carries a last-modified date from inside the jar
      // and no directive, which browsers read as licence to keep it for months
      // and so to serve a version every later deploy has replaced.
      ZIO.scoped:
        for
          port    <- serving
          control <- cacheControl(port, "/")
        yield assertTrue(control.contains(Header.CacheControl.NoCache))
    ,
    test("is not cached even though the api it serves is"):
      // The page is how a reader picks up a new deploy, so it must revalidate;
      // the data behind it never changes within one, so it need not.
      ZIO.scoped:
        for
          port <- serving
          page <- cacheControl(port, "/")
          data <- cacheControl(port, "/people/1")
        yield assertTrue(
          page.contains(Header.CacheControl.NoCache),
          data.exists {
            case Header.CacheControl.Multiple(values) => values.exists(_.isInstanceOf[Header.CacheControl.MaxAge])
            case other                                => other.isInstanceOf[Header.CacheControl.MaxAge]
          }
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
