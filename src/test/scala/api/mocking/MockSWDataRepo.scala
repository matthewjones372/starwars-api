package com.jones
package api.mocking

import data.*
import domain.*

import zio.*
import zio.mock.*

object MockSWDataRepo extends Mock[SWDataRepo]:

  object GetFilm   extends Effect[Int, DataRepoError, Film]
  object GetPerson extends Effect[Int, DataRepoError, People]
  object GetPeople extends Effect[Int, DataRepoError, Set[People]]

  val compose: URLayer[Proxy, SWDataRepo] = ZLayer {
    ZIO.serviceWith[Proxy] { proxy =>
      new SWDataRepo:
        override def getFilm(id: Int): IO[DataRepoError, Film] = proxy(GetFilm, id)

        override def getPerson(id: Int): IO[DataRepoError, People] = proxy(GetPerson, id)

        override def getPeople: IO[DataRepoError, Set[People]] =
          proxy(GetPeople, 0)
    }
  }
