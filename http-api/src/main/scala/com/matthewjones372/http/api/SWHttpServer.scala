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
import zio.schema.codec.BinaryCodec
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

trait SWHttpServer:
  def start: URIO[Server, Nothing]

object SWHttpServer:
  def default = measuring(preEncoded = true)

  /**
   * The server over the bundled data, which is read from a resource at startup
   * and never changes: that is what makes encoding each response once sound.
   *
   * [preEncoded] is a seam for the load test, so it can measure the same code
   * with the encoding and without. Not part of the API: what ships is
   * [default], and nothing outside this build can ask for the slow path.
   */
  private[matthewjones372] def measuring(preEncoded: Boolean) =
    (for
      dataRepo <- ZIO.service[SWDataRepo]
      graph    <- characterGraph(dataRepo).memoize
      encoded  <- encodedEntities(dataRepo).memoize
    yield SWHttpServerImpl(dataRepo, graph, encoded, preEncoded)).provideSomeLayer(SWDataRepo.layer)

  /**
   * The server over whatever repo it is handed, which is not pre-encoded.
   *
   * Encoding a response once is sound because the bundled data is read from a
   * resource at startup and never changes. A repo passed in here has made no
   * such promise, so this one asks it per request as it always did.
   */
  def layer: ZLayer[SWDataRepo, Nothing, SWHttpServer] = ZLayer.fromZIO {
    for
      dataRepo <- ZIO.service[SWDataRepo]
      graph    <- characterGraph(dataRepo).memoize
      encoded  <- encodedEntities(dataRepo).memoize
    yield SWHttpServerImpl(dataRepo, graph, encoded, preEncoded = false)
  }

  /**
   * Every entity turned into response bytes once.
   *
   * A JFR profile of this API at 6,000 requests a second put UTF-8 encoding and
   * zio-schema's case-class encoder at roughly three quarters of the on-CPU
   * samples, and no line of this repository in the profile at all. The data is
   * read from a resource at startup and never changes, so the encoding is work
   * this API does once and then repeats on every request.
   *
   * Suspended and memoized, as [characterGraph] is: nothing here touches the
   * repo until a request needs it, which keeps a stubbed repo in a test doing
   * what the test said and not what a constructor asked for.
   */
  private[api] def encodedEntities(dataRepo: SWDataRepo): IO[DataRepoError, Encoded] =
    ZIO.suspendSucceed {
      for
        people    <- dataRepo.getCharacters(None, None, None)
        films     <- dataRepo.getFilms(None, None)
        byId      <- keyedBytes(people.results)(_.url)(characterCodec.encode)
        filmsById <- keyedBytes(films.results)(_.url)(filmCodec.encode)
      yield Encoded(byId, filmsById)
    }

  private[api] final case class Encoded(characters: Map[EntityId, Chunk[Byte]], films: Map[EntityId, Chunk[Byte]])

  private def keyedBytes[A](entities: List[A])(url: A => String)(
    encode: A => Chunk[Byte]
  ): IO[DataRepoError, Map[EntityId, Chunk[Byte]]] =
    ZIO
      .foreach(entities) { entity =>
        ZIO
          .fromEither(SWDataRepo.parseEntityId(url(entity)))
          .mapBoth(
            message => DataRepoError.UnexpectedError(message, new IllegalArgumentException(message)),
            id => id -> encode(entity)
          )
      }
      .map(_.toMap)

  // The same codec the endpoint's own output goes through, so the bytes served
  // from the map are the bytes the codec would have produced. `PreEncodedSpec`
  // asserts that against a server with the knob off rather than trusting it.
  private val characterCodec: BinaryCodec[Character]                 = schemaBasedBinaryCodec[Character]
  private val filmCodec: BinaryCodec[Film]                           = schemaBasedBinaryCodec[Film]
  private val characterNotFoundCodec: BinaryCodec[CharacterNotFound] = schemaBasedBinaryCodec[CharacterNotFound]
  private val filmNotFoundCodec: BinaryCodec[FilmNotFound]           = schemaBasedBinaryCodec[FilmNotFound]

  private[api] def notFoundCharacter(id: EntityId): Response =
    jsonResponse(Status.NotFound, characterNotFoundCodec.encode(CharacterNotFound("Character not found", id)))

  private[api] def notFoundFilm(id: EntityId): Response =
    jsonResponse(Status.NotFound, filmNotFoundCodec.encode(FilmNotFound("Film not found", id)))

  private[api] def jsonResponse(status: Status, body: Chunk[Byte]): Response =
    Response(
      status = status,
      headers = Headers(Header.ContentType(MediaType.application.json)),
      body = Body.fromChunk(body)
    )

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
  private val encoded: IO[DataRepoError, SWHttpServer.Encoded],
  private val preEncoded: Boolean
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

  // The bytes for one entity, served from the map, or the same 404 the endpoint
  // would have produced. A miss here is a miss in the repo the map was built
  // from, so there is one answer rather than a fallback that could differ.
  private val preEncodedCharacterRoute =
    SWHttpServer.getCharacterEndpoint.route -> handler { (characterId: EntityId, _: Request) =>
      encoded
        .map(_.characters.get(characterId))
        .foldZIO(
          error => ZIO.succeed(Response.internalServerError(error.getMessage)),
          {
            case Some(bytes) => ZIO.succeed(SWHttpServer.jsonResponse(Status.Ok, bytes))
            case None        => ZIO.succeed(SWHttpServer.notFoundCharacter(characterId))
          }
        )
    }

  private val preEncodedFilmRoute =
    SWHttpServer.getFilmEndpoint.route -> handler { (filmId: EntityId, _: Request) =>
      encoded
        .map(_.films.get(filmId))
        .foldZIO(
          error => ZIO.succeed(Response.internalServerError(error.getMessage)),
          {
            case Some(bytes) => ZIO.succeed(SWHttpServer.jsonResponse(Status.Ok, bytes))
            case None        => ZIO.succeed(SWHttpServer.notFoundFilm(filmId))
          }
        )
    }

  private val handlers =
    if preEncoded then
      Chunk(
        preEncodedCharacterRoute,
        getCharactersHandler,
        getFilmsHandler,
        preEncodedFilmRoute,
        getShortestPathHandler
      )
    else Chunk(getCharacterHandler, getCharactersHandler, getFilmsHandler, getFilmHandler, getShortestPathHandler)

  private val routes = Routes(handlers) ++ swaggerRoutes

  override def start: URIO[Server, Nothing] = Server.serve(routes)
