package com.jones

package client
import model.FilmRaw

import zio.http.Client
import zio.*

trait FilmClientService:
  def getFrom(id: Int): IO[ClientServiceError, FilmRaw]
  
  def getFromUrl(url: String): IO[ClientServiceError, FilmRaw]

object FilmClientService:
  private type Env = Client & Scope & HttpClientConfig

  def getFrom(id: Int)(implicit trace: Trace) =
    ZIO.serviceWithZIO[FilmClientService](_.getFrom(id))
    
  def getFromUrl(url: String)(implicit trace: Trace) =
    ZIO.serviceWithZIO[FilmClientService](_.getFromUrl(url))

  def layer: URLayer[Env, FilmClientService] =
    ZLayer.fromFunction(FilmClientServiceLive.apply)
