package com.jones
package api

import domain.Film

import com.jones.api.SWAPIServerError.*
import com.jones.data.SWDataRepo
import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*

object SWServer:
  def default =
    (for
      dataRepo <- ZIO.service[SWDataRepo]
      swapi    <- ZIO.service[SWApi]
    yield SWServer(swapi)).provideSomeLayer(SWApi.layer)

private final case class SWServer(private val SWApi: SWApi):

  private val getPersonEndpoint =
    Endpoint(Method.GET / "people" / PathCodec.int("person"))
      .out[Film]
      .outErrors[SWAPIServerError](
        HttpCodec.error[PersonNotFound](Status.NotFound),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  private val getPersonHandler = getPersonEndpoint.implement { personId =>
    SWApi.getFilmFrom(personId).tapError(err => ZIO.logError(s"getPersonEndpoint error: $err")).catchAll {
      (err: SWAPIServerError) =>
        ZIO.fail(err)
    }
  }.sandbox

  private val openAPI       = OpenAPIGen.fromEndpoints(title = "Star Wars API", version = "1.0", getPersonEndpoint)
  private val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", openAPI)
  private val routes        = (getPersonHandler.toRoutes ++ swaggerRoutes) @@ Middleware.debug

  def start = Server.serve(routes)
