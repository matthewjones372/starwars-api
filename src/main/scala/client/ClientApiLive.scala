package com.jones
package client

import client.ClientError.UnexpectedSeverError
import model.*

import nl.vroste.rezilience.{Policy, Retry}
import zio.*
import zio.http.*
import zio.json.*

final case class ClientApiLive(
  client: Client,
  scope: Scope,
  httpConfig: HttpClientConfig
) extends ClientApi:

  private val retryPolicy = Retry.make(
    Retry.Schedules.whenCase { case _: UnexpectedSeverError => }(
      Retry.Schedules.exponentialBackoff(min = 1.second, max = 5.second)
    )
  )
  private val policy = for {
    retry <- retryPolicy
  } yield retry.toPolicy

  private val env = ZEnvironment(client, scope)

  override def getPersonFrom(id: Int): IO[ClientError, People] =
    get[People](httpConfig.baseUrl / "people" / id.toString / "?format=json")
      .provideEnvironment(env)

  override def getFilmFrom(id: Int): IO[ClientError, Film] =
    get[Film](httpConfig.baseUrl / "films" / id.toString / "?format=json")
      .provideEnvironment(env)

  override def getFilmFrom(url: URL): IO[ClientError, Film] =
    get[Film](url).provideEnvironment(env)

  private def get[A: JsonDecoder](url: URL) =
    policy.flatMap { rp =>
      rp {
        (for
          response <- client.request(Request.get(url))
          body     <- response.bodyOrResponseError(url)
//          _        <- ZIO.logInfo(body)
          result <- ZIO.fromEither {
                      body
                        .stripPrefix("\"")
                        .stripSuffix("\"")
                        .replaceAll("""\\""", "")
                        .fromJson[A]
                        .left
                        .map(err => ClientError.JsonDeserializationError(err))
                    }
        yield result).catchAll { case err: ClientError =>
          ZIO.logError(err.getMessage) *>
            ZIO.fail(err)
        }
      }.mapError(Policy.unwrap)
    }

extension (response: Response)
  def bodyOrResponseError(url: URL): IO[ClientError, String] =
    if response.status.isSuccess then
      response.body.asString.orElseFail(ClientError.ResponseDeserializationError("Error decoding response"))
    else if response.status.code == 404 then ZIO.fail(ClientError.NotFound(url.encode))
    else if response.status.code == 429 then ZIO.fail(ClientError.RateLimited("Rate limited"))
    else if response.status.isClientError then ZIO.fail(ClientError.UnexpectedClientError("Client error"))
    else if response.status.isServerError then
      ZIO.fail(ClientError.UnexpectedSeverError(s"Server error ${response.status} - url $url"))
    else ZIO.fail(ClientError.UnexpectedClientError(s"Unknown error ${response.status}"))
