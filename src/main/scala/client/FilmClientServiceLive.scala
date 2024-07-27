package com.jones
package client

import com.jones.model.FilmRaw
import zio.*
import zio.http.*
import zio.json.*

final case class FilmClientServiceLive(client: Client, scope: Scope, httpConfig: HttpClientConfig)
    extends FilmClientService:
  private val env = ZEnvironment(client, scope)

  override def getFrom(id: Int): IO[ClientServiceError, FilmRaw] = {
    for {
      response <- Client.request(Request.get(httpConfig.baseUrl / "films" / id.toString / "?format=json"))
      body     <- if (response.status.isClientError) ZIO.fail(ClientServiceError.NotFound(id)) else response.body.asString
      film     <- ZIO.fromEither(body.fromJson[FilmRaw].left.map(err => ClientServiceError.DeserializationError(err)))
    } yield film
  }.catchAll { case err: ClientServiceError =>
    ZIO.fail(err)
  }
    .provideEnvironment(env)

  override def getFromUrl(url: String): IO[ClientServiceError, FilmRaw] = {
    for {
      response <- Client.request(Request.get(url))
      body     <- if (response.status.isClientError) ZIO.fail(ClientServiceError.UnexpectedError(url)) else response.body.asString
      film     <- ZIO.fromEither(body.fromJson[FilmRaw].left.map(err => ClientServiceError.DeserializationError(err)))
    } yield film
  }.catchAll { case err: ClientServiceError =>
    ZIO.fail(err)
  }
    .provideEnvironment(env)
