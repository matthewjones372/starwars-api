package com.jones
package client

import model.PeopleRaw

import zio.*
import zio.http.*
import zio.json.*

final case class PersonClientServiceLive(client: Client, scope: Scope, httpConfig: HttpClientConfig)
    extends PersonClientService:
  private val env = ZEnvironment(client, scope)

  override def getFrom(id: Int): IO[ClientServiceError, PeopleRaw] = {
    for {
      response <- Client.request(Request.get(httpConfig.baseUrl / "people" / id.toString / "?format=json"))
      body     <- if (response.status.isClientError) ZIO.fail(ClientServiceError.NotFound(id)) else response.body.asString
      people   <- ZIO.fromEither(body.fromJson[PeopleRaw].left.map(err => ClientServiceError.DeserializationError(err)))
    } yield people
  }.catchAll { case err: ClientServiceError =>
    ZIO.fail(err)
  }
    .provideEnvironment(env)
