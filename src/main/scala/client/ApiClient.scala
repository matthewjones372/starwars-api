package com.jones
package client

import client.ClientError.*
import client.SWAPIClientService.SWAPIEnv
import domain.*

import zio.*
import zio.cache.*
import zio.http.*
import zio.json.JsonCodec

trait ApiClient:
  def getPersonFrom(id: Int): IO[ClientError, People]

  def getFilmFrom(id: Int): IO[ClientError, Film]

  def getFilmFrom(url: URL): IO[ClientError, Film]

  def getPeople: IO[ClientError, Set[People]]

  def getFilms: IO[ClientError, Set[Film]]

object ApiClient:
  def getPersonFrom(id: Int)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getPersonFrom(id))

  def getFilmFrom(id: Int)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFrom(id))

  def getFilmFrom(url: URL)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFrom(url))

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
    override def getFilmFrom(url: URL): IO[ClientError, Film] =
      cache.get(CacheKey.FilmUrl(url)).map {
        case film: Film => film
        case _          => throw UnreachableError
      }

    override def getFilmFrom(id: Int): IO[ClientError, Film] =
      cache.get(CacheKey.FilmId(id)).map {
        case film: Film => film
        case _          => throw UnreachableError
      }

    override def getPersonFrom(id: Int): IO[ClientError, People] =
      cache.get(CacheKey.PersonId(id)).map {
        case people: People => people
        case _              => throw UnreachableError
      }

    override def getPeople: IO[ClientError, Set[People]] =
      cache.get(CacheKey.People).map {
        case PeopleSet(people) => people
        case _                 => throw UnreachableError
      }

    override def getFilms: IO[ClientError, Set[Film]] =
      cache.get(CacheKey.Films).map {
        case FilmSet(films) => films
        case _              => throw UnreachableError
      }

  def live: RLayer[SWAPIEnv, ApiClient] =
    ZLayer.fromZIO {
      def lookup(client: ApiClient): Lookup[Any, Any, ClientError, CacheEntities] = Lookup {
        case CacheKey.FilmId(id) =>
          client.getFilmFrom(id)
        case CacheKey.PersonId(id) =>
          client.getPersonFrom(id)
        case CacheKey.FilmUrl(url) =>
          client.getFilmFrom(url)
        case CacheKey.People =>
          client.getPeople.map(PeopleSet.apply)
        case CacheKey.Films =>
          client.getFilms.map(FilmSet.apply)
      }
      for
        client     <- ZIO.service[Client]
        httpConfig <- ZIO.service[HttpClientConfig]
        scope      <- ZIO.service[Scope]
        apiClient   = ApiLiveClient(client, httpConfig, scope)

        client <-
          for
            cache <-
              Cache.makeWith(
                httpConfig.cacheSize,
                lookup(apiClient)
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
      get[People](httpConfig.baseUrl / "people" / id.toString / "?format=json")
        .provideEnvironment(env)

    override def getPeople: IO[ClientError, Set[People]] = {
      val firstPage = get[Peoples](httpConfig.baseUrl / "people" / "?format=json")

      firstPage.flatMap { case Peoples(count, _, _, firstPage) =>
        // API returns a max of 10 results per call
        val pages = (count / 10) + 1
        ZIO
          .foreachPar(2 to pages)(getPeopleFromPage)
          .map(peoples => (peoples.flatten ++ firstPage).toSet)
      }.orElseFail(ClientError.UnreachableError)
    }.provideEnvironment(env)

    override def getFilmFrom(id: Int): IO[ClientError, Film] =
      get[Film](httpConfig.baseUrl / "films" / id.toString / "?format=json")
        .provideEnvironment(env)

    override def getFilmFrom(url: URL): IO[ClientError, Film] =
      get[Film](url).provideEnvironment(env)

    override def getFilms: IO[ClientError, Set[Film]] = {
      val firstPage = get[Films](httpConfig.baseUrl / "films" / "?format=json")

      firstPage.flatMap { case Films(count, _, _, firstPage) =>
        // API returns a max of 10 results per call
        val pages = (count / 10) + 1
        ZIO
          .foreachPar(2 to pages)(getFilmsFromPage)
          .map(films => (films.flatten ++ firstPage).toSet)
      }.orElseFail(ClientError.UnreachableError)
    }.provideEnvironment(env)

    private def getFilmsFromPage(page: Int): IO[ClientError, Set[Film]] =
      get[Films](httpConfig.baseUrl / "films" / s"?format=json&page=$page")
        .map(_.results.toSet)
        .provideEnvironment(env)

    private def getPeopleFromPage(page: Int): IO[ClientError, Set[People]] =
      get[Peoples](httpConfig.baseUrl / "people" / s"?format=json&page=$page")
        .map(_.results.toSet)
        .provideEnvironment(env)

    private def get[A](url: URL)(using codec: JsonCodec[A]) =
      ResiliencyPolicy.run {
        (for
          response <- client.request(Request.get(url))
          body     <- response.bodyOrClientError(url)
          result <-
            ZIO
              .fromEither(
                codec.decodeJson(
                  body //TODO: Fix this string manipulation we shouldn't need this. we want to use zio schema and the .to[People]
                    .stripPrefix("\"")
                    .stripSuffix("\"")
                    .replaceAll("""\\""", "")
                )
              )
              .mapError(err => ClientError.JsonDeserializationError(body, err))
        yield result).catchAll {
          case err: UnexpectedSeverError =>
            ZIO.logError(err.getMessage) *>
              ZIO.fail(err)
          case err: ClientError =>
            ZIO.logWarning(err.getMessage) *>
              ZIO.fail(err)
        }
      }
