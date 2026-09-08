package com.matthewjones372.data

import com.matthewjones372.domain.*
import com.matthewjones372.sorting.{DynamicMultiSorter, SortBy}
import zio.*
import zio.http.*
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

trait SWDataRepo:
  def getFilm(id: Int): IO[DataRepoError, Film]
  def getPerson(id: Int): IO[DataRepoError, People]
  def getPeople(from: Option[Int], fetchSize: Option[Int], sortBy: Option[List[SortBy]]): IO[DataRepoError, Peoples]
  def getFilms(from: Option[Int], fetchSize: Option[Int]): IO[DataRepoError, Films]

object SWDataRepo:
  private val defaultPageSize = 10

  private[data] def parseEntityId(url: String): Either[String, Int] =
    URL
      .decode(url)
      .toOption
      .flatMap(_.path.segments.filter(_.nonEmpty).lastOption)
      .flatMap(_.toIntOption)
      .toRight(s"Could not parse an entity id from url '$url'")

  private[data] def paginate[A](data: List[A], page: Option[Int], pageSize: Option[Int]): List[A] =
    (page, pageSize) match
      case (None, None) => data
      case _ =>
        val size   = pageSize.getOrElse(defaultPageSize)
        val offset = (page.getOrElse(1) - 1) * size
        data.slice(offset, offset + size)

  def fromEntities(people: List[People], films: List[Film]): IO[DataRepoError, SWDataRepo] =
    for
      peopleById <- ZIO.foreach(people)(person => keyOf(person.url).map(_ -> person)).map(_.toMap)
      filmsById  <- ZIO.foreach(films)(film => keyOf(film.url).map(_ -> film)).map(_.toMap)
    yield InMemoryDataRepo(peopleById, filmsById)

  private def keyOf(url: String): IO[DataRepoError, Int] =
    ZIO
      .fromEither(parseEntityId(url))
      .mapError(message => DataRepoError.UnexpectedError(message, new IllegalArgumentException(message)))

  def layer: RLayer[Any, SWDataRepo] = ZLayer.fromZIO {
    for
      _          <- ZIO.logInfo("Reading in Star Wars Data")
      peopleJson <- readResource("people_data.json")
      filmJson   <- readResource("film_data.json")
      people     <- decode[People](peopleJson, "people")
      films      <- decode[Film](filmJson, "films")
      _          <- ZIO.logInfo(s"Parsed ${people.size} people and ${films.size} films")
      repo       <- fromEntities(people, films)
    yield repo
  }

  private def readResource(name: String): Task[String] =
    ZIO.scoped {
      ZIO
        .fromAutoCloseable(
          ZIO
            .attemptBlocking(Option(getClass.getResourceAsStream(s"/$name")))
            .someOrFail(
              new FileNotFoundException(s"Classpath resource '/$name' not found")
            )
        )
        .flatMap(stream => ZIO.attemptBlocking(new String(stream.readAllBytes(), StandardCharsets.UTF_8)))
    }

  private def decode[A](json: String, label: String)(using
    codec: zio.schema.codec.BinaryCodec[List[A]]
  ): Task[List[A]] =
    ZIO
      .fromEither(json.to[List[A]])
      .tapError(error => ZIO.logError(s"Failed to parse $label data: $error"))
      .mapError(error => new RuntimeException(s"Failed to parse $label data: $error"))

final private case class InMemoryDataRepo(peopleById: Map[Int, People], filmsById: Map[Int, Film]) extends SWDataRepo:

  private val orderedPeople = peopleById.toList.sortBy(_._1).map(_._2)
  private val orderedFilms  = filmsById.toList.sortBy(_._1).map(_._2)

  override def getFilm(id: Int): IO[DataRepoError, Film] =
    ZIO.fromOption(filmsById.get(id)).orElseFail(DataRepoError.FilmNotFound("Film not found", id))

  override def getPerson(id: Int): IO[DataRepoError, People] =
    ZIO.fromOption(peopleById.get(id)).orElseFail(DataRepoError.PersonNotFound("Person not found", id))

  override def getFilms(from: Option[Int], fetchSize: Option[Int]): IO[DataRepoError, Films] =
    ZIO.succeed(Films(orderedFilms.size, SWDataRepo.paginate(orderedFilms, from, fetchSize)))

  override def getPeople(
    from: Option[Int],
    fetchSize: Option[Int],
    sortBy: Option[List[SortBy]]
  ): IO[DataRepoError, Peoples] =
    val sorted = sortBy.fold(orderedPeople)(DynamicMultiSorter.sort(orderedPeople, _))
    ZIO.succeed(Peoples(orderedPeople.size, SWDataRepo.paginate(sorted, from, fetchSize)))
