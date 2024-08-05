package com.matthewjones372.http.api

import SWAPIServerError.*
import com.matthewjones372.data.{DataRepoError, SWDataRepo}
import com.matthewjones372.domain.{Film, People}
import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.endpoint.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.openapi.*

trait SWHttpServer:
  def start: URIO[Server, Nothing]

object SWHttpServer:
  def default =
    (for dataRepo <- ZIO.service[SWDataRepo]
    yield SWHttpServerImpl(dataRepo)).provideSomeLayer(SWDataRepo.layer)

  def layer = ZLayer.fromFunction(SWHttpServerImpl.apply)

  val getPersonEndpoint =
    Endpoint(Method.GET / "people" / PathCodec.int("person"))
      .out[People]
      .outErrors[SWAPIServerError](
        HttpCodec.error[PersonNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getPeopleEndpoint =
    Endpoint(Method.GET / "people")
      .out[Set[People]]
      .outErrors[SWAPIServerError](
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getFilmsEndpoint =
    Endpoint(Method.GET / "films")
      .out[Set[Film]]
      .outErrors[SWAPIServerError](
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  private val endPoints =
    Chunk(getPersonEndpoint, getPeopleEndpoint, getFilmsEndpoint)

  val openAPI =
    OpenAPIGen.fromEndpoints(
      title = "Star Wars API",
      version = "1.0",
      endPoints
    )

private final case class SWHttpServerImpl(private val dataRepo: SWDataRepo) extends SWHttpServer:

  private val getPersonHandler = SWHttpServer.getPersonEndpoint.implement { personId =>
    dataRepo
      .getPerson(personId)
      .catchAll {
        case DataRepoError.PersonNotFound(message, personId) =>
          ZIO.fail(PersonNotFound(message, personId))
        case err =>
          ZIO.fail(UnexpectedError(err.getMessage))
      }
  }.sandbox

  private val getPeopleHandler = SWHttpServer.getPeopleEndpoint.implement { _ =>
    dataRepo.getPeople.catchAll { err =>
      ZIO.fail(UnexpectedError(err.getMessage))
    }
  }.sandbox

  private def getFilmHandler = SWHttpServer.getFilmsEndpoint.implement { _ =>
    dataRepo.getFilms.catchAll { err =>
      ZIO.fail(UnexpectedError(err.getMessage))
    }
  }.sandbox

  private val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", SWHttpServer.openAPI)

  private val handlers = Chunk(getPersonHandler, getPeopleHandler, getFilmHandler)

  private val routes =
    (Routes(handlers) ++ swaggerRoutes) @@ Middleware.debug

  override def start: URIO[Server, Nothing] = Server.serve(routes)
