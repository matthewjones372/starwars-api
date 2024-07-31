package com.jones
package client

import com.jones.domain.*
import zio.*
import zio.http.{Client, URL}

trait SWAPIClientService:
  def getFilmsFromPerson(id: Int): IO[ClientError, Set[String]]
  def getPeople: IO[ClientError, Set[People]]
  def getFilmsFromPeople: IO[ClientError, Map[String, Set[String]]]

object SWAPIClientService:
  type SWAPIEnv = Client & Scope & HttpClientConfig

  def getFilmsFromPerson(id: Int)(using Trace): ZIO[SWAPIClientService, ClientError, Set[String]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getFilmsFromPerson(id))

  def getPeople(using Trace): ZIO[SWAPIClientService, ClientError, Set[People]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getPeople)

  def getFilmsFromPeople(using Trace): ZIO[SWAPIClientService, ClientError, Map[String, Set[String]]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getFilmsFromPeople)

  private val layer =
    ZLayer.fromFunction(SWAPIServiceLive.apply)

  val default: RLayer[SWAPIEnv, SWAPIClientService] = ApiClient.live >>> SWAPIClientService.layer

final private case class SWAPIServiceLive(apiClient: ApiClient) extends SWAPIClientService:

  override def getFilmsFromPerson(id: Int): IO[ClientError, Set[String]] = {
    for
      people <- ApiClient.getPersonFrom(id)
      films  <- ZIO.foreachPar(people.films)(url => decodeUrlString(url).flatMap(ApiClient.getFilmFrom))
    yield films.map(_.title)
  }.provideEnvironment(ZEnvironment(apiClient))

  override def getPeople: IO[ClientError, Set[People]] =
    ApiClient.getPeople.provideEnvironment(ZEnvironment(apiClient))

  override def getFilmsFromPeople: IO[ClientError, Map[String, Set[String]]] =
    ((for
      people <- ApiClient.getPeople
      films <-
        ZIO
          .foreachPar(people) { person =>
            ZIO
              .foreachPar(person.films)(url => decodeUrlString(url).flatMap(ApiClient.getFilmFrom).map(_.title))
              .map(films => (person.name, films))
          }
    yield films).map(_.toMap)).provideEnvironment(ZEnvironment(apiClient))
