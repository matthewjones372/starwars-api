package com.matthewjones372.api.client

import com.matthewjones372.domain.*
import zio.*
import zio.http.Client

trait SWAPIClientService:
  def getFilmsFromPerson(id: Int): IO[ClientError, Set[String]]
  def getPeople: IO[ClientError, Set[People]]
  def getFilmsFromPeople: IO[ClientError, Map[String, Set[String]]]
  def getFilms: IO[ClientError, Set[Film]]

object SWAPIClientService:
  type SWAPIEnv = Client & Scope & HttpClientConfig

  def getFilmsFromPerson(id: Int)(using Trace): ZIO[SWAPIClientService, ClientError, Set[String]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getFilmsFromPerson(id))

  def getPeople(using Trace): ZIO[SWAPIClientService, ClientError, Set[People]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getPeople)

  def getFilmsFromPeople(using Trace): ZIO[SWAPIClientService, ClientError, Map[String, Set[String]]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getFilmsFromPeople)

  def getFilms(using Trace): ZIO[SWAPIClientService, ClientError, Set[Film]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getFilms)

  private val layer =
    ZLayer.fromFunction(SWAPIServiceLive.apply)

  val default: RLayer[SWAPIEnv, SWAPIClientService] = ApiClient.live >>> SWAPIClientService.layer

final private case class SWAPIServiceLive(apiClient: ApiClient) extends SWAPIClientService:

  override def getFilms: IO[ClientError, Set[Film]] =
    ApiClient.getFilms.provideEnvironment(ZEnvironment(apiClient))

  override def getFilmsFromPerson(id: Int): IO[ClientError, Set[String]] = {
    for
      people <- ApiClient.getPersonFrom(id)
      films  <- ZIO.foreachPar(people.films)(url => decodeUrlString(url).flatMap(ApiClient.getFilmFrom))
    yield films.map(_.title)
  }.provideEnvironment(ZEnvironment(apiClient))

  override def getPeople: IO[ClientError, Set[People]] =
    ApiClient.getPeople.provideEnvironment(ZEnvironment(apiClient))

  override def getFilmsFromPeople: IO[ClientError, Map[String, Set[String]]] =
    (for
      people <- ApiClient.getPeople
      films <-
        ZIO
          .foreachPar(people) { person =>
            ZIO
              .foreachPar(person.films) { url =>
                decodeUrlString(url).flatMap(ApiClient.getFilmFrom).map(_.title)
              }
              .map(films => (person.name, films))
          }
    yield films).map(_.toMap).provideEnvironment(ZEnvironment(apiClient))
