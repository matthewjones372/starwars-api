package com.jones
package client

import client.ClientError.InvalidUrl

import zio.*
import zio.http.URL

final case class SWAPIServiceLive(clientApi: ClientApi) extends SWAPIService:

  override def getFilmsFromPerson(id: Int): IO[ClientError, List[String]] = {
    for {
      people <- ClientApi.getPersonFrom(id)
      films <- ZIO.foreachPar(people.films) { url =>
                 decodeUrlString(url).flatMap(ClientApi.getFilmFrom)
               }
    } yield films.map(_.title)
  }.provideEnvironment(ZEnvironment(clientApi))

  private def decodeUrlString(url: String): IO[InvalidUrl, URL] =
    ZIO.fromEither(URL.decode(url)).orElseFail(InvalidUrl(url))
