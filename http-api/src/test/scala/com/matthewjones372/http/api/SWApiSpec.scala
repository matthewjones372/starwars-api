package com.matthewjones372.http.api

import com.matthewjones372.data.{DataRepoError, SWDataRepo}
import com.matthewjones372.domain.{Film, Films, People, Peoples}
import stubby.*
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.http.netty.server.NettyDriver
import zio.test.*
import zio.test.Assertion.*

object SWApiSpec extends ZIOSpecDefault:
  def spec = suite("SWApiSpec")(
    suite("getFilms")(
      test("returns a set of films") {
        {
          for
            client <- ZIO.service[Client]
            _ <- stub[SWDataRepo](_.getFilms) {
                   ZIO.attempt(Films(1, List(film))).orElseFail(DataRepoError.FilmsNotFound)
                 }
            swServer    <- ZIO.service[SWHttpServer]
            _           <- swServer.start.fork
            testRequest <- requestToCorrectPort
            response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "films")))
          yield assertTrue(response.status == Status.Ok)
        }.provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo],
          SWHttpServer.layer
        )
      },
      test("returns a server error when there is a issue with the data repo") {
        {
          for
            _ <- stub[SWDataRepo](_.getFilms) {
                   ZIO.fail(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
                 }
            client      <- ZIO.service[Client]
            swServer    <- ZIO.service[SWHttpServer]
            _           <- swServer.start.fork
            testRequest <- requestToCorrectPort
            response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "films")))
          yield assertTrue(response.status == Status.InternalServerError)
        }.provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo],
          SWHttpServer.layer
        )
      }
    ),
    suite("getPeople")(
      test("returns a set of people") {
        (for
          _ <- stub[SWDataRepo](_.getPeople) {
                 ZIO.attempt(Peoples(1, List(person))).orElseFail(DataRepoError.FilmsNotFound)
               }
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people")))
        yield assertTrue(
          response.status == Status.Ok
        )).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo],
          SWHttpServer.layer
        )
      },
      test("returns a server error when there is a issue with the data repo") {
        (for
          _ <- stub[SWDataRepo](_.getPeople) {
                 ZIO.fail(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
               }
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people")))
        yield assertTrue(response.status == Status.InternalServerError)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo],
          SWHttpServer.layer
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
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.Ok))
          .provideSome[Client & Driver](Scope.default, TestServer.layer, stubbed[SWDataRepo], SWHttpServer.layer)
      },
      test("returns a 404 when given an invalid id") {
        (for
          _ <- stub[SWDataRepo](_.getPerson(1)) {
                 ZIO.fail(DataRepoError.PersonNotFound("Person not found", 1))
               }
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.NotFound)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo],
          SWHttpServer.layer
        )
      },
      test("returns an appropriate error when there is a server error") {
        (for
          _ <- stub[SWDataRepo](_.getPeople) {
                 ZIO.fail(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
               }
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.InternalServerError)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo],
          SWHttpServer.layer
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
      gender = None,
      homeworld = None,
      films = Set("/films/1/?format=json", "/films/2/?format=json"),
      species = None,
      vehicles = None,
      starships = None
    )

  def requestToCorrectPort =
    for
      p    <- ZIO.serviceWith[Server](_.port)
      port <- p
    yield Request
      .get(url = URL.root.port(port))
