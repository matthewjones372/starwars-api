import com.matthewjones372.api.client.{ApiClient, HttpClientConfig}
import com.matthewjones372.data.SWDataRepo
import com.matthewjones372.domain.{Film, People}
import com.matthewjones372.http.api.SWHttpServer
import com.matthewjones372.search.SWGraph
import zio.*
import zio.http.*

import java.net.URI

object BootStrapClientExample extends ZIOAppDefault:

  def run =
    (for
      swapi                                  <- ZIO.service[ApiClient]
      (time, people: Map[People, Set[Film]]) <- swapi.getFilmsFromPeople.timed
      _                                      <- Console.printLine(s"There are ${people.size} people and it took ${time.toMillis} ms")
      (time2, films)                         <- swapi.getFilms.timed
      _                                      <- Console.printLine(s"There are ${films.size} films and it took ${time2.toMillis} ms")
      peopleToFilm                            = people.map { case (person, films) => (person.name, films.map(_.title)) }
      (time3, shortestPath)                  <- ZIO.succeed(SWGraph(peopleToFilm).bfs("Darth Maul", "Greedo")).timed
      _                                      <- Console.printLine(s"bfs took ${time3.toMillis} ms")
      _ <- Console.printLine(
             s"The shortest path between Darth Maul and Greedo is: ${shortestPath.map(_.length).getOrElse(0)} films"
           )
      _ <- Console.printLine(shortestPath.mkString)
    yield ExitCode.success)
      .provide(
        ApiClient.live,
        ZLayer.succeed(HttpClientConfig(URL.fromURI(new URI("https://swapi.dev/api")).get, 1000)),
        Scope.default,
        Client.default
      )

object BootStrapServerExample extends ZIOAppDefault:

  def run = (for
    server <- SWHttpServer.default
    _      <- server.start
  yield ()).provide(SWDataRepo.layer, Server.default)
