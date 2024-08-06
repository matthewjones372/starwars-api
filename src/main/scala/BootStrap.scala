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
      (time, people)        <- swapi.getFilmsFromPeople.timed
      _                     <- Console.printLine(s"There are ${people.size} people and it took ${time.toMillis} ms")
      (time2, films)        <- swapi.getFilms.timed
      _                     <- Console.printLine(s"There are ${films.size} films and it took ${time2.toMillis} ms")
      (time3, shortestPath) <- ZIO.succeed(SWGraph(people).bfs("Darth Maul", "Greedo")).timed
      _                     <- Console.printLine(s"bfs took ${time3.toMillis} ms")
      _ <- Console.printLine(
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

  def run = (for
    server <- SWHttpServer.default
    _      <- server.start
  yield ()).provide(
    Server.default,
    SLF4J.slf4j(LogFormat.colored),
    removeDefaultLoggers
  )
