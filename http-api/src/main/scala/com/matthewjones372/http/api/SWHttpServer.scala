package com.matthewjones372.http.api

import SWAPIServerError.*
import com.matthewjones372.data.{DataRepoError, SWDataRepo}
import com.matthewjones372.domain.*
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
    Endpoint(Method.GET / "people" / PathCodec.int("personId"))
      .out[People]
      .outErrors[SWAPIServerError](
        HttpCodec.error[PersonNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getPeopleEndpoint =
    Endpoint(Method.GET / "people")
      .query(QueryCodec.queryInt("page").optional)
      .out[Peoples]
      .outErrors[SWAPIServerError](
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getFilmsEndpoint =
    Endpoint(Method.GET / "films")
      .query(QueryCodec.queryInt("page").optional)
      .out[Films]
      .outErrors[SWAPIServerError](
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getFilmEndpoint =
    Endpoint(Method.GET / "films" / PathCodec.int("filmId"))
      .out[Film]
      .outErrors[SWAPIServerError](
        HttpCodec.error[FilmNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  private val endPoints =
    Chunk(getPersonEndpoint, getPeopleEndpoint, getFilmsEndpoint, getFilmEndpoint)

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

  private val getPeopleHandler = SWHttpServer.getPeopleEndpoint.implement { page =>
    dataRepo.getPeople(page, Some(10)).catchAll { err =>
      ZIO.fail(UnexpectedError(err.getMessage))
    }
  }.sandbox

  private def getFilmHandler = SWHttpServer.getFilmEndpoint.implement { filmId =>
    dataRepo.getFilm(filmId).catchAll {
      case DataRepoError.FilmNotFound(message, _) =>
        ZIO.fail(FilmNotFound(message, filmId))
      case err =>
        ZIO.fail(UnexpectedError(err.getMessage))
    }

  }

  private def getFilmsHandler = SWHttpServer.getFilmsEndpoint.implement { page =>
    dataRepo.getFilms(page, Some(10)).catchAll { err =>
      ZIO.fail(UnexpectedError(err.getMessage))
    }
  }.sandbox

  private val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", SWHttpServer.openAPI)

  private val handlers = Chunk(getPersonHandler, getPeopleHandler, getFilmsHandler, getFilmHandler)

  private val routes =
    (Routes(handlers) ++ swaggerRoutes) @@ Middleware.debug

  override def start: URIO[Server, Nothing] = Server.serve(routes)
