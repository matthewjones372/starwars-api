package com.jones

import client.{FilmClientService, HttpClientConfig, PersonClientService, SWAPIService}

import zio.*
import zio.http.*

import java.net.URI

object BootStrap extends ZIOAppDefault {
  def run = {
    val program = for {
      swapi <- ZIO.service[SWAPIService]
      r2    <- swapi.getFilmsFromPerson(1)
      _     <- Console.printLine(r2)
    } yield ExitCode.success

    program
      .provide(
        PersonClientService.layer,
        FilmClientService.layer,
        SWAPIService.layer,
        ZLayer.succeed(HttpClientConfig(URL.fromURI(new URI("https://swapi.dev/api")).get)),
        Scope.default,
        Client.default
      )
  }
}
