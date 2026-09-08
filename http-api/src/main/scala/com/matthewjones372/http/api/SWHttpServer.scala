package com.matthewjones372.http.api

import com.matthewjones372.data.{DataRepoError, SWDataRepo}
import com.matthewjones372.domain.*
import com.matthewjones372.http.api.SWAPIServerError.*
import com.matthewjones372.search.{Path, SWGraph}
import com.matthewjones372.sorting.{FieldOrdering, SortBy}
import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

trait SWHttpServer:
  def start: URIO[Server, Nothing]

object SWHttpServer:
  /**
   * Without `Middleware.debug`, which used to be on this route stack.
   *
   * It logs a line per request, so it sat on the path every response takes.
   * Measured, that was three times this API's capacity: the knee moved from
   * between 2,000 and 4,000 requests a second to between 8,000 and 12,000, and
   * at 4,000 the same handler answered in 774us without it against 25,559us
   * with it. See `load-test/FINDINGS.md`.
   *
   * `withRequestLogging(true)` puts it back for a human who is reading the log.
   */
  def default = withRequestLogging(false)

  def withRequestLogging(enabled: Boolean) =
    (for
      dataRepo <- ZIO.service[SWDataRepo]
      graph    <- characterGraph(dataRepo).memoize
    yield SWHttpServerImpl(dataRepo, graph, enabled)).provideSomeLayer(SWDataRepo.layer)

  def layer: ZLayer[SWDataRepo, Nothing, SWHttpServer] = ZLayer.fromZIO {
    for
      dataRepo <- ZIO.service[SWDataRepo]
      graph    <- characterGraph(dataRepo).memoize
    yield SWHttpServerImpl(dataRepo, graph, false)
  }

  // Characters are joined by the films they share, so film urls resolve to titles for the edge labels.
  private[api] def characterGraph(dataRepo: SWDataRepo): IO[DataRepoError, SWGraph[String]] =
    // Suspended so the repo is not touched until a request actually needs the graph.
    ZIO.suspendSucceed {
      for
        people <- dataRepo.getCharacters(None, None, None)
        films  <- dataRepo.getFilms(None, None)
        titles  = films.results.map(film => film.url -> film.title).toMap
      yield SWGraph(people.results.map(person => person.name -> person.films.flatMap(titles.get)).toMap)
    }

  inline private def fieldNames[A <: Product](using A: Mirror.ProductOf[A]): List[String] =
    constValueTuple[A.MirroredElemLabels].toList.asInstanceOf[List[String]]

  inline private def fieldDocString[A <: Product](using Mirror.ProductOf[A]) =
    Doc.p(
      s"Fields: ${fieldNames[A].mkString(",")}"
    )

  private val characterIdPath = PathCodec.int("characterId").transformOrFailLeft(EntityId.from)(identity)
  private val filmIdPath      = PathCodec.int("filmId").transformOrFailLeft(EntityId.from)(identity)
  private val targetIdPath    = PathCodec.int("targetId").transformOrFailLeft(EntityId.from)(identity)

  private val pageQuery =
    QueryCodec.query[Int]("page").transformOrFail(PageNumber.from)(page => Right(page)).optional

  val getCharacterEndpoint =
    Endpoint(Method.GET / "people" / characterIdPath)
      .out[Character]
      .outErrors[SWAPIServerError](
        HttpCodec.error[CharacterNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getCharactersEndpoint =
    (Endpoint(Method.GET / "people") ?? Doc.p("Get a list of  all people response is paged"))
      .query(pageQuery)
      .query(
        QueryCodec
          .query[String]("sortBy")
          .examples(List("example1" -> "name:ASC", "example2" -> "name:ASC,height:DESC"))
          .optional ?? fieldDocString[Character]
      )
      .out[Characters]
      .outErrors[SWAPIServerError](
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getFilmsEndpoint =
    Endpoint(Method.GET / "films")
      .query(pageQuery)
      .out[Films]
      .outErrors[SWAPIServerError](
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getFilmEndpoint =
    Endpoint(Method.GET / "films" / filmIdPath)
      .out[Film]
      .outErrors[SWAPIServerError](
        HttpCodec.error[FilmNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getShortestPathEndpoint =
    (Endpoint(Method.GET / "people" / characterIdPath / "path-to" / targetIdPath)
      ?? Doc.p("The shortest chain of shared films connecting two characters"))
      .out[ShortestPath]
      .outErrors[SWAPIServerError](
        HttpCodec.error[CharacterNotFound](Status.NotFound),
        HttpCodec.error[PathNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  private[api] def toShortestPath(start: String, end: String, path: Path[String]): ShortestPath =
    val steps = path.path
      .getOrElse(Chunk.empty)
      .dropRight(1)
      .map((person, film) => PathStep(person, film))
      .toList
    ShortestPath(start, end, path.length, steps)

  private[api] def parseSortByList(sortByParam: String): List[SortBy] =
    sortByParam.split(",").toList.flatMap(parseSortBy)

  private[api] def parseSortBy(sortByString: String): Option[SortBy] =
    val parts = sortByString.split(":")
    if parts.length == 2 then
      val key         = parts(0).trim
      val orderingStr = parts(1).trim.toUpperCase
      FieldOrdering.values.find(_.toString == orderingStr).map(ordering => SortBy(key, ordering))
    else None

  private val endPoints =
    Chunk(getCharacterEndpoint, getCharactersEndpoint, getFilmsEndpoint, getFilmEndpoint, getShortestPathEndpoint)

  val openAPI =
    OpenAPIGen.fromEndpoints(
      title = "Star Wars API",
      version = "1.0",
      endPoints
    )

private final case class SWHttpServerImpl(
  private val dataRepo: SWDataRepo,
  private val characterGraph: IO[DataRepoError, SWGraph[String]],
  private val requestLogging: Boolean
) extends SWHttpServer:

  private def characterOrError(id: EntityId): IO[SWAPIServerError, Character] =
    dataRepo.getCharacter(id).catchAll {
      case DataRepoError.CharacterNotFound(message, characterId) =>
        ZIO.fail(CharacterNotFound(message, characterId))
      case err =>
        ZIO.fail(UnexpectedError(err.getMessage))
    }

  private val getShortestPathHandler = SWHttpServer.getShortestPathEndpoint.implement { (characterId, targetId) =>
    for
      start  <- characterOrError(characterId)
      target <- characterOrError(targetId)
      graph  <- characterGraph.mapError(err => UnexpectedError(err.getMessage))
      path   <- ZIO
                .fromOption(graph.bfs(start.name, target.name))
                .orElseFail(PathNotFound(s"No path between ${start.name} and ${target.name}"))
    yield SWHttpServer.toShortestPath(start.name, target.name, path)
  }.sandbox

  private val getCharacterHandler = SWHttpServer.getCharacterEndpoint.implement { characterId =>
    dataRepo
      .getCharacter(characterId)
      .catchAll {
        case DataRepoError.CharacterNotFound(message, characterId) =>
          ZIO.fail(CharacterNotFound(message, characterId))
        case err =>
          ZIO.fail(UnexpectedError(err.getMessage))
      }
  }.sandbox

  private val getCharactersHandler = SWHttpServer.getCharactersEndpoint.implement { (page, sortByParams) =>
    dataRepo
      .getCharacters(page, Some(PageSize.default), sortByParams.map(SWHttpServer.parseSortByList))
      .catchAll(err => ZIO.fail(UnexpectedError(err.getMessage)))
  }.sandbox

  private def getFilmHandler = SWHttpServer.getFilmEndpoint.implement { filmId =>
    dataRepo.getFilm(filmId).catchAll {
      case DataRepoError.FilmNotFound(message, _) =>
        ZIO.fail(FilmNotFound(message, filmId))
      case err =>
        ZIO.fail(UnexpectedError(err.getMessage))
    }
  }.sandbox

  private def getFilmsHandler = SWHttpServer.getFilmsEndpoint.implement { page =>
    dataRepo.getFilms(page, Some(PageSize.default)).catchAll { err =>
      ZIO.fail(UnexpectedError(err.getMessage))
    }
  }.sandbox

  private val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", SWHttpServer.openAPI)

  private val handlers =
    Chunk(getCharacterHandler, getCharactersHandler, getFilmsHandler, getFilmHandler, getShortestPathHandler)

  private val routes =
    val served = Routes(handlers) ++ swaggerRoutes
    if requestLogging then served @@ Middleware.debug else served

  override def start: URIO[Server, Nothing] = Server.serve(routes)
