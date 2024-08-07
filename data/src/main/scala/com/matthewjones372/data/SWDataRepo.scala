package com.matthewjones372.data

import com.matthewjones372.domain.*
import com.matthewjones372.sorting.{DynamicMultiSorter, SortBy}
import zio.*
import zio.concurrent.ConcurrentMap
import zio.http.*
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

trait SWDataRepo:
  def getFilm(id: Int): IO[DataRepoError, Film]
  def getPerson(id: Int): IO[DataRepoError, People]
  def getPeople(from: Option[Int], fetchSize: Option[Int], sortBy: Option[List[SortBy]]): IO[DataRepoError, Peoples]
  def getFilms(from: Option[Int], fetchSize: Option[Int]): IO[DataRepoError, Films]

object SWDataRepo:

  private def getId(url: String) =
    URL.decode(url).map(_.path.segments.last).toOption.get.toInt - 1

  def layer: RLayer[Any, SWDataRepo] = ZLayer.fromZIO {
    for
      peopleData <- ConcurrentMap.empty[Int, People]
      filmData   <- ConcurrentMap.empty[Int, Film]

      _          <- ZIO.logInfo("Reading in Star Wars Data")
      peopleJson <- ZIO.readFile("src/main/resources/people_data.json")
      filmJson   <- ZIO.readFile("src/main/resources/film_data.json")

      peoples <- ZIO
                   .fromEither(peopleJson.to[List[People]])
                   .tapError(e => ZIO.logError(s"Failed to parse ${e}"))
                   .mapError(e => new RuntimeException(e))
      films <- ZIO.fromEither(filmJson.to[List[Film]]).mapError(e => new RuntimeException(e))

      _ <- ZIO.logInfo("Parsed....")
      _ <- filmData.putAll(films.map(film => (getId(film.url), film))*)
      _ <- peopleData.putAll(peoples.map(person => (getId(person.url), person))*)
    yield InMemoryDataRepo(peopleData, filmData)
  }

final private case class InMemoryDataRepo(peopleData: ConcurrentMap[Int, People], filmData: ConcurrentMap[Int, Film])
    extends SWDataRepo:

  private val peopleDataList  = peopleData.toList
  private val peopleDataCount = peopleDataList.map(_.size)
  private val filmDataList    = filmData.toList
  private val filmDataCount   = filmData.toList.map(_.size)

  override def getFilms(from: Option[Int], fetchSize: Option[Int]): IO[DataRepoError, Films] =
    val data = (from, fetchSize) match
      case (Some(from), Some(fetchSize)) =>
        filmDataList.map(_.getPage(from, fetchSize))
      case (None, None) =>
        filmDataList
      case (None, Some(fetchSize)) =>
        filmDataList.map(_.slice(0, fetchSize))

      case (Some(from), None) =>
        filmDataList.map(_.slice(0, 10))
    for
      count <- filmDataCount
      films <- data.map(_.map { case (_, film) => film })
    yield Films(count, films)

  override def getFilm(id: Int): IO[DataRepoError, Film] =
    filmData.get(id - 1).someOrFail(DataRepoError.FilmNotFound("Film not found", id))

  override def getPerson(id: Int): IO[DataRepoError, People] =
    peopleData.get(id - 1).someOrFail(DataRepoError.PersonNotFound("Person not found", id))

  override def getPeople(
    from: Option[Int],
    fetchSize: Option[Int],
    sortBy: Option[List[SortBy]]
  ): IO[DataRepoError, Peoples] =

    // We wouldn't need to  do  this with a database...
    val peopleDataListSorted = peopleDataList
      .map(_.unzip)
      .map { case (idx, people) =>
        sortBy.map { sorts =>
          idx.zip(DynamicMultiSorter.sort(people, sorts))
        }.getOrElse(idx.zip(people))
      }

    val data = (from, fetchSize) match
      case (Some(from), Some(fetchSize)) =>
        peopleDataListSorted.map(_.getPage(from, fetchSize))
      case (None, None)            => peopleDataListSorted
      case (None, Some(fetchSize)) => peopleDataListSorted.map(_.slice(0, fetchSize))
      case (Some(from), None) =>
        peopleDataListSorted.map(_.slice(0, 10))

    for
      count  <- peopleDataCount
      people <- data.map(_.map { case (_, film) => film })
    yield Peoples(count, people)

  extension [A](data: List[A])
    private def getPage(page: Int, pageSize: Int): List[A] = {
      val from = (page - 1) * pageSize
      val to   = from + pageSize + 1
      data.slice(from, to)
    }
