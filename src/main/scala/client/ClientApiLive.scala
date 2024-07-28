package com.jones
package client

import client.ClientError.{UnexpectedClientError, UnexpectedSeverError}
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
    ResiliencyPolicy.policy.flatMap { run =>
      run {
        (for
          response <- client.request(Request.get(url))
          body     <- response.bodyOrClientError(url)
          result <- ZIO.fromEither {
                      body //TODO: Fix this string manipulation you shouldn't need this. Raise an issue with zio-http
                        .stripPrefix("\"")
                        .stripSuffix("\"")
                        .replaceAll("""\\""", "")
                        .fromJson[A]
                        .left
                        .map(err => ClientError.JsonDeserializationError(err))
                    }
        yield result).catchAll {
          case err: (UnexpectedClientError | UnexpectedSeverError) =>
            ZIO.logError(err.getMessage) *>
              ZIO.fail(err)
          case err: ClientError =>
            ZIO.logWarning(err.getMessage) *>
              ZIO.fail(err)
        }
      }.mapError(Policy.unwrap)
    }

extension (response: Response)
  def bodyOrClientError(url: URL): IO[ClientError, String] =
    if response.status.isSuccess then
      response.body.asString.orElseFail(ClientError.ResponseDeserializationError("Error decoding response"))
    else if response.status.code == 404 then ZIO.fail(ClientError.NotFound(url.encode))
    else if response.status.code == 429 then ZIO.fail(ClientError.RateLimited("Rate limited"))
    else if response.status.isClientError then ZIO.fail(ClientError.UnexpectedClientError("Client error"))
    else if response.status.isServerError then
      ZIO.fail(ClientError.UnexpectedSeverError(s"Server error ${response.status} - url $url"))
    else ZIO.fail(ClientError.UnexpectedClientError(s"Unknown error ${response.status}"))

object ResiliencyPolicy:
  private val retryPolicy = Retry.make(
    Retry.Schedules.whenCase { case _: UnexpectedSeverError => }(
      Retry.Schedules.exponentialBackoff(min = 1.second, max = 5.second)
    )
  )
  val policy = for {
    retry <- retryPolicy
  } yield retry.toPolicy
