package com.matthewjones372.api.client

import zio.*
import zio.http.*
import zio.json.*
import ClientError.*
import com.matthewjones372.domain.*
import zio.cache.*

trait ApiClient:
  def getPersonFrom(id: Int): IO[ClientError, People]

  def getFilmFrom(id: Int): IO[ClientError, Film]

  def getFilmFrom(url: URL): IO[ClientError, Film]

  def getPeople: IO[ClientError, Set[People]]

  def getFilms: IO[ClientError, Set[Film]]

  def getFilmsFromPerson(id: Int): IO[ClientError, Set[Film]]

  def getFilmsFromPeople: IO[ClientError, Map[People, Set[Film]]]

object ApiClient:
  private type APIClientEnv = Client & Scope & HttpClientConfig

  def getPersonFrom(id: Int)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getPersonFrom(id))

  def getFilmsFromPerson(personId: Int)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmsFromPerson(personId))

  def getFilmFrom(id: Int)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFrom(id))

  def getFilmFrom(url: URL)(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilmFrom(url))

  def getPeople(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getPeople)

  def getFilms(using Trace) =
    ZIO.serviceWithZIO[ApiClient](_.getFilms)

  private enum CacheKey:
    case FilmFromId(id: Int)
    case FilmFromUrl(url: URL)
    case Films
    case PersonFromId(id: Int)
    case People
    case FilmsFromPersonId(id: Int)
    case FilmsFromPeople

  private final case class FilmSet(films: Set[Film])                   extends AnyVal
  private final case class FilmsPeopleMap(map: Map[People, Set[Film]]) extends AnyVal
  private final case class PeopleSet(people: Set[People])              extends AnyVal

  private type CacheEntities = Film | People | FilmSet | PeopleSet | FilmsPeopleMap

  private final class CachingApiClient(
    cache: Cache[CacheKey, ClientError, CacheEntities]
  ) extends ApiClient:
    override def getFilmFrom(url: URL): IO[ClientError, Film] =
      cache.get(CacheKey.FilmFromUrl(url)).map {
        case film: Film => film
        case _          => throw UnreachableError
      }

    override def getFilmFrom(id: Int): IO[ClientError, Film] =
      cache.get(CacheKey.FilmFromId(id)).map {
        case film: Film => film
        case _          => throw UnreachableError
      }

    override def getPersonFrom(id: Int): IO[ClientError, People] =
      cache.get(CacheKey.PersonFromId(id)).map {
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

    override def getFilmsFromPerson(id: Int): IO[ClientError, Set[Film]] =
      cache.get(CacheKey.FilmsFromPersonId(id)).map {
        case FilmSet(films) => films
        case _              => throw UnreachableError
      }

    override def getFilmsFromPeople: IO[ClientError, Map[People, Set[Film]]] =
      cache.get(CacheKey.FilmsFromPeople).map {
        case FilmsPeopleMap(filmsPeopleMap) => filmsPeopleMap
        case _                              => throw UnreachableError
      }

  def live: RLayer[APIClientEnv, ApiClient] =
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
                  case CacheKey.FilmFromId(id) =>
                    apiClient.getFilmFrom(id)
                  case CacheKey.PersonFromId(id) =>
                    apiClient.getPersonFrom(id)
                  case CacheKey.FilmFromUrl(url) =>
                    apiClient.getFilmFrom(url)
                  case CacheKey.People =>
                    apiClient.getPeople.map(PeopleSet.apply)
                  case CacheKey.Films =>
                    apiClient.getFilms.map(FilmSet.apply)
                  case CacheKey.FilmsFromPersonId(id) =>
                    apiClient.getFilmsFromPerson(id).map(fs => FilmSet(fs))
                  case CacheKey.FilmsFromPeople =>
                    apiClient.getFilmsFromPeople.map(FilmsPeopleMap.apply)

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

    override def getFilmsFromPerson(id: Int): IO[ClientError, Set[Film]] =
      for
        people <- getPersonFrom(id)
        films  <- ZIO.foreachPar(people.films)(url => decodeUrlString(url).flatMap(getFilmFrom))
      yield films

    override def getFilmsFromPeople: IO[ClientError, Map[People, Set[Film]]] =
      (for
        people <- getPeople
        films <-
          ZIO
            .foreachPar(people) { person =>
              ZIO
                .foreachPar(person.films)(url => decodeUrlString(url).flatMap(getFilmFrom))
                .map(films => (person, films))
            }
      yield films).map(_.toMap)

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
          response <- Client.request(Request.get(url))
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
      }.provideEnvironment(env)
