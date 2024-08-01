package com.jones
package api

import api.SWAPIServerError.*
import data.SWDataRepo
import domain.{Film, People}

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*

object SWHttpServer:
  def default =
    (for
      dataRepo <- ZIO.service[SWDataRepo]
      swapi    <- ZIO.service[SWApi]
    yield SWHttpServer(swapi)).provideSomeLayer(SWApi.layer)

private final case class SWHttpServer(private val SWApi: SWApi):

  private val getPersonEndpoint =
    Endpoint(Method.GET / "people" / PathCodec.int("person"))
      .out[People]
      .outErrors[SWAPIServerError](
        HttpCodec.error[PersonNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  private val getPersonHandler = getPersonEndpoint.implement { personId =>
    SWApi.getPerson(personId).tapError(err => ZIO.logError(s"getPersonEndpoint error: $err")).catchAll {
      (err: SWAPIServerError) =>
        ZIO.fail(err)
    }
  }.sandbox

  private val getPeopleEndpoint =
    Endpoint(Method.GET / "people")
      .out[Set[People]]
      .outErrors[SWAPIServerError](
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  private val getPeopleHandler = getPeopleEndpoint.implement { _ =>
    SWApi.getPeople.tapError(err => ZIO.logError(s"getPeopleEndpoint error: $err")).catchAll {
      case (err: SWAPIServerError) =>
        ZIO.fail(err)
    }
  }.sandbox

  private val getFilmsEndpoint =
    Endpoint(Method.GET / "films")
      .out[Set[Film]]
      .outErrors[SWAPIServerError](
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  private def getFilmHandler = getFilmsEndpoint.implement { _ =>
    SWApi.getFilms.catchAll { case (err: SWAPIServerError) =>
      ZIO.fail(err)
    }
  }.sandbox

  private val openAPI =
    OpenAPIGen.fromEndpoints(
      title = "Star Wars API",
      version = "1.0",
      getPersonEndpoint,
      getPeopleEndpoint,
      getFilmsEndpoint
    )
  private val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", openAPI)
  private val routes =
    (getPersonHandler.toRoutes ++
      getPeopleHandler.toRoutes ++
      getFilmHandler.toRoutes ++
      swaggerRoutes) @@ Middleware.debug

  def start = Server.serve(routes)
