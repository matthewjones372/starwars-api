package com.matthewjones372.http.api

import com.matthewjones372.data.{DataRepoError, DataRepo}
import com.matthewjones372.domain.*
import com.matthewjones372.search.Graph
import com.matthewjones372.sorting.SortBy
import stubby.*
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.http.netty.server.NettyDriver
import zio.test.*

object ApiServerSpec extends ZIOSpecDefault:
  def spec = suite("ApiServerSpec")(
    suite("getFilms")(
      test("returns a set of films") {
        {
          for
            client <- ZIO.service[Client]
            _      <- stub[DataRepo](_.getFilms) {
                   ZIO.attempt(Films(1, List(film))).orElseFail(DataRepoError.FilmsNotFound)
                 }
            swServer    <- ZIO.service[ApiServer]
            _           <- swServer.start.fork
            testRequest <- requestToCorrectPort
            response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "films")))
          yield assertTrue(response.status == Status.Ok)
        }.provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[DataRepo],
          ApiServer.layer
        )
      },
      test("returns a server error when there is a issue with the data repo") {
        {
          for
            _ <- stub[DataRepo](_.getFilms) {
                   ZIO.fail(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
                 }
            client      <- ZIO.service[Client]
            swServer    <- ZIO.service[ApiServer]
            _           <- swServer.start.fork
            testRequest <- requestToCorrectPort
            response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "films")))
          yield assertTrue(response.status == Status.InternalServerError)
        }.provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[DataRepo],
          ApiServer.layer
        )
      }
    ),
    suite("getCharacters")(
      test("returns a set of people") {
        (for
          _ <- stub[DataRepo](_.getCharacters) {
                 ZIO.attempt(Characters(1, List(person))).orElseFail(DataRepoError.FilmsNotFound)
               }
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people")))
        yield assertTrue(
          response.status == Status.Ok
        )).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[DataRepo],
          ApiServer.layer
        )
      },
      test("returns a server error when there is a issue with the data repo") {
        (for
          _ <- stub[DataRepo](_.getCharacters) {
                 ZIO.fail(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
               }
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people")))
        yield assertTrue(response.status == Status.InternalServerError)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[DataRepo],
          ApiServer.layer
        )
      }
    ),
    suite("getCharacter")(
      test("returns a person when given a valid id") {
        (for
          _           <- stub[DataRepo](_.getCharacter)(ZIO.succeed(person))
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
          body        <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body.contains("C-3PO"))).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[DataRepo],
          ApiServer.layer
        )
      },
      test("returns a 404 when the person is not found") {
        (for
          _ <- stub[DataRepo](_.getCharacter) {
                 ZIO.fail(DataRepoError.CharacterNotFound("Character not found", EntityId(99)))
               }
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "99")))
        yield assertTrue(response.status == Status.NotFound)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[DataRepo],
          ApiServer.layer
        )
      },
      test("returns a server error when the repo fails for another reason") {
        (for
          _ <- stub[DataRepo](_.getCharacter) {
                 ZIO.fail(DataRepoError.UnexpectedError("Server error", new RuntimeException("BOOM!")))
               }
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1")))
        yield assertTrue(response.status == Status.InternalServerError)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          stubbed[DataRepo],
          ApiServer.layer
        )
      }
    ),
    suite("shortest path")(
      test("returns the chain of films connecting two characters") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(
                        testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1" / "path-to" / "3"))
                      )
          body <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"films\":2"),
          body.contains("Lobot"),
          body.contains("Boba Fett"),
          body.contains("\"chains\":1"),
          body.contains("\"alternatives\":[]")
        )).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          ZLayer.succeed(repoWith(List(lobot, luke, bobaFett), List(empireStrikesBack, aNewHope))),
          ApiServer.layer
        )
      },
      test("carries every equally short chain, not only the one it leads with") {
        val leia = person.copy(name = "Leia", films = Set("/films/5/", "/films/4/"))
        (for
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(
                        testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1" / "path-to" / "4"))
                      )
          body <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          // Lobot reaches Boba Fett through Luke or through Leia, both in two hops.
          body.contains("\"chains\":2"),
          body.contains("Luke"),
          body.contains("Leia")
        )).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          ZLayer.succeed(repoWith(List(lobot, luke, leia, bobaFett), List(empireStrikesBack, aNewHope))),
          ApiServer.layer
        )
      },
      test("returns a 404 when no chain of films connects the two characters") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(
                        testRequest.copy(url = testRequest.url.path(Path.root / "people" / "1" / "path-to" / "2"))
                      )
        yield assertTrue(response.status == Status.NotFound)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          ZLayer.succeed(repoWith(List(lobot, bobaFett), List(empireStrikesBack, aNewHope))),
          ApiServer.layer
        )
      },
      test("returns a 404 when either character is unknown") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(
                        testRequest.copy(url = testRequest.url.path(Path.root / "people" / "99" / "path-to" / "1"))
                      )
        yield assertTrue(response.status == Status.NotFound)).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          ZLayer.succeed(repoWith(List(lobot, luke, bobaFett), List(empireStrikesBack, aNewHope))),
          ApiServer.layer
        )
      }
    ),
    suite("graph insights")(
      test("ranks the cast by how many co-stars each character has") {
        (for
          client      <- ZIO.service[Client]
          swServer    <- ZIO.service[ApiServer]
          _           <- swServer.start.fork
          testRequest <- requestToCorrectPort
          response    <- client(testRequest.copy(url = testRequest.url.path(Path.root / "graph" / "insights")))
          body        <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"characters\":3"),
          body.contains("\"films\":2"),
          body.contains("\"pairs\":2"),
          body.contains("\"diameter\":2"),
          body.contains("\"clusters\":1"),
          // Luke shares a film with both of the others, so he outranks them.
          body.indexOf("Luke") < body.indexOf("Lobot"),
          body.contains("\"exclusiveCast\":1")
        )).provideSome[Client & Driver](
          Scope.default,
          TestServer.layer,
          ZLayer.succeed(repoWith(List(lobot, luke, bobaFett), List(empireStrikesBack, aNewHope))),
          ApiServer.layer
        )
      }
    ),
    suite("insight assembly")(
      test("ranks the most connected character first and the least connected last") {
        val insights = ApiServer.toGraphInsights(
          Graph(
            Map(
              "Lobot"     -> Set("The Empire Strikes Back"),
              "Luke"      -> Set("The Empire Strikes Back", "A New Hope"),
              "Boba Fett" -> Set("A New Hope")
            )
          ).connectivity
        )

        assertTrue(
          insights.connections.head == CharacterConnections("Luke", 2, 2, 2, 1.0),
          insights.connections.last == CharacterConnections("Lobot", 1, 1, 2, 1.5),
          insights.ensembles == List(FilmEnsemble("A New Hope", 2, 1), FilmEnsemble("The Empire Strikes Back", 2, 1))
        )
      },
      test("rounds the separations, which carry more digits than they mean") {
        val insights = ApiServer.toGraphInsights(
          Graph(
            Map(
              "Lobot"     -> Set("The Empire Strikes Back"),
              "Luke"      -> Set("The Empire Strikes Back", "A New Hope"),
              "Boba Fett" -> Set("A New Hope"),
              "Lando"     -> Set("The Empire Strikes Back")
            )
          ).connectivity
        )

        assertTrue(
          insights.averageSeparation == 1.33,
          insights.density == 0.67,
          insights.connections.forall(connection => connection.averageSeparation * 100 % 1 == 0)
        )
      }
    ),
    suite("path assembly")(
      test("drops the terminal entry, which repeats the film of the hop before it") {
        val path = com.matthewjones372.search
          .Path("Lobot", "Boba Fett", Some(Chunk(("Lobot", "ESB"), ("Luke", "ANH"), ("Boba Fett", "ANH"))))

        val assembled = ApiServer.toShortestPath("Lobot", "Boba Fett", List(path), 1)

        assertTrue(
          assembled.films == 2,
          assembled.steps == List(PathStep("Lobot", "ESB"), PathStep("Luke", "ANH")),
          assembled.start == "Lobot",
          assembled.end == "Boba Fett",
          assembled.alternatives.isEmpty,
          assembled.chains == 1
        )
      },
      test("reports no steps when the start and target are the same character") {
        val path      = com.matthewjones372.search.Path("Luke", "Luke", Some(Chunk.empty))
        val assembled = ApiServer.toShortestPath("Luke", "Luke", List(path), 1)

        assertTrue(assembled.films == 0, assembled.steps.isEmpty)
      },
      test("leads with the first chain and carries the rest as alternatives") {
        val throughLuke = com.matthewjones372.search
          .Path("Lobot", "Boba Fett", Some(Chunk(("Lobot", "ESB"), ("Luke", "ANH"), ("Boba Fett", "ANH"))))
        val throughLeia = com.matthewjones372.search
          .Path("Lobot", "Boba Fett", Some(Chunk(("Lobot", "ESB"), ("Leia", "ANH"), ("Boba Fett", "ANH"))))

        val assembled = ApiServer.toShortestPath("Lobot", "Boba Fett", List(throughLuke, throughLeia), 2)

        assertTrue(
          assembled.steps == List(PathStep("Lobot", "ESB"), PathStep("Luke", "ANH")),
          assembled.alternatives == List(Chain(List(PathStep("Lobot", "ESB"), PathStep("Leia", "ANH")))),
          assembled.chains == 2
        )
      },
      test("says how many chains there are even when it carries fewer") {
        val path = com.matthewjones372.search
          .Path("Lobot", "Boba Fett", Some(Chunk(("Lobot", "ESB"), ("Luke", "ANH"), ("Boba Fett", "ANH"))))

        val assembled = ApiServer.toShortestPath("Lobot", "Boba Fett", List(path), 24)

        assertTrue(assembled.alternatives.isEmpty, assembled.chains == 24)
      }
    ),
    suite("refined request parameters")(
      test("rejects a page below the first one rather than serving an empty page") {
        for
          people <- statusOf("/people?page=0")
          films  <- statusOf("/films?page=-1")
        yield assertTrue(people == Status.BadRequest, films == Status.BadRequest)
      },
      test("serves the first page") {
        for status <- statusOf("/people?page=1")
        yield assertTrue(status == Status.Ok)
      },
      test("rejects an entity id below the first one without reaching the repo") {
        for
          person <- statusOf("/people/0")
          film   <- statusOf("/films/0")
        yield assertTrue(person == Status.BadRequest, film == Status.BadRequest)
      }
    ).provideSome[Client & Driver](
      Scope.default,
      TestServer.layer,
      ZLayer.succeed(repoWith(List(lobot), List(empireStrikesBack))),
      ApiServer.layer
    ) @@ TestAspect.sequential
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
      "",
      None
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
  def repoWith(characters: List[Character], films: List[Film]): DataRepo =
    new DataRepo:
      def getFilm(id: EntityId) =
        ZIO.fromOption(films.lift(id - 1)).orElseFail(DataRepoError.FilmNotFound("Film not found", id))

      def getCharacter(id: EntityId) =
        ZIO.fromOption(characters.lift(id - 1)).orElseFail(DataRepoError.CharacterNotFound("Character not found", id))

      def getCharacters(from: Option[PageNumber], fetchSize: Option[PageSize], sortBy: Option[List[SortBy]]) =
        ZIO.succeed(Characters(characters.size, characters))

      def getFilms(from: Option[PageNumber], fetchSize: Option[PageSize]) =
        ZIO.succeed(Films(films.size, films))

  val empireStrikesBack = film.copy(title = "The Empire Strikes Back", url = "/films/5/")
  val aNewHope          = film.copy(title = "A New Hope", url = "/films/4/")

  val lobot    = person.copy(name = "Lobot", films = Set("/films/5/"))
  val luke     = person.copy(name = "Luke", films = Set("/films/5/", "/films/4/"))
  val bobaFett = person.copy(name = "Boba Fett", films = Set("/films/4/"))

  // The server is started per suite, so each request has to find the port it landed on.
  def statusOf(target: String) =
    for
      client   <- ZIO.service[Client]
      swServer <- ZIO.service[ApiServer]
      _        <- swServer.start.fork
      request  <- requestToCorrectPort
      url      <- ZIO.fromEither(URL.decode(target))
      response <- client(request.copy(url = request.url.path(url.path).addQueryParams(url.queryParams)))
    yield response.status

  def requestToCorrectPort =
    for
      p    <- ZIO.serviceWith[Server](_.port)
      port <- p
    yield Request
      .get(url = URL.root.port(port))
