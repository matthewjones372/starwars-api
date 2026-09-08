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
  def getPersonFrom(id: Int): IO[ClientError, People]

  def getFilmFrom(id: Int): IO[ClientError, Film]

  def getFilmFromUrl(url: URL): IO[ClientError, Film]

  def getPeople: IO[ClientError, Set[People]]

  def getFilms: IO[ClientError, Set[Film]]

object ApiClient:
  def getPersonFrom(id: Int)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getPersonFrom(id))

  def getFilmFrom(id: Int)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFrom(id))

  def getFilmFromUrl(url: URL)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFromUrl(url))

  def getPeople(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getPeople)

  def getFilms(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilms)

  private enum CacheKey:
    case FilmId(id: Int)
    case FilmUrl(url: URL)
    case Films
    case PersonId(id: Int)
    case People

  private final case class FilmSet(films: Set[Film])      extends AnyVal
  private final case class PeopleSet(people: Set[People]) extends AnyVal

  private type CacheEntities = Film | People | FilmSet | PeopleSet

  private final class CachingApiClient(
    cache: Cache[CacheKey, ClientError, CacheEntities]
  ) extends ApiClient:
    private def getAs[A](key: CacheKey)(entity: PartialFunction[CacheEntities, A]): IO[ClientError, A] =
      cache.get(key).flatMap(value => ZIO.fromOption(entity.lift(value)).orElseFail(UnreachableError))

    override def getFilmFromUrl(url: URL): IO[ClientError, Film] =
      getAs(CacheKey.FilmUrl(url)) { case film: Film => film }

    override def getFilmFrom(id: Int): IO[ClientError, Film] =
      getAs(CacheKey.FilmId(id)) { case film: Film => film }

    override def getPersonFrom(id: Int): IO[ClientError, People] =
      getAs(CacheKey.PersonId(id)) { case people: People => people }

    override def getPeople: IO[ClientError, Set[People]] =
      getAs(CacheKey.People) { case PeopleSet(people) => people }

    override def getFilms: IO[ClientError, Set[Film]] =
      getAs(CacheKey.Films) { case FilmSet(films) => films }

  def live: RLayer[SWAPIEnv, ApiClient] =
    ZLayer.fromZIO {
      for
        client     <- ZIO.service[Client]
        httpConfig <- ZIO.config(HttpClientConfig.config)
        scope      <- ZIO.service[Scope]
        apiClient   = ApiLiveClient(client, httpConfig, scope)
        client <-
          for
            cache <-
              Cache.makeWith(
                httpConfig.cacheSize,
                Lookup {
                  case CacheKey.FilmId(id) =>
                    apiClient.getFilmFrom(id)
                  case CacheKey.PersonId(id) =>
                    apiClient.getPersonFrom(id)
                  case CacheKey.FilmUrl(url) =>
                    apiClient.getFilmFromUrl(url)
                  case CacheKey.People =>
                    apiClient.getPeople.map(PeopleSet.apply)
                  case CacheKey.Films =>
                    apiClient.getFilms.map(FilmSet.apply)
                }
              )(exit => if exit.isSuccess then 30.minutes else Duration.Zero)
          yield CachingApiClient(cache)
      yield client
    }

  final private case class ApiLiveClient(
    client: Client,
    httpConfig: HttpClientConfig,
    scope: Scope
  ) extends ApiClient:
    private val env = ZEnvironment(client, scope)

    override def getPersonFrom(id: Int): IO[ClientError, People] =
      get[People]((httpConfig.baseUrl / "people" / id.toString).addQueryParam("format", "json"))
        .provideEnvironment(env)

    override def getPeople: IO[ClientError, Set[People]] =
      getPagedResponse[Peoples, People]("people").provideEnvironment(env)

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
          .map { entity =>
            (entity.flatMap(_.results.toSet) ++ firstPage.results).toSet
          }
      }.orElseFail(ClientError.FailedToGetPagedResponse)
    }.provideEnvironment(env)

    private def get[A: BinaryCodec](url: URL) =
      ResiliencyPolicy.run {
        (for
          response <- client.request(Request.get(url))
          result   <- response.bodyOrClientError(url)
        yield result).catchAll {
          case err: UnexpectedSeverError =>
            ZIO.logError(err.getMessage) *>
              ZIO.fail(err)
          case err: ClientError =>
            ZIO.logWarning(err.getMessage) *>
              ZIO.fail(err)
        }
      }
