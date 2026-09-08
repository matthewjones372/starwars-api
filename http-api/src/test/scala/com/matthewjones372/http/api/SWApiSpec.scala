package com.matthewjones372.http.api

import com.matthewjones372.data.{DataRepoError, SWDataRepo}
import com.matthewjones372.domain.{Character, Characters, Film, Films}
import com.matthewjones372.sorting.SortBy
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
    suite("getCharacters")(
      test("returns a set of people") {
        (for
          _ <- stub[SWDataRepo](_.getCharacters) {
                 ZIO.attempt(Characters(1, List(person))).orElseFail(DataRepoError.FilmsNotFound)
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
          _ <- stub[SWDataRepo](_.getCharacters) {
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
    suite("getCharacter")(
      test("returns a person when given a valid id") {
        (for
          _           <- stub[SWDataRepo](_.getCharacter)(ZIO.succeed(person))
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
          body        <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body.contains("C-3PO"))).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo],
          SWHttpServer.layer
        )
      },
      test("returns a 404 when the person is not found") {
        (for
          _ <- stub[SWDataRepo](_.getCharacter) {
                 ZIO.fail(DataRepoError.CharacterNotFound("Character not found", 99))
               }
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "99")))
        yield assertTrue(response.status == Status.NotFound)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[SWDataRepo],
          SWHttpServer.layer
        )
      },
      test("returns a server error when the repo fails for another reason") {
        (for
          _ <- stub[SWDataRepo](_.getCharacter) {
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
    ),
    suite("shortest path")(
      test("returns the chain of films connecting two characters") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response <- client(
                        testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1" / "path-to" / "3"))
                      )
          body <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"films\":2"),
          body.contains("Lobot"),
          body.contains("Boba Fett")
        )).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          ZLayer.succeed(repoWith(List(lobot, luke, bobaFett), List(empireStrikesBack, aNewHope))),
          SWHttpServer.layer
        )
      },
      test("returns a 404 when no chain of films connects the two characters") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response <- client(
                        testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1" / "path-to" / "2"))
                      )
        yield assertTrue(response.status == Status.NotFound)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          ZLayer.succeed(repoWith(List(lobot, bobaFett), List(empireStrikesBack, aNewHope))),
          SWHttpServer.layer
        )
      },
      test("returns a 404 when either character is unknown") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[SWHttpServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response <- client(
                        testRequest.copy(url = testRequest.url.path(Path.root / "people" / "99" / "path-to" / "1"))
                      )
        yield assertTrue(response.status == Status.NotFound)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          ZLayer.succeed(repoWith(List(lobot, luke, bobaFett), List(empireStrikesBack, aNewHope))),
          SWHttpServer.layer
        )
      }
    ),
    suite("path assembly")(
      test("drops the terminal entry, which repeats the film of the hop before it") {
        val path = com.matthewjones372.search
          .Path("Lobot", "Boba Fett", Some(Chunk(("Lobot", "ESB"), ("Luke", "ANH"), ("Boba Fett", "ANH"))))

        val assembled = SWHttpServer.toShortestPath("Lobot", "Boba Fett", path)

        assertTrue(
          assembled.films == 2,
          assembled.steps == List(PathStep("Lobot", "ESB"), PathStep("Luke", "ANH")),
          assembled.start == "Lobot",
          assembled.end == "Boba Fett"
        )
      },
      test("reports no steps when the start and target are the same character") {
        val path      = com.matthewjones372.search.Path("Luke", "Luke", Some(Chunk.empty))
        val assembled = SWHttpServer.toShortestPath("Luke", "Luke", path)

        assertTrue(assembled.films == 0, assembled.steps.isEmpty)
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
    Character(
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
      starships = None,
      url = "https://swapi.dev/api/species/2/"
    )

  // stubby matches on the method rather than its arguments, so ids need a real double here
  def repoWith(characters: List[Character], films: List[Film]): SWDataRepo =
    new SWDataRepo:
      def getFilm(id: Int) =
        ZIO.fromOption(films.lift(id - 1)).orElseFail(DataRepoError.FilmNotFound("Film not found", id))

      def getCharacter(id: Int) =
        ZIO.fromOption(characters.lift(id - 1)).orElseFail(DataRepoError.CharacterNotFound("Character not found", id))

      def getCharacters(from: Option[Int], fetchSize: Option[Int], sortBy: Option[List[SortBy]]) =
        ZIO.succeed(Characters(characters.size, characters))

      def getFilms(from: Option[Int], fetchSize: Option[Int]) =
        ZIO.succeed(Films(films.size, films))

  val empireStrikesBack = film.copy(title = "The Empire Strikes Back", url = "/films/5/")
  val aNewHope          = film.copy(title = "A New Hope", url = "/films/4/")

  val lobot    = person.copy(name = "Lobot", films = Set("/films/5/"))
  val luke     = person.copy(name = "Luke", films = Set("/films/5/", "/films/4/"))
  val bobaFett = person.copy(name = "Boba Fett", films = Set("/films/4/"))

  def requestToCorrectPort =
    for
      p    <- ZIO.serviceWith[Server](_.port)
      port <- p
    yield Request
      .get(url = URL.root.port(port))
