package com.jones

import api.SWServer
import client.{HttpClientConfig, SWAPIClientService}
import data.SWDataRepo
import search.SWGraph

import zio.*
import zio.http.*

import java.net.URI

object BootStrapClientExample extends ZIOAppDefault:

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
        ZLayer.succeed(HttpClientConfig(URL.fromURI(new URI("https://swapi.dev/api")).get, 1000)),
        Scope.default,
        Client.default
      )

object BootStrapServerExample extends ZIOAppDefault:

  def run = (for
    server <- SWServer.default
    _      <- server.start
  yield ()).provide(SWDataRepo.layer, Server.default)
