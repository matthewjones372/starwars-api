package com.jones
package client

import zio.*

trait SWAPIService:
  def getFilmsFromPerson(id: Int): IO[ClientServiceError, List[String]]

object SWAPIService:
  def getFilmsFromPerson(id: Int)(implicit trace: Trace) =
    ZIO.serviceWithZIO[SWAPIService](_.getFilmsFromPerson(id))

  def layer =
    ZLayer.fromFunction(SWAPIServiceLive.apply)
    
  def  default = PersonClientService.layer ++ FilmClientService.layer ++ SWAPIService.layer

final case class SWAPIServiceLive(personClientService: PersonClientService, filmClientService: FilmClientService)
    extends SWAPIService:
  override def getFilmsFromPerson(id: Int): IO[ClientServiceError, List[String]] = {
    for {
      people <- PersonClientService.getFrom(id)
      films  <- ZIO.foreachPar(people.films)(FilmClientService.getFromUrl)
    } yield films.map(_.title)
  }.provideEnvironment(ZEnvironment(personClientService, filmClientService))
