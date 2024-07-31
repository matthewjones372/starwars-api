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
      swapi          <- ZIO.service[SWAPIClientService]
      (time, people) <- swapi.getFilmsFromPeople.timed
      _              <- Console.printLine(s"There are ${people.size} people and it took ${time.toMillis} ms")

      _ <- ZIO.succeed(people.filter(_._2.size == 1).foreach { case (name, films) =>
             println(s"$name appeared in ${films.size} films")
           })
      shortestPath <- ZIO.succeed(SWGraph(people).bfs("Darth Maul", "Greedo"))
      _            <- Console.printLine(s"Shortest path between Ben Quadinaros and Mon Mothma is: $shortestPath")
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
