package com.jones
package client

import zio.*
import zio.http.Client

trait SWAPIService:
  def getFilmsFromPerson(id: Int): IO[ClientError, Set[String]]

object SWAPIService:
  type Env = Client & Scope & HttpClientConfig

  def getFilmsFromPerson(id: Int)(implicit trace: Trace) =
    ZIO.serviceWithZIO[SWAPIService](_.getFilmsFromPerson(id))

  val layer =
    ZLayer.fromFunction(SWAPIServiceLive.apply)

  val default = ClientApi.layer >>> SWAPIService.layer
