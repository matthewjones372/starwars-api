package com.jones
package api

import api.mocking.MockSWDataRepo
import data.DataRepoError
import domain.Generators.*

import com.jones.domain.People
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.http.netty.server.NettyDriver
import zio.mock.Expectation.*
import zio.test.*
import zio.test.Assertion.*

object SWApiSpec extends ZIOSpecDefault:
  def spec = suite("SWApiSpec")(
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

  val person =
    People(
      "C-3PO",
      "167",
      "75",
      "n/a",
      "gold",
      "yellow",
      "112BBY",
      "n/a",
      "https://swapi.dev/api/planets/1/",
      Set("/films/1/?format=json", "/films/2/?format=json"),
      Set("https://swapi.dev/api/species/2/"),
      Set(),
      Set()
    )

  def requestToCorrectPort =
    for
      p    <- ZIO.serviceWith[Server](_.port)
      port <- p
    yield Request
      .get(url = URL.root.port(port))
