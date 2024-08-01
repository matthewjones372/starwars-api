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
                Lookup {
                  case CacheKey.FilmId(id) =>
                    apiClient.getFilmFrom(id)
                  case CacheKey.PersonId(id) =>
                    apiClient.getPersonFrom(id)
                  case CacheKey.FilmUrl(url) =>
                    apiClient.getFilmFrom(url)
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

    override def getFilmFrom(url: URL): IO[ClientError, Film] =
      get[Film](url).provideEnvironment(env)

    override def getFilms: IO[ClientError, Set[Film]] =
      getPagedResponse[Films, Film]("films").provideEnvironment(env)

    private def getPagedResponse[A <: Paged[B]: JsonCodec, B](entity: String) = {
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
                    .replaceAll("""\\(?![nr])""", "")
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
