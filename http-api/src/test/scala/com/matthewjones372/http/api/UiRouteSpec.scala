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
      server <- ApiServer.default
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

  // Redirects are the point here, so this client is told not to follow them.
  private def locationOf(port: Int, path: String) =
    for
      client   <- ZIO.service[Client]
      response <- client(Request.get(URL.decode(s"http://localhost:$port$path").toOption.get)).disconnect
      location  = response.header(Header.Location).map(_.url.encode)
    yield location.map(response.status -> _)

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
          body.contains("<title>Character Graph</title>")
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
          (person, _, body) <- get(port, "/starwars/people/1")
          (docs, _, _)      <- get(port, "/docs/openapi")
        yield assertTrue(
          person == Status.Ok,
          docs == Status.Ok,
          body.contains("Luke Skywalker")
        )
    ,
    test("answers 404 for a universe it does not serve, whether or not it knows the name"):
      ZIO.scoped:
        for
          port                <- serving
          (unknown, _, uBody) <- get(port, "/startrek/people/1")
          (noData, _, nBody)  <- get(port, "/mcu/people/1")
          (noDataList, _, _)  <- get(port, "/mcu/people")
          (noDataGraph, _, _) <- get(port, "/mcu/graph/insights")
        yield assertTrue(
          unknown == Status.NotFound,
          noData == Status.NotFound,
          noDataList == Status.NotFound,
          noDataGraph == Status.NotFound,
          uBody.contains("startrek"),
          nBody.contains("mcu")
        )
    ,
    test("sends the paths the api answered on before the universe prefix to the default one"):
      ZIO.scoped:
        for
          port   <- serving
          person <- locationOf(port, "/people/1")
          people <- locationOf(port, "/people?page=2")
          films  <- locationOf(port, "/films/1")
          graph  <- locationOf(port, "/graph/insights")
        yield assertTrue(
          person == Some(Status.PermanentRedirect -> "/starwars/people/1"),
          people == Some(Status.PermanentRedirect -> "/starwars/people?page=2"),
          films == Some(Status.PermanentRedirect -> "/starwars/films/1"),
          graph == Some(Status.PermanentRedirect -> "/starwars/graph/insights")
        )
  ).provide(Client.default) @@ TestAspect.withLiveClock
