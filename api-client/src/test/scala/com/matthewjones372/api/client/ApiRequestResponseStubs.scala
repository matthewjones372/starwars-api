package com.matthewjones372.api.client

import com.matthewjones372.domain.*
import com.matthewjones372.http.api.SWHttpServer
import zio.*
import zio.http.*
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

object ApiRequestResponseStubs:
  lazy val baseUrl = "http://localhost"

  lazy val film1Url       = "films/1/?format=json"
  lazy val film2Url       = "films/2/?format=json"
  lazy val personUrl      = "people/1"
  lazy val personPagedUrl = "people"
  lazy val filmPagedUrl   = "films"

  extension (url: Either[Exception, URL])
    def addJsonQueryParam: Either[Exception, URL] =
      url.map(_.addQueryParam("format", "json"))

    def addPageQueryParam(page: Int): Either[Exception, URL] =
      url.map(_.addQueryParam("page", page.toString))

    def unsafeGet: URL =
      url.toOption.get

  def personPagedUrlWith(page: Int) =
    Request.get(
      URL.decode(personPagedUrl).addJsonQueryParam.addPageQueryParam(page).unsafeGet
    )

  def filmPagedUrlWith(page: Int) =
    Request.get(
      URL.decode(filmPagedUrl).addJsonQueryParam.addPageQueryParam(page).unsafeGet
    )

  val personRequest      = Request.get(URL.decode(personUrl).addJsonQueryParam.unsafeGet)
  val filmRequest1       = Request.get(URL.decode(film1Url).unsafeGet)
  val filmRequest2       = Request.get(URL.decode(film2Url).unsafeGet)
  val personPagedRequest = Request.get(URL.decode(personPagedUrl).addJsonQueryParam.unsafeGet)
  val filmPagedRequest   = Request.get(URL.decode(filmPagedUrl).addJsonQueryParam.unsafeGet)

  val person =
    People(
      "C-3PO",
      Some(167),
      Some(75),
      "n/a",
      "gold",
      "yellow",
      "112BBY",
      None,
      None,
      Set("/films/1/?format=json", "/films/2/?format=json"),
      None,
      None,
      None,
      ""
    )

  def personWithDiff(name: Int): People =
    person.copy(name = name.toString, films = Set(s"http://localhost/$name"))

  def filmWithDiff(title: String): Film =
    film1.copy(title = title)

  val pagedFilmJson = Films(
    11,
    results = List(
      filmWithDiff("1"),
      filmWithDiff("2"),
      filmWithDiff("3"),
      filmWithDiff("4"),
      filmWithDiff("5"),
      filmWithDiff("6"),
      filmWithDiff("7"),
      filmWithDiff("8"),
      filmWithDiff("9"),
      filmWithDiff("10"),
      filmWithDiff("11"),
      filmWithDiff("12")
    )
  )

  val pagedFilmResponse = Response(
    status = Status.Ok,
    body = Body.from[Films](pagedFilmJson)
  )

  val pagedPersonJson =
    Peoples(
      11,
      results = List(
        personWithDiff(1),
        personWithDiff(2),
        personWithDiff(3),
        personWithDiff(4),
        personWithDiff(5),
        personWithDiff(6),
        personWithDiff(7),
        personWithDiff(8),
        personWithDiff(9),
        personWithDiff(10),
        personWithDiff(11),
        personWithDiff(12)
      )
    )

  lazy val pagedPersonResponse = Response(
    status = Status.Ok,
    body = Body.from[Peoples](pagedPersonJson)
  )

  lazy val personResponse = Response(
    status = Status.Ok,
    body = Body.from(person)
  )

  lazy val film1 = Film(
    title = "The Empire Strikes Back",
    episodeId = 5,
    openingCrawl = "opening",
    director = "Irvin Kershner",
    producer = "Gary Kurtz",
    releaseDate = "Rick McCallum",
    characters = Set("John", "marty"),
    planets = Set("Earth"),
    starships = Set(
      "https://swapi.dev/api/planets/4/",
      "https://swapi.dev/api/planets/5/",
      "https://swapi.dev/api/planets/6/",
      "https://swapi.dev/api/planets/27/"
    ),
    vehicles = Set(
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
    species = Set(
      "https://swapi.dev/api/vehicles/16/",
      "https://swapi.dev/api/vehicles/14/",
      "https://swapi.dev/api/vehicles/19/",
      "https://swapi.dev/api/vehicles/18/",
      "https://swapi.dev/api/vehicles/20/",
      "https://swapi.dev/api/vehicles/8/"
    ),
    created = "",
    edited = "2014-12-12T11:26:24.656000Z",
    url = "2014-12-15T13:07:53.386000Z"
  )

  lazy val film1Response = Response(
    status = Status.Ok,
    body = Body.from(film1)
  )

  lazy val film2 = Film(
    "A New Hope",
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

  lazy val film2Response = Response(status = Status.Ok, body = Body.from(film2))

  def getFilmSuccess = SWHttpServer.getFilmEndpoint.implement { _ =>
    ZIO.succeed(film1)
  }

  def addCallWithClientError(state: Ref[Int]) =
    TestClient.addRoute(SWHttpServer.getPersonEndpoint.route -> handler {
      state.getAndUpdate(_ + 1).as {
        Response.status(Status.Unauthorized)
      }
    })
