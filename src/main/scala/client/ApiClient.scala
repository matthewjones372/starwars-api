package com.jones
package client

import client.ClientError.{UnexpectedClientError, UnexpectedSeverError}
import model.*

import zio.*
import zio.http.*
import zio.json.*
import client.SWAPIService.Env

import zio.cache.*

trait ApiClient:
  def getPersonFrom(id: Int): IO[ClientError, People]

  def getFilmFrom(id: Int): IO[ClientError, Film]

  def getFilmFrom(url: URL): IO[ClientError, Film]

object ApiClient:
  def getPersonFrom(id: Int)(implicit trace: Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getPersonFrom(id))

  def getFilmFrom(id: Int)(implicit trace: Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFrom(id))

  def getFilmFrom(url: URL)(implicit trace: Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFrom(url))

  private enum CacheKey:
    case FilmIdKey(id: Int)
    case PersonIdKey(id: Int)
    case FilmCacheUrlKey(url: URL)

  private final class CachingApiClient(
    cache: Cache[CacheKey, ClientError, Film | People]
  ) extends ApiClient:
    override def getFilmFrom(url: URL): IO[ClientError, Film] =
      cache.get(CacheKey.FilmCacheUrlKey(url)).map {
        case film: Film => film
        case _          => throw UnexpectedClientError("Film not found")
      }

    override def getPersonFrom(id: Int): IO[ClientError, People] =
      cache.get(CacheKey.PersonIdKey(id)).map {
        case people: People => people
        case _              => throw UnexpectedClientError("Person not found")
      }

    override def getFilmFrom(id: Int): IO[ClientError, Film] =
      cache.get(CacheKey.FilmIdKey(id)).map {
        case film: Film => film
        case _          => throw UnexpectedClientError("Film not found")
      }

  def live: RLayer[Env, ApiClient] =
    ZLayer.fromZIO {
      for
        client     <- ZIO.service[Client]
        httpConfig <- ZIO.service[HttpClientConfig]
        scope      <- ZIO.service[Scope]
        apiClient   = ApiLiveClient(client, httpConfig, scope)
        client <-
          for
            filmCache <-
              Cache.makeWith(
                1000,
                Lookup {
                  case CacheKey.FilmIdKey(id) =>
                    apiClient.getFilmFrom(id)
                  case CacheKey.PersonIdKey(id) =>
                    apiClient.getPersonFrom(id)
                  case CacheKey.FilmCacheUrlKey(url) =>
                    apiClient.getFilmFrom(url)
                }
              )(exit => if exit.isSuccess then 30.minutes else Duration.Zero)
          yield CachingApiClient(filmCache)
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

    override def getFilmFrom(id: Int): IO[ClientError, Film] =
      get[Film]((httpConfig.baseUrl / "films" / id.toString).addQueryParam("format", "json"))
        .provideEnvironment(env)

    override def getFilmFrom(url: URL): IO[ClientError, Film] =
      get[Film](url).provideEnvironment(env)

    private def get[A: JsonDecoder](url: URL) =
      ResiliencyPolicy.run {
        (for
          response <- client.request(Request.get(url))
          body     <- response.bodyOrClientError(url)
          result <- ZIO.fromEither {
                      body //TODO: Fix this string manipulation you shouldn't need this. Raise an issue with zio-http
                        .stripPrefix("\"")
                        .stripSuffix("\"")
                        .replaceAll("""\\""", "")
                        .fromJson[A]
                        .left
                        .map(err => ClientError.JsonDeserializationError(err))
                    }
        yield result).catchAll {
          case err: UnexpectedSeverError =>
            ZIO.logError(err.getMessage) *>
              ZIO.fail(err)
          case err: ClientError =>
            ZIO.logWarning(err.getMessage) *>
              ZIO.fail(err)
        }
      }
