import com.matthewjones372.api.client.SWAPIClientService
import com.matthewjones372.http.api.SWHttpServer
import com.matthewjones372.search.SWGraph
import zio.*
import zio.Runtime.removeDefaultLoggers
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.*
import zio.logging.LogFormat
import zio.logging.backend.SLF4J

object ClientExample extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    (Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> Runtime.setUnhandledErrorLogLevel(LogLevel.Debug)) ++
      Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  def run =
    (for
      swapi                 <- ZIO.service[SWAPIClientService]
      (time, people)        <- swapi.getFilmsFromCharacters.timed
      _                     <- Console.printLine(s"There are ${people.size} people and it took ${time.toMillis} ms")
      (time2, films)        <- swapi.getFilms.timed
      _                     <- Console.printLine(s"There are ${films.size} films and it took ${time2.toMillis} ms")
      (time3, shortestPath) <- ZIO.succeed(SWGraph(people).bfs("Darth Maul", "Greedo")).timed
      _                     <- Console.printLine(s"bfs took ${time3.toMillis} ms")
      _                     <- Console.printLine(
             s"The shortest path between Darth Maul and Greedo is: ${shortestPath.map(_.length).getOrElse(0)} films"
           )
      _ <- Console.printLine(shortestPath.mkString)
    yield ExitCode.success)
      .provide(
        SWAPIClientService.default,
        Scope.default,
        Client.default
      )

object ServerExample extends ZIOAppDefault:

  /**
   * The port the platform assigns, and limits on what one connection may hold.
   *
   * A connection that opens and then says nothing costs a socket and holds it
   * for as long as it likes: with no idle timeout, which is the zio-http
   * default, a few hundred of those exhaust the server without ever sending a
   * request. The timeout is far longer than any honest client here needs, since
   * every response is served from memory.
   *
   * The header limits are lower than the defaults because this API reads no
   * cookies and no authorization, so nothing it serves needs headers that size.
   * None of this stops a flood; it stops a single connection being cheap to
   * hold open and expensive to hold.
   */
  private val serverConfig =
    ZLayer.fromZIO(
      System
        .envOrElse("PORT", "8080")
        .map(port =>
          Server.Config.default
            .port(port.toIntOption.getOrElse(8080))
            .idleTimeout(30.seconds)
            .maxHeaderSize(4096)
            .maxInitialLineLength(2048)
            .gracefulShutdownTimeout(5.seconds)
        )
    )

  def run = (for
    server <- SWHttpServer.default
    _      <- server.start
  yield ()).provide(
    serverConfig,
    Server.live,
    SLF4J.slf4j(LogFormat.colored),
    removeDefaultLoggers
  )
