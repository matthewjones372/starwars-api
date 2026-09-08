package com.matthewjones372.api.client

import com.matthewjones372.api.client.config.HttpClientConfig
import com.matthewjones372.domain.*
import zio.*
import zio.http.*

trait SWAPIClientService:
  def getFilmsFromCharacter(id: Int): IO[ClientError, Set[String]]
  def getCharacters: IO[ClientError, Set[Character]]
  def getFilmsFromCharacters: IO[ClientError, Map[String, Set[String]]]
  def getFilms: IO[ClientError, Set[Film]]

object SWAPIClientService:
  type SWAPIEnv = Client & Scope

  def getFilmsFromCharacter(id: Int)(using Trace): ZIO[SWAPIClientService, ClientError, Set[String]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getFilmsFromCharacter(id))

  def getCharacters(using Trace): ZIO[SWAPIClientService, ClientError, Set[Character]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getCharacters)

  def getFilmsFromCharacters(using Trace): ZIO[SWAPIClientService, ClientError, Map[String, Set[String]]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getFilmsFromCharacters)

  def getFilms(using Trace): ZIO[SWAPIClientService, ClientError, Set[Film]] =
    ZIO.serviceWithZIO[SWAPIClientService](_.getFilms)

  private val layer: RLayer[ApiClient, SWAPIClientService] =
    ZLayer.fromZIO {
      for
        apiClient  <- ZIO.service[ApiClient]
        httpConfig <- ZIO.config(HttpClientConfig.config)
      yield SWAPIServiceLive(apiClient, httpConfig.maxConcurrency)
    }

  val default: RLayer[SWAPIEnv, SWAPIClientService] = ApiClient.live >>> SWAPIClientService.layer

final private case class SWAPIServiceLive(apiClient: ApiClient, maxConcurrency: Int) extends SWAPIClientService:

  override def getFilms: IO[ClientError, Set[Film]] =
    ApiClient.getFilms.provideEnvironment(ZEnvironment(apiClient))

  override def getFilmsFromCharacter(id: Int): IO[ClientError, Set[String]] = {
    for
      people <- ApiClient.getCharacterFrom(id)
      films  <- ZIO
                 .foreachPar(people.films)(url => decodeUrlString(url).flatMap(ApiClient.getFilmFromUrl))
                 .withParallelism(maxConcurrency)
    yield films.map(_.title)
  }.provideEnvironment(ZEnvironment(apiClient))

  override def getCharacters: IO[ClientError, Set[Character]] =
    ApiClient.getCharacters.provideEnvironment(ZEnvironment(apiClient))

  override def getFilmsFromCharacters: IO[ClientError, Map[String, Set[String]]] =
    (for
      people <- ApiClient.getCharacters
      films  <-
        ZIO
          .foreachPar(people) { person =>
            ZIO
              .foreachPar(person.films) { url =>
                decodeUrlString(url).flatMap(ApiClient.getFilmFromUrl).map(_.title)
              }
              .withParallelism(maxConcurrency)
              .map(films => (person.name, films))
          }
          .withParallelism(maxConcurrency)
    yield films).map(_.toMap).provideEnvironment(ZEnvironment(apiClient))
