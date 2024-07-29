package com.jones
package client

import zio.*
import zio.http.{Client, URL}

trait SWAPIService:
  def getFilmsFromPerson(id: Int): IO[ClientError, Set[String]]

object SWAPIService:
  type SWAPIEnv = Client & Scope & HttpClientConfig

  def getFilmsFromPerson(id: Int)(using trace: Trace): ZIO[SWAPIService, ClientError, Set[String]] =
    ZIO.serviceWithZIO[SWAPIService](_.getFilmsFromPerson(id))

  private val layer =
    ZLayer.fromFunction(SWAPIServiceLive.apply)

  val default = ApiClient.live >>> SWAPIService.layer

final private case class SWAPIServiceLive(apiClient: ApiClient) extends SWAPIService:

  override def getFilmsFromPerson(id: Int): IO[ClientError, Set[String]] = {
    for
      people <- ApiClient.getPersonFrom(id)
      films  <- ZIO.foreachPar(people.films)(url => decodeUrlString(url).flatMap(ApiClient.getFilmFrom))
    yield films.map(_.title)
  }.provideEnvironment(ZEnvironment(apiClient))
