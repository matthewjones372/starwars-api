package com.jones
package data

import domain.*

import zio.*

trait SWDataRepo:
  def getFilm(id: Int): IO[DataRepoError, Film]
  def getPeople(id: Int): IO[DataRepoError, People]

object SWDataRepo:
  def layer: RLayer[Any, SWDataRepo] = ZLayer.succeed {
    new SWDataRepo:

      override def getFilm(id: Int): IO[DataRepoError, Film] =
        ZIO.succeed(
          Film(
            "A New Hope",
            32,
            "asdfa",
            "https://swapi.dev/api/films/1/",
            "https://swapi.dev/api/people/1/",
            "https://swapi.dev/api/planets/1/",
            Set.empty,
            Set.empty,
            Set.empty,
            Set.empty,
            Set.empty,
            "",
            "",
            ""
          )
        )

      override def getPeople(id: Int): IO[DataRepoError, People] =
        ZIO.fail(DataRepoError.PersonNotFound("People not found", id))
  }
