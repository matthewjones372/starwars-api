package com.jones
package api

import api.SWAPIServerError.*
import data.{DataRepoError, SWDataRepo}
import domain.Film

import com.jones.data.DataRepoError.UnexpectedError
import zio.*

trait SWApi:
  def getFilmsFromPerson(personId: Int): IO[SWAPIServerError, List[Film]]

  def getFilmFrom(id: Int): IO[SWAPIServerError, Film]

object SWApi:
  def layer = ZLayer.fromFunction(SWAPIImpl.apply)

private case class SWAPIImpl(dataRepo: SWDataRepo) extends SWApi:
  override def getFilmsFromPerson(personId: Int): IO[SWAPIServerError, List[Film]] =
    ???

  override def getFilmFrom(id: Int): IO[SWAPIServerError, Film] =
    dataRepo.getFilm(id).mapError {

      case DataRepoError.PersonNotFound(message, personId) =>
        PersonNotFound(message, personId)

      case UnexpectedError(message, exception) =>
        ServerError()
    }
