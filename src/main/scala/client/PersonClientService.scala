package com.jones
package client

import model.PeopleRaw

import zio.*
import zio.http.Client

trait PersonClientService:
  def getFrom(id: Int): IO[ClientServiceError, PeopleRaw]

object PersonClientService:
  private type Env = Client & Scope & HttpClientConfig

  def getFrom(id: Int)(implicit trace: Trace) =
    ZIO.serviceWithZIO[PersonClientService](_.getFrom(id))

  def layer: URLayer[Env, PersonClientService] =
    ZLayer.fromFunction(PersonClientServiceLive.apply)
