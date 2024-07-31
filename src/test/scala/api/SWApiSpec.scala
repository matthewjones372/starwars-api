package com.jones
package api

import api.mocking.MockSWDataRepo
import data.DataRepoError
import domain.Generators.*
import domain.{Film, People}

import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.http.netty.server.NettyDriver
import zio.mock.Expectation.*
import zio.test.*
import zio.test.Assertion.*

object SWApiSpec extends ZIOSpecDefault:
  def spec = suite("SWApiSpec")(
    suite("getFilms")(
      test("returns a set of films") {
        {
          for
            client      <- ZIO.service[Client]
            swServer    <- SWServer.default
            _           <- swServer.start.fork
            testRequest <- requestToCorrectPort
            response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "films")))
          yield assertTrue(response.status == Status.Ok)
        }.provideSome[Client & Driver](
          MockSWDataRepo.GetFilms(
            assertion = anything,
            result = value(Set(film))
          ),
          Scope.default,
          TestServer.layer
        )
      },
      test("returns a server error when there is a issue with the data repo") {
        {
          for
            client      <- ZIO.service[Client]
            swServer    <- SWServer.default
            _           <- swServer.start.fork
            testRequest <- requestToCorrectPort
            response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "films")))
          yield assertTrue(response.status == Status.InternalServerError)
        }.provideSome[Client & Driver](
          MockSWDataRepo.GetFilms(
            assertion = anything,
            result = failure(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
          ),
          Scope.default,
          TestServer.layer
        )
      }
    ),
    suite("getPeople")(
      test("returns a set of people") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- SWServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people")))
        yield assertTrue(
          response.status == Status.Ok
        )).provideSome[Client & Driver](
          MockSWDataRepo.GetPeople(
            assertion = anything,
            result = value(Set(person))
          ),
          Scope.default,
          TestServer.layer
        )
      },
      test("returns a server error when there is a issue with the data repo") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- SWServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people")))
        yield assertTrue(response.status == Status.InternalServerError)).provideSome[Client & Driver](
          MockSWDataRepo.GetPeople(
            assertion = anything,
            result = failure(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
          ),
          Scope.default,
          TestServer.layer
        )
      }
    ),
    suite("getPeopleFrom")(
      test("returns a film when given a valid id") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- SWServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.Ok)).provideSome[Client & Driver](
          MockSWDataRepo.GetPerson(
            assertion = equalTo(1),
            result = value(person)
          ),
          Scope.default,
          TestServer.layer
        )
      },
      test("returns a 404 when given an invalid id") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- SWServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.NotFound)).provideSome[Client & Driver](
          MockSWDataRepo.GetPerson(
            assertion = equalTo(1),
            result = failure(DataRepoError.PersonNotFound("Person not found", 1))
          ),
          Scope.default,
          TestServer.layer
        )
      },
      test("returns an appropriate error when there is a server error") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- SWServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.InternalServerError)).provideSome[Client & Driver](
          MockSWDataRepo.GetPerson(
            assertion = equalTo(1),
            result = failure(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
          ),
          Scope.default,
          TestServer.layer
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
      height = "167",
      mass = "75",
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
