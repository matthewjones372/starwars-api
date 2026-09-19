package com.matthewjones372.http.api

import com.matthewjones372.data.{DataRepo, DataRepoError, Universes}
import com.matthewjones372.domain.*
import com.matthewjones372.http.api.ApiServerError.*
import com.matthewjones372.search.{Connectivity, Path, Graph}
import com.matthewjones372.sorting.{FieldOrdering, SortBy}
import nl.vroste.rezilience.Bulkhead
import zio.*
import zio.http.*
import zio.http.Path as HttpPath
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*
import zio.schema.codec.BinaryCodec
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

import java.nio.charset.StandardCharsets

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

trait ApiServer:
  def start: URIO[Server, Nothing]

object ApiServer:
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
    ZIO.serviceWithZIO[Universes](build(_, preEncoded)).provideSomeLayer(Universes.layer)

  /**
   * The server over whatever repo it is handed, which is not pre-encoded.
   *
   * Encoding a response once is sound because the bundled data is read from a
   * resource at startup and never changes. A repo passed in here has made no
   * such promise, so this one asks it per request as it always did.
   */
  def layer: ZLayer[DataRepo, Nothing, ApiServer] = ZLayer.fromZIO {
    ZIO
      .service[DataRepo]
      .flatMap(repo => build(Universes.fromRepos(Map(UniverseId.default -> repo)), preEncoded = false))
  }

  def universes: ZLayer[Universes, Nothing, ApiServer] = ZLayer.fromZIO {
    ZIO.serviceWithZIO[Universes](build(_, preEncoded = false))
  }

  // The graph and the encoded bytes are built per universe and memoized, as the
  // single pair was: nothing touches a repo until a request needs what it holds.
  private def build(universes: Universes, preEncoded: Boolean): UIO[ApiServer] =
    for
      graphs  <- perUniverse(universes)(characterGraph)
      encoded <- perUniverse(universes)(encodedEntities)
    yield ApiServerImpl(universes, graphs, encoded, preEncoded)

  private def perUniverse[A](universes: Universes)(
    of: DataRepo => IO[DataRepoError, A]
  ): UIO[Map[UniverseId, IO[DataRepoError, A]]] =
    ZIO
      .foreach(universes.available)(universe => universes.repo(universe).flatMap(of).memoize.map(universe -> _))
      .map(_.toMap)

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
  private[api] def encodedEntities(dataRepo: DataRepo): IO[DataRepoError, Encoded] =
    ZIO.suspendSucceed {
      for
        people    <- dataRepo.getCharacters(None, None, None)
        films     <- dataRepo.getFilms(None, None)
        byId      <- keyedBytes(people.results)(_.url)(characterCodec.encode)
        filmsById <- keyedBytes(films.results)(_.url)(filmCodec.encode)
        byUrl      = people.results.map(person => person.url -> characterCodec.encode(person)).toMap
      yield Encoded(byId, filmsById, byUrl)
    }

  private[api] final case class Encoded(
    characters: Map[EntityId, Chunk[Byte]],
    films: Map[EntityId, Chunk[Byte]],
    charactersByUrl: Map[String, Chunk[Byte]]
  )

  /**
   * A page as the bytes of the characters on it, joined.
   *
   * The repo still chooses and orders the page, which is a sort and a slice
   * over values already in memory. What this skips is the encoding, which the
   * profile put at three quarters of the on-CPU samples and which this endpoint
   * repeats ten times a request.
   */
  private[api] def charactersPage(count: Int, results: List[Chunk[Byte]]): Chunk[Byte] =
    val comma  = Chunk.fromArray(",".getBytes(StandardCharsets.UTF_8))
    val joined = results.reduceOption((one, next) => one ++ comma ++ next).getOrElse(Chunk.empty)
    Chunk.fromArray(s"""{"count":$count,"results":[""".getBytes(StandardCharsets.UTF_8)) ++
      joined ++
      Chunk.fromArray("]}".getBytes(StandardCharsets.UTF_8))

  private def keyedBytes[A](entities: List[A])(url: A => String)(
    encode: A => Chunk[Byte]
  ): IO[DataRepoError, Map[EntityId, Chunk[Byte]]] =
    ZIO
      .foreach(entities) { entity =>
        ZIO
          .fromEither(DataRepo.parseEntityId(url(entity)))
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
  private val charactersCodec: BinaryCodec[Characters]               = schemaBasedBinaryCodec[Characters]
  private val filmCodec: BinaryCodec[Film]                           = schemaBasedBinaryCodec[Film]
  private val characterNotFoundCodec: BinaryCodec[CharacterNotFound] = schemaBasedBinaryCodec[CharacterNotFound]
  private val filmNotFoundCodec: BinaryCodec[FilmNotFound]           = schemaBasedBinaryCodec[FilmNotFound]
  private val universeNotFoundCodec: BinaryCodec[UniverseNotFound]   = schemaBasedBinaryCodec[UniverseNotFound]

  private[api] def notFoundCharacter(id: EntityId): Response =
    jsonResponse(Status.NotFound, characterNotFoundCodec.encode(CharacterNotFound("Character not found", id)))

  private[api] def notFoundUniverse(slug: String): Response =
    jsonResponse(
      Status.NotFound,
      universeNotFoundCodec.encode(UniverseNotFound(s"No data is served for '$slug'", slug))
    )

  private[api] def notFoundFilm(id: EntityId): Response =
    jsonResponse(Status.NotFound, filmNotFoundCodec.encode(FilmNotFound("Film not found", id)))

  /**
   * The page, from the bytes already in hand where every character on it is
   * there, and from the codec where one is not.
   *
   * A miss cannot happen for the bundled data, and the fallback is here rather
   * than an assertion because a page served differently is worse than a page
   * served slowly.
   */
  private[api] def charactersResponse(encoded: Encoded, characters: Characters): Response =
    val bytes = characters.results.map(person => encoded.charactersByUrl.get(person.url))
    if bytes.forall(_.isDefined) then jsonResponse(Status.Ok, charactersPage(characters.count, bytes.flatten))
    else jsonResponse(Status.Ok, charactersCodec.encode(characters))

  /**
   * How much work the server will have in hand at once, and how much it will
   * hold waiting.
   *
   * These bound memory rather than police callers. Every answer this API gives
   * is already in memory, so a request is quick and a queue this size drains in
   * well under a second; what the pair prevents is an arrival rate the instance
   * cannot keep up with turning into an unbounded backlog of requests it is
   * holding on behalf of clients that have long since gone. On the 512MB
   * instance this is deployed to, that backlog is what kills it.
   */
  private[api] val maxInFlight = 64
  private[api] val maxQueued   = 128

  /**
   * The answer when both of those are full.
   *
   * A refusal that arrives promptly is worth more than an answer that arrives
   * after the caller has given up, and it tells an honest client to come back
   * rather than leaving it to guess.
   */
  private[api] val tooBusy: Response =
    Response(
      status = Status.ServiceUnavailable,
      headers = Headers(Header.ContentType(MediaType.application.json)),
      body = Body.fromString("""{"error":"Server busy, retry shortly"}""")
    ).addHeader(Header.RetryAfter.ByDuration(2.seconds))

  /**
   * Runs each request through the bulkhead, which rejects rather than queues
   * once [maxQueued] are already waiting.
   *
   * rezilience's `RateLimiter` is the other tool to hand and is the wrong one
   * here: it queues what it cannot admit, without bound, so a flood would be
   * absorbed into memory instead of being turned away.
   */
  private[api] def shedding(bulkhead: Bulkhead)(
    handler: Handler[Any, Response, Request, Response]
  ): Handler[Any, Response, Request, Response] =
    Handler.fromFunctionZIO[Request] { request =>
      // Applying a handler asks for a scope, for bodies that are read as they
      // arrive. Every body here is a chunk already in memory, so the scope has
      // nothing left to hold once the response is built and closing it around
      // the call is safe -- the same reasoning, and the same shape, as the
      // pre-encoded characters route below.
      ZIO.scoped(bulkhead(handler(request))).catchAll {
        case Bulkhead.WrappedError(response) => ZIO.fail(response)
        case Bulkhead.BulkheadRejection      => ZIO.succeed(tooBusy)
      }
    }

  /**
   * Lets a cache answer for this API.
   *
   * The data is read from a resource at startup and never changes, so a repeat
   * of a request is a repeat of an answer. Saying so lets the CDN in front of
   * the deployment serve the second caller without waking this process at all,
   * which is the only mitigation available here that acts before the traffic
   * reaches the instance. The window is short enough that a deploy carrying new
   * data is not shadowed for long.
   *
   * Only successful responses: an error is a fact about one request, and a
   * cached 400 would outlive the mistake that caused it.
   */
  private[api] val cacheable =
    HandlerAspect.updateResponse { response =>
      if response.status.isSuccess then
        response.addHeader(
          Header.CacheControl.Multiple(NonEmptyChunk(Header.CacheControl.Public, Header.CacheControl.MaxAge(300)))
        )
      else response
    }

  private[api] def redirecting(segment: String): Handler[Any, Nothing, (HttpPath, Request), Response] =
    handler { (rest: HttpPath, request: Request) =>
      val target = HttpPath.root / UniverseId.default.slug / segment ++ rest
      Response
        .redirect(request.url.path(target), isPermanent = true)
        .addHeader(Header.CacheControl.MaxAge(300))
    }

  private[api] def jsonResponse(status: Status, body: Chunk[Byte]): Response =
    Response(
      status = status,
      headers = Headers(Header.ContentType(MediaType.application.json)),
      body = Body.fromChunk(body)
    )

  // The edges are film urls rather than titles because two films can share a
  // title, and an edge labelled with the title would merge them into one.
  private[api] final case class CharacterGraph(graph: Graph[String], titles: Map[String, String]):
    def titleOf(film: String): String = titles.getOrElse(film, film)

  // Characters are joined by the films they share.
  private[api] def characterGraph(dataRepo: DataRepo): IO[DataRepoError, CharacterGraph] =
    // Suspended so the repo is not touched until a request actually needs the graph.
    ZIO.suspendSucceed {
      for
        people <- dataRepo.getCharacters(None, None, None)
        films  <- dataRepo.getFilms(None, None)
        titles  = films.results.map(film => film.url -> film.title).toMap
      yield CharacterGraph(
        Graph(people.results.map(person => person.name -> person.films.filter(titles.contains)).toMap),
        titles
      )
    }

  inline private def fieldNames[A <: Product](using A: Mirror.ProductOf[A]): List[String] =
    constValueTuple[A.MirroredElemLabels].toList.asInstanceOf[List[String]]

  inline private def fieldDocString[A <: Product](using Mirror.ProductOf[A]) =
    Doc.p(
      s"Fields: ${fieldNames[A].mkString(",")}"
    )

  // Left as a string rather than decoded to a UniverseId here: a path codec that
  // fails has no route to report it on, so an unknown slug would surface as an
  // unhandled error. It is resolved in the handler instead, where it is a 404.
  private val universePath =
    PathCodec.string("universe") ??
      Doc.p(s"The dataset to read. One of ${UniverseId.slugs.mkString(", ")}.")

  private val characterIdPath = PathCodec.int("characterId").transformOrFailLeft(EntityId.from)(identity)
  private val filmIdPath      = PathCodec.int("filmId").transformOrFailLeft(EntityId.from)(identity)
  private val targetIdPath    = PathCodec.int("targetId").transformOrFailLeft(EntityId.from)(identity)

  private val pageQuery =
    QueryCodec.query[Int]("page").transformOrFail(PageNumber.from)(page => Right(page)).optional

  val getUniversesEndpoint =
    (Endpoint(Method.GET / "universes") ?? Doc.p("The datasets this API serves, and the slug each answers on"))
      .out[AvailableUniverses]
      .outErrors[ApiServerError](
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getCharacterEndpoint =
    Endpoint(Method.GET / universePath / "people" / characterIdPath)
      .out[Character]
      .outErrors[ApiServerError](
        HttpCodec.error[CharacterNotFound](Status.NotFound),
        HttpCodec.error[UniverseNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getCharactersEndpoint =
    (Endpoint(Method.GET / universePath / "people") ?? Doc.p("Get a list of  all people response is paged"))
      .query(pageQuery)
      .query(
        QueryCodec
          .query[String]("sortBy")
          .examples(List("example1" -> "name:ASC", "example2" -> "name:ASC,height:DESC"))
          .optional ?? fieldDocString[Character]
      )
      .out[Characters]
      .outErrors[ApiServerError](
        HttpCodec.error[UniverseNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getFilmsEndpoint =
    Endpoint(Method.GET / universePath / "films")
      .query(pageQuery)
      .out[Films]
      .outErrors[ApiServerError](
        HttpCodec.error[UniverseNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getFilmEndpoint =
    Endpoint(Method.GET / universePath / "films" / filmIdPath)
      .out[Film]
      .outErrors[ApiServerError](
        HttpCodec.error[FilmNotFound](Status.NotFound),
        HttpCodec.error[UniverseNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getShortestPathEndpoint =
    (Endpoint(Method.GET / universePath / "people" / characterIdPath / "path-to" / targetIdPath)
      ?? Doc.p("The shortest chains of shared films connecting two characters, and how many there are"))
      .out[ShortestPath]
      .outErrors[ApiServerError](
        HttpCodec.error[CharacterNotFound](Status.NotFound),
        HttpCodec.error[PathNotFound](Status.NotFound),
        HttpCodec.error[UniverseNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  val getGraphInsightsEndpoint =
    (Endpoint(Method.GET / universePath / "graph" / "insights")
      ?? Doc.p("How connected each character is, and what the whole cast looks like as a graph"))
      .out[GraphInsights]
      .outErrors[ApiServerError](
        HttpCodec.error[UniverseNotFound](Status.NotFound),
        HttpCodec.error[UnexpectedError](Status.InternalServerError),
        HttpCodec.error[ServerError](Status.InternalServerError)
      )

  /**
   * How many equally short chains one request carries back.
   *
   * Two characters two hops apart have seventeen of them on average in this
   * data and seventy at the most, which is more than anyone pages through and
   * more than the answer should weigh. How many there really are rides along,
   * so a caller shown ten of twenty-four is told so.
   */
  private[api] val maxChains = 10

  private[api] def stepsOf(graph: CharacterGraph)(path: Path[String]): List[PathStep] =
    path.path.getOrElse(Chunk.empty).dropRight(1).map((person, film) => PathStep(person, graph.titleOf(film))).toList

  private[api] def toShortestPath(
    graph: CharacterGraph,
    start: String,
    end: String,
    paths: List[Path[String]],
    chains: Int
  ): ShortestPath =
    val chosen = paths.headOption
    ShortestPath(
      start = start,
      end = end,
      films = chosen.map(_.length).getOrElse(0),
      steps = chosen.map(stepsOf(graph)).getOrElse(Nil),
      alternatives = paths.drop(1).map(path => Chain(stepsOf(graph)(path))),
      chains = chains
    )

  // Two decimals is the resolution the numbers carry: separations run between
  // one hop and the graph's diameter, and a full double of that is noise.
  private def rounded(value: Double): Double = math.round(value * 100) / 100.0

  private[api] def toGraphInsights(graph: CharacterGraph, connectivity: Connectivity[String]): GraphInsights =
    GraphInsights(
      characters = connectivity.nodes,
      films = connectivity.films,
      pairs = connectivity.pairs,
      density = rounded(connectivity.density),
      averageSeparation = rounded(connectivity.averageSeparation),
      diameter = connectivity.diameter,
      clusters = connectivity.clusters,
      connections = connectivity.connections.map { connection =>
        CharacterConnections(
          name = connection.node,
          films = connection.films,
          coStars = connection.coStars,
          reach = connection.reach,
          averageSeparation = rounded(connection.averageSeparation)
        )
      },
      ensembles = connectivity.ensembles.map(ensemble =>
        FilmEnsemble(graph.titleOf(ensemble.film), ensemble.cast, ensemble.exclusiveCast)
      )
    )

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
    Chunk(
      getUniversesEndpoint,
      getCharacterEndpoint,
      getCharactersEndpoint,
      getFilmsEndpoint,
      getFilmEndpoint,
      getShortestPathEndpoint,
      getGraphInsightsEndpoint
    )

  val openAPI =
    OpenAPIGen.fromEndpoints(
      title = "Film Universe API",
      version = "1.0",
      endPoints
    )

private final case class ApiServerImpl(
  private val universes: Universes,
  private val graphs: Map[UniverseId, IO[DataRepoError, ApiServer.CharacterGraph]],
  private val encodedByUniverse: Map[UniverseId, IO[DataRepoError, ApiServer.Encoded]],
  private val preEncoded: Boolean
) extends ApiServer:

  // A slug this API does not know and one it knows but ships no data for are the
  // same answer: the dataset is not here. Neither is a bad request -- the caller
  // named a resource, and it does not exist.
  private def missing(slug: String) = UniverseNotFound(s"No data is served for '$slug'", slug)

  private def universeOf(slug: String): IO[ApiServerError, UniverseId] =
    ZIO.fromEither(UniverseId.from(slug)).orElseFail(missing(slug))

  private def repoOf(slug: String): IO[ApiServerError, DataRepo] =
    universeOf(slug).flatMap(universe => universes.repo(universe).orElseFail(missing(slug)))

  private def held[A](from: Map[UniverseId, IO[DataRepoError, A]], slug: String): IO[ApiServerError, A] =
    universeOf(slug).flatMap { universe =>
      ZIO
        .fromOption(from.get(universe))
        .orElseFail(missing(slug))
        .flatMap(_.mapError(err => UnexpectedError(err.getMessage)))
    }

  private def graphOf(slug: String) = held(graphs, slug)

  private def encodedOf(slug: String) = held(encodedByUniverse, slug)

  private def errorResponse(error: ApiServerError): Response = error match
    case UniverseNotFound(_, slug) => ApiServer.notFoundUniverse(slug)
    case other                     => Response.internalServerError(other.getMessage)

  private def characterOrError(universe: String, id: EntityId): IO[ApiServerError, Character] =
    repoOf(universe).flatMap(_.getCharacter(id)).catchAll {
      case DataRepoError.CharacterNotFound(message, characterId) =>
        ZIO.fail(CharacterNotFound(message, characterId))
      case err: ApiServerError => ZIO.fail(err)
      case err                 => ZIO.fail(UnexpectedError(err.getMessage))
    }

  private val getShortestPathHandler = ApiServer.getShortestPathEndpoint.implement { (universe, characterId, targetId) =>
    for
      start  <- characterOrError(universe, characterId)
      target <- characterOrError(universe, targetId)
      graph  <- graphOf(universe)
      paths  <- ZIO
                 .succeed(graph.graph.shortestPaths(start.name, target.name, ApiServer.maxChains))
                 .filterOrFail(_.nonEmpty)(PathNotFound(s"No path between ${start.name} and ${target.name}"))
      chains = graph.graph.countShortestPaths(start.name, target.name)
    yield ApiServer.toShortestPath(graph, start.name, target.name, paths, chains)
  }.sandbox

  private val getGraphInsightsHandler = ApiServer.getGraphInsightsEndpoint.implement { (universe: String) =>
    graphOf(universe).map(graph => ApiServer.toGraphInsights(graph, graph.graph.connectivity))
  }.sandbox

  private val getUniversesHandler = ApiServer.getUniversesEndpoint.implement { (_: Unit) =>
    val offered = universes.available.map(universe => UniverseSummary(universe.slug, universe.label))
    ZIO.succeed(AvailableUniverses(offered.size, offered))
  }.sandbox

  private val getCharacterHandler = ApiServer.getCharacterEndpoint.implement { (universe, characterId) =>
    characterOrError(universe, characterId)
  }.sandbox

  private val getCharactersHandler = ApiServer.getCharactersEndpoint.implement { (universe, page, sortByParams) =>
    repoOf(universe)
      .flatMap(_.getCharacters(page, Some(PageSize.default), sortByParams.map(ApiServer.parseSortByList)))
      .catchAll {
        case err: ApiServerError => ZIO.fail(err)
        case err                 => ZIO.fail(UnexpectedError(err.getMessage))
      }
  }.sandbox

  private def getFilmHandler = ApiServer.getFilmEndpoint.implement { (universe, filmId) =>
    repoOf(universe).flatMap(_.getFilm(filmId)).catchAll {
      case DataRepoError.FilmNotFound(message, _) =>
        ZIO.fail(FilmNotFound(message, filmId))
      case err: ApiServerError => ZIO.fail(err)
      case err                 => ZIO.fail(UnexpectedError(err.getMessage))
    }
  }.sandbox

  private def getFilmsHandler = ApiServer.getFilmsEndpoint.implement { (universe, page) =>
    repoOf(universe).flatMap(_.getFilms(page, Some(PageSize.default))).catchAll {
      case err: ApiServerError => ZIO.fail(err)
      case err                 => ZIO.fail(UnexpectedError(err.getMessage))
    }
  }.sandbox

  private val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", ApiServer.openAPI)

  /**
   * The browser UI, which is a single page read from the classpath.
   *
   * Serving it from the API rather than from somewhere else is what keeps the
   * two on one origin: the page calls `/people` and `/films` as relative paths,
   * so there is no cross-origin rule to relax and no second thing to deploy or
   * to keep in step with this one.
   */
  /**
   * The page must be revalidated rather than cached on the reader's word alone.
   *
   * Resources inside the staged jar carry the zip epoch as their last-modified
   * date, and a response with a validator but no `Cache-Control` is left to the
   * browser's heuristic: roughly a tenth of the age of that date, which for a
   * timestamp in 2010 is over a year. The page would then survive every deploy
   * behind it, so a reader who had opened it once would keep the version they
   * first saw. `no-cache` still allows the 304 the etag would have earned; what
   * it forbids is serving it without asking.
   */
  private val uiRoutes =
    Routes(
      Method.GET / Root -> Handler
        .fromResource("web/index.html")
        .map(_.addHeader(Header.CacheControl.NoCache))
        .sandbox
    )

  // The bytes for one entity, served from the map, or the same 404 the endpoint
  // would have produced. A miss here is a miss in the repo the map was built
  // from, so there is one answer rather than a fallback that could differ.
  private val preEncodedCharacterRoute =
    ApiServer.getCharacterEndpoint.route -> handler { (universe: String, characterId: EntityId, _: Request) =>
      encodedOf(universe)
        .map(_.characters.get(characterId))
        .foldZIO(
          error => ZIO.succeed(errorResponse(error)),
          {
            case Some(bytes) => ZIO.succeed(ApiServer.jsonResponse(Status.Ok, bytes))
            case None        => ZIO.succeed(ApiServer.notFoundCharacter(characterId))
          }
        )
    }

  private val preEncodedFilmRoute =
    ApiServer.getFilmEndpoint.route -> handler { (universe: String, filmId: EntityId, _: Request) =>
      encodedOf(universe)
        .map(_.films.get(filmId))
        .foldZIO(
          error => ZIO.succeed(errorResponse(error)),
          {
            case Some(bytes) => ZIO.succeed(ApiServer.jsonResponse(Status.Ok, bytes))
            case None        => ZIO.succeed(ApiServer.notFoundFilm(filmId))
          }
        )
    }

  // Only the query shapes the endpoint accepts without complaint. Anything else
  // goes to the endpoint itself, which owns the 400 it produces for `page=0` and
  // for a page that is not a number; replicating either here would be two
  // spellings of one error, free to drift apart.
  private def cleanPage(request: Request): Option[Option[PageNumber]] =
    request.url.queryParams.getAll("page") match
      case Chunk()     => Some(None)
      case Chunk(only) => only.toIntOption.flatMap(PageNumber.from(_).toOption).map(Some(_))
      case _           => None

  private val preEncodedCharactersRoute =
    ApiServer.getCharactersEndpoint.route -> handler { (universe: String, request: Request) =>
      cleanPage(request) match
        case None       => ZIO.scoped(getCharactersHandler.toHandler.apply(request))
        case Some(page) =>
          val sortBy = request.url.queryParams.queryParam("sortBy").map(ApiServer.parseSortByList)
          encodedOf(universe)
            .zip(
              repoOf(universe)
                .flatMap(
                  _.getCharacters(page, Some(PageSize.default), sortBy)
                    .mapError(err => UnexpectedError(err.getMessage))
                )
            )
            .fold(
              error => errorResponse(error),
              (bytes, characters) => ApiServer.charactersResponse(bytes, characters)
            )
    }

  private val handlers =
    if preEncoded then
      Chunk(
        getUniversesHandler,
        preEncodedCharacterRoute,
        preEncodedCharactersRoute,
        getFilmsHandler,
        preEncodedFilmRoute,
        getShortestPathHandler,
        getGraphInsightsHandler
      )
    else
      Chunk(
        getUniversesHandler,
        getCharacterHandler,
        getCharactersHandler,
        getFilmsHandler,
        getFilmHandler,
        getShortestPathHandler,
        getGraphInsightsHandler
      )

  /**
   * Where the api answered before it carried a universe.
   *
   * A redirect rather than a second set of routes: an entity's `url` can name
   * only one of the two, so the unprefixed one has to point at the prefixed one
   * rather than stand beside it as an equal.
   */
  private val legacyRoutes =
    Routes(
      Method.GET / "people" / PathCodec.trailing -> ApiServer.redirecting("people"),
      Method.GET / "films" / PathCodec.trailing  -> ApiServer.redirecting("films"),
      Method.GET / "graph" / PathCodec.trailing  -> ApiServer.redirecting("graph")
    )

  // The API is cacheable and the page is not: the page is how a reader picks up
  // a new deploy, so it revalidates while the data it fetches need not.
  private val routes =
    (Routes(handlers) @@ ApiServer.cacheable) ++ legacyRoutes ++ swaggerRoutes ++ uiRoutes

  override def start: URIO[Server, Nothing] =
    ZIO.scoped {
      for
        bulkhead <- Bulkhead.make(ApiServer.maxInFlight, ApiServer.maxQueued)
        served   <- Server.serve(routes.transform(ApiServer.shedding(bulkhead)))
      yield served
    }
