package com.matthewjones372.api.client

import com.matthewjones372.api.client.config.HttpClientConfig
import com.matthewjones372.domain.*
import zio.*
import zio.http.*

trait UniverseClient:
  def getFilmsFromCharacter(id: Int): IO[ClientError, Set[String]]
  def getCharacters: IO[ClientError, Set[Character]]
  def getFilmsFromCharacters: IO[ClientError, Map[String, Set[String]]]
  def getFilms: IO[ClientError, Set[Film]]

object UniverseClient:
  type ClientEnv = Client & Scope

  def getFilmsFromCharacter(id: Int)(using Trace): ZIO[UniverseClient, ClientError, Set[String]] =
    ZIO.serviceWithZIO[UniverseClient](_.getFilmsFromCharacter(id))

  def getCharacters(using Trace): ZIO[UniverseClient, ClientError, Set[Character]] =
    ZIO.serviceWithZIO[UniverseClient](_.getCharacters)

  def getFilmsFromCharacters(using Trace): ZIO[UniverseClient, ClientError, Map[String, Set[String]]] =
    ZIO.serviceWithZIO[UniverseClient](_.getFilmsFromCharacters)

  def getFilms(using Trace): ZIO[UniverseClient, ClientError, Set[Film]] =
    ZIO.serviceWithZIO[UniverseClient](_.getFilms)

  private val layer: RLayer[ApiClient, UniverseClient] =
    ZLayer.fromZIO {
      for
        apiClient  <- ZIO.service[ApiClient]
        httpConfig <- ZIO.config(HttpClientConfig.config)
      yield UniverseClientLive(apiClient, httpConfig.maxConcurrency)
    }

  val default: RLayer[ClientEnv, UniverseClient] = ApiClient.live >>> UniverseClient.layer

final private case class UniverseClientLive(apiClient: ApiClient, maxConcurrency: Int) extends UniverseClient:

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
