package com.jones
package api

import api.mocking.MockSWDataRepo
import domain.Film

import com.jones.data.DataRepoError
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.http.netty.server.NettyDriver
import zio.mock.Expectation.*
import zio.test.*
import zio.test.Assertion.*

object SWApiSpec extends ZIOSpecDefault:
  def spec = suite("SWApiSpec")(
    suite("getFilmFrom")(
      test("returns a film when given a valid id") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- SWServer.default
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.Ok)).provideSome[Client & Driver](
          MockSWDataRepo.GetFilm(
            assertion = equalTo(1),
            result = value(film1)
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
          MockSWDataRepo.GetFilm(
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
          MockSWDataRepo.GetFilm(
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

  def requestToCorrectPort =
    for
      p    <- ZIO.serviceWith[Server](_.port)
      port <- p
    yield Request
      .get(url = URL.root.port(port))

  val film1 = Film(
    "The Empire Strikes Back",
    5,
    "opening",
    "Irvin Kershner",
    "Gary Kurtz",
    "Rick McCallum",
    Set(),
    Set(),
    Set(
      "https://swapi.dev/api/planets/4/",
      "https://swapi.dev/api/planets/5/",
      "https://swapi.dev/api/planets/6/",
      "https://swapi.dev/api/planets/27/"
    ),
    Set(
      "https://swapi.dev/api/starships/11/",
      "https://swapi.dev/api/starships/22/",
      "https://swapi.dev/api/starships/15/",
      "https://swapi.dev/api/starships/10/",
      "https://swapi.dev/api/starships/3/",
      "https://swapi.dev/api/starships/23/",
      "https://swapi.dev/api/starships/12/",
      "https://swapi.dev/api/starships/21/",
      "https://swapi.dev/api/starships/17/"
    ),
    Set(
      "https://swapi.dev/api/vehicles/16/",
      "https://swapi.dev/api/vehicles/14/",
      "https://swapi.dev/api/vehicles/19/",
      "https://swapi.dev/api/vehicles/18/",
      "https://swapi.dev/api/vehicles/20/",
      "https://swapi.dev/api/vehicles/8/"
    ),
    "",
    "2014-12-12T11:26:24.656000Z",
    "2014-12-15T13:07:53.386000Z"
  )
