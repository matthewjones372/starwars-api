package com.jones
package api

import data.{DataRepoError, SWDataRepo}
import domain.Generators.*
import domain.{Film, People}

import stubby.*
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.http.netty.server.NettyDriver
import zio.test.*

object SWApiSpec extends ZIOSpecDefault:
  def spec = suite("SWApiSpec")(
    suite("getFilms")(
      test("returns a set of films") {
        {
          for
            client <- ZIO.service[Client]
            _ <- stub[SWDataRepo](_.getFilms) {
                   ZIO.attempt(Set(film)).orElseFail(DataRepoError.FilmsNotFound)
                 }
            swServer    <- SWHttpServer.default
            _           <- swServer.start.fork
            testRequest <- requestToCorrectPort
            response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "films")))
          yield assertTrue(response.status == Status.Ok)
        }.provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo]
        )
      },
      test("returns a server error when there is a issue with the data repo") {
        {
          for
            _ <- stub[SWDataRepo](_.getFilms) {
                   ZIO.fail(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
                 }
            client      <- ZIO.service[Client]
            swServer    <- SWHttpServer.default
            _           <- swServer.start.fork
            testRequest <- requestToCorrectPort
            response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "films")))
          yield assertTrue(response.status == Status.InternalServerError)
        }.provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo]
        )
      }
    ),
    suite("getPeople")(
      test("returns a set of people") {
        (for
          _ <- stub[SWDataRepo](_.getPeople) {
                 ZIO.attempt(Set(person)).orElseFail(DataRepoError.FilmsNotFound)
               }
          client      <- ZIO.service[Client]
          swServer    <- SWHttpServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people")))
        yield assertTrue(
          response.status == Status.Ok
        )).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo]
        )
      },
      test("returns a server error when there is a issue with the data repo") {
        (for
          _ <- stub[SWDataRepo](_.getPeople) {
                 ZIO.fail(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
               }
          client      <- ZIO.service[Client]
          swServer    <- SWHttpServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people")))
        yield assertTrue(response.status == Status.InternalServerError)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo]
        )
      }
    ),
    suite("getPeopleFrom")(
      test("returns a film when given a valid id") {
        (for
          _ <- stub[SWDataRepo](_.getPerson(1)) {
                 ZIO.attempt(person).orElseFail(DataRepoError.FilmsNotFound)
               }
          client      <- ZIO.service[Client]
          swServer    <- SWHttpServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.Ok))
          .provideSome[Client & Driver](Scope.default, TestServer.layer, stubbed[SWDataRepo])
      },
      test("returns a 404 when given an invalid id") {
        (for
          _ <- stub[SWDataRepo](_.getPerson(1)) {
                 ZIO.fail(DataRepoError.PersonNotFound("Person not found", 1))
               }
          client      <- ZIO.service[Client]
          swServer    <- SWHttpServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.NotFound)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo]
        )
      },
      test("returns an appropriate error when there is a server error") {
        (for
          _ <- stub[SWDataRepo](_.getPeople) {
                 ZIO.fail(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
               }
          client      <- ZIO.service[Client]
          swServer    <- SWHttpServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.InternalServerError)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo]
        )
      }
    )
  ).provide(
    ZLayer.succeed(Server.Config.default.onAnyOpenPort),
    Client.default,
    NettyDriver.customized,
    ZLayer.succeed(NettyConfig.defaultWithFastShutdown)
  )

  val film =
    Film(
      "The Phantom Menace",
      1,
      "worst film",
      "George Lucas",
      "",
      "",
      Set.empty,
      Set.empty,
      Set.empty,
      Set.empty,
      Set.empty,
      "",
      "",
      ""
    )

  val person =
    People(
      name = "C-3PO",
      height = Some(167),
      mass = Some(75),
      hairColor = "n/a",
      skinColor = "gold",
      eyeColor = "yellow",
      birthYear = "112BBY",
      gender = "n/a",
      homeworld = "https://swapi.dev/api/planets/1/",
      films = Set("/films/1/?format=json", "/films/2/?format=json"),
      species = Set("https://swapi.dev/api/species/2/"),
      vehicles = Set(),
      starships = Set()
    )

  def requestToCorrectPort =
    for
      p    <- ZIO.serviceWith[Server](_.port)
      port <- p
    yield Request
      .get(url = URL.root.port(port))
