package com.jones
package client

import zio.*
import zio.http.*
import zio.http.codec.QueryCodec
import zio.http.endpoint.Endpoint

import java.net.URI
import scala.io.Source

object ApiRequestResponseStubs:
  val baseUrl = "http://localhost"

  val testEnv =
    (Scope.default ++
      ZLayer.succeed(HttpClientConfig(URL.fromURI(new URI(baseUrl)).get)))
      >>> SWAPIService.default

  val film1Url  = "films/1/?format=json"
  val film2Url  = "films/2/?format=json"
  val personUrl = "people/1/?format=json"

  val personRequest = Request.get(personUrl)
  val filmRequest1  = Request.get(URL.decode(film1Url).toOption.get)
  val filmRequest2  = Request.get(URL.decode(film2Url).toOption.get)

  val personJson =
    Source.fromResource("people_json.json").getLines().mkString

  val personResponse = Response.json(
    personJson
  )

  val film1Json = Source.fromResource("film1_json.json").getLines().mkString

  val film1Response = Response.json(
    film1Json
  )

  val film2Json = Source.fromResource("film2_json.json").getLines().mkString

  val film2Response = Response.json(
    film2Json
  )

  val getPersonEndpoint =
    Endpoint(Method.GET / "people" / int("person") / "?format=json" / trailing)
      .out[String]

  def getFilmsEndpoint =
    Endpoint(Method.GET / "films" / int("filmId") / trailing)
      .query(QueryCodec.query("format"))
      .out[String]

  def getFilmSuccess = getFilmsEndpoint.implement { _ =>
    ZIO.succeed(film1Json)
  }

  def addCallWithClientError(state: Ref[Int]) =
    TestClient.addRoute(getPersonEndpoint.route -> handler {
      state.getAndUpdate(_ + 1).as {
        Response.status(Status.Unauthorized)
      }
    })

