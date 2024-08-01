package com.jones
package client

import domain.{Film, People, Peoples}

import zio.*
import zio.http.*
import zio.http.codec.QueryCodec
import zio.http.endpoint.Endpoint
import zio.json.*

import java.net.URI

object ApiRequestResponseStubs:
  val baseUrl = "http://localhost"

  val testEnv =
    (Scope.default ++
      ZLayer.succeed(HttpClientConfig(URL.fromURI(new URI(baseUrl)).get, 1000)))
      >>> SWAPIClientService.default

  val film1Url       = "films/1/?format=json"
  val film2Url       = "films/2/?format=json"
  val personUrl      = "people/1"
  val personPagedUrl = "people"

  extension (url: Either[Exception, URL])
    def addJsonQueryParam: Either[Exception, URL] =
      url.map(_.addQueryParam("format", "json"))

    def addPageQueryParam(page: Int): Either[Exception, URL] =
      url.map(_.addQueryParam("page", page.toString))

    def unsafeGet: URL =
      url.toOption.get

  def personPagedUrlWith(page: Int) =
    Request.get(
      URL.decode("people").addJsonQueryParam.addPageQueryParam(page).unsafeGet
    )

  val personRequest      = Request.get(URL.decode(personUrl).addJsonQueryParam.unsafeGet)
  val filmRequest1       = Request.get(URL.decode(film1Url).unsafeGet)
  val filmRequest2       = Request.get(URL.decode(film2Url).unsafeGet)
  val personPagedRequest = Request.get(URL.decode(personPagedUrl).addJsonQueryParam.unsafeGet)

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

  def personWithDiff(height: Int): People =
    person.copy(height = height.toString)

  val pagedPersonJson =
    Peoples(
      11,
      None,
      None,
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

  val pagedPersonResponse = Response(
    status = Status.Ok,
    body = Body.fromString(pagedPersonJson.toJson)
  )

  val personResponse = Response(
    status = Status.Ok,
    body = Body.fromString(person.toJson)
  )

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

  val film1Response = Response(
    status = Status.Ok,
    body = Body.fromString(film1.toJson)
  )

  val film2 = Film(
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

  val film2Response = Response(status = Status.Ok, body = Body.fromString(film2.toJson))

  val getPersonEndpoint =
    Endpoint(Method.GET / "people" / int("person"))
      .query(QueryCodec.query("format"))
      .out[String]

  def getFilmsEndpoint =
    Endpoint(Method.GET / "films" / int("filmId") / trailing)
      .query(QueryCodec.query("format"))
      .out[String]

  def getFilmSuccess = getFilmsEndpoint.implement { _ =>
    ZIO.succeed(film1.toJson)
  }

  def addCallWithClientError(state: Ref[Int]) =
    TestClient.addRoute(getPersonEndpoint.route -> handler {
      state.getAndUpdate(_ + 1).as {
        Response.status(Status.Unauthorized)
      }
    })
