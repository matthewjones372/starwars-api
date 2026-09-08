package com.matthewjones372.api.client

import com.matthewjones372.api.client.ClientError.*
import com.matthewjones372.api.client.SWAPIClientService.SWAPIEnv
import com.matthewjones372.api.client.config.HttpClientConfig
import com.matthewjones372.domain.*
import zio.*
import zio.cache.*
import zio.http.*
import zio.schema.codec.BinaryCodec
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

trait ApiClient:
  def getCharacterFrom(id: Int): IO[ClientError, Character]

  def getFilmFrom(id: Int): IO[ClientError, Film]

  def getFilmFromUrl(url: URL): IO[ClientError, Film]

  def getCharacters: IO[ClientError, Set[Character]]

  def getFilms: IO[ClientError, Set[Film]]

object ApiClient:
  def getCharacterFrom(id: Int)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getCharacterFrom(id))

  def getFilmFrom(id: Int)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFrom(id))

  def getFilmFromUrl(url: URL)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFromUrl(url))

  def getCharacters(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getCharacters)

  def getFilms(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilms)

  // A film is reachable by id or by url, and both name the same entry.
  private enum FilmKey:
    case Id(id: Int)
    case Url(url: URL)

  // A cache holds a single value type, so one cache over every key would have to
  // widen its values and narrow them again on the way out. One cache per type
  // keeps the lookup and its result in the same types the callers use.
  private final class CachingApiClient(
    films: Cache[FilmKey, ClientError, Film],
    characters: Cache[Int, ClientError, Character],
    allFilms: Cache[Unit, ClientError, Set[Film]],
    allCharacters: Cache[Unit, ClientError, Set[Character]]
  ) extends ApiClient:
    override def getFilmFromUrl(url: URL): IO[ClientError, Film] = films.get(FilmKey.Url(url))

    override def getFilmFrom(id: Int): IO[ClientError, Film] = films.get(FilmKey.Id(id))

    override def getCharacterFrom(id: Int): IO[ClientError, Character] = characters.get(id)

    override def getCharacters: IO[ClientError, Set[Character]] = allCharacters.get(())

    override def getFilms: IO[ClientError, Set[Film]] = allFilms.get(())

  private def cacheOf[K, V](capacity: Int)(lookup: K => IO[ClientError, V]) =
    Cache.makeWith(capacity, Lookup(lookup))(exit => if exit.isSuccess then 30.minutes else Duration.Zero)

  def live: RLayer[SWAPIEnv, ApiClient] =
    ZLayer.fromZIO {
      for
        client     <- ZIO.service[Client]
        httpConfig <- ZIO.config(HttpClientConfig.config)
        scope      <- ZIO.service[Scope]
        apiClient   = ApiLiveClient(client, httpConfig, scope)
        client     <-
          for
            films <- cacheOf[FilmKey, Film](httpConfig.cacheSize) {
                       case FilmKey.Id(id)   => apiClient.getFilmFrom(id)
                       case FilmKey.Url(url) => apiClient.getFilmFromUrl(url)
                     }
            characters <- cacheOf[Int, Character](httpConfig.cacheSize)(apiClient.getCharacterFrom)
            // Whole collection lookups have a single key, so they need a single entry.
            allFilms      <- cacheOf[Unit, Set[Film]](1)(_ => apiClient.getFilms)
            allCharacters <- cacheOf[Unit, Set[Character]](1)(_ => apiClient.getCharacters)
          yield CachingApiClient(films, characters, allFilms, allCharacters)
      yield client
    }

  final private case class ApiLiveClient(
    client: Client,
    httpConfig: HttpClientConfig,
    scope: Scope
  ) extends ApiClient:
    private val env = ZEnvironment(client, scope)

    override def getCharacterFrom(id: Int): IO[ClientError, Character] =
      get[Character]((httpConfig.baseUrl / "people" / id.toString).addQueryParam("format", "json"))
        .provideEnvironment(env)

    override def getCharacters: IO[ClientError, Set[Character]] =
      getPagedResponse[Characters, Character]("people").provideEnvironment(env)

    override def getFilmFrom(id: Int): IO[ClientError, Film] =
      get[Film]((httpConfig.baseUrl / "films" / id.toString).addQueryParam("format", "json"))
        .provideEnvironment(env)

    override def getFilmFromUrl(url: URL): IO[ClientError, Film] =
      get[Film](url).provideEnvironment(env)

    override def getFilms: IO[ClientError, Set[Film]] =
      getPagedResponse[Films, Film]("films").provideEnvironment(env)

    private def getPagedResponse[A <: Paged[B]: BinaryCodec, B](entity: String) = {
      get[A]((httpConfig.baseUrl / entity).addQueryParam("format", "json")).flatMap { firstPage =>
        ZIO
          .foreachPar(2 to firstPage.pageCount)(page =>
            get[A](
              (httpConfig.baseUrl / entity)
                .addQueryParam("format", "json")
                .addQueryParam("page", page.toString)
            )
          )
          .withParallelism(httpConfig.maxConcurrency)
          .map { entity =>
            (entity.flatMap(_.results.toSet) ++ firstPage.results).toSet
          }
      }.orElseFail(ClientError.FailedToGetPagedResponse)
    }.provideEnvironment(env)

    private def get[A: BinaryCodec](url: URL) =
      ResiliencyPolicy.run {
        (for
          response <- client.batched(Request.get(url))
          result   <- response.bodyOrClientError(url)
        yield result).catchAll {
          case err: UnexpectedSeverError =>
            ZIO.logError(err.getMessage) *>
              ZIO.fail(err)
          case err: ClientError =>
            ZIO.logWarning(err.getMessage) *>
              ZIO.fail(err)
          case err =>
            ZIO.logError(s"Unexpected error requesting $url: ${err.getMessage}") *>
              ZIO.fail(ClientError.UnexpectedClientError(err.getMessage))
        }
      }
