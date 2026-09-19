package com.matthewjones372.data

import com.matthewjones372.domain.*
import com.matthewjones372.sorting.SortBy
import zio.*
import zio.http.*
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

trait DataRepo:
  def getFilm(id: EntityId): IO[DataRepoError, Film]
  def getCharacter(id: EntityId): IO[DataRepoError, Character]
  def getCharacters(
    from: Option[PageNumber],
    fetchSize: Option[PageSize],
    sortBy: Option[List[SortBy]]
  ): IO[DataRepoError, Characters]
  def getFilms(from: Option[PageNumber], fetchSize: Option[PageSize]): IO[DataRepoError, Films]

object DataRepo:
  // Public because an entity's id is only in its url, and `http-api` needs it to
  // key a response by the id the path carries.
  def parseEntityId(url: String): Either[String, EntityId] =
    URL
      .decode(url)
      .toOption
      .flatMap(_.path.segments.filter(_.nonEmpty).lastOption)
      .flatMap(_.toIntOption)
      .toRight(s"Could not parse an entity id from url '$url'")
      .flatMap(EntityId.from)

  private[data] def paginate[A](data: List[A], page: Option[PageNumber], pageSize: Option[PageSize]): List[A] =
    (page, pageSize) match
      case (None, None) => data
      case _            =>
        val size   = pageSize.getOrElse(PageSize.default)
        val offset = (page.getOrElse(PageNumber.first) - 1) * size
        data.slice(offset, offset + size)

  def fromEntities(people: List[Character], films: List[Film]): IO[DataRepoError, DataRepo] =
    for
      peopleById <- ZIO.foreach(people)(person => keyOf(person.url).map(_ -> person)).map(_.toMap)
      filmsById  <- ZIO.foreach(films)(film => keyOf(film.url).map(_ -> film)).map(_.toMap)
    yield InMemoryDataRepo(peopleById, filmsById)

  private def keyOf(url: String): IO[DataRepoError, EntityId] =
    ZIO
      .fromEither(parseEntityId(url))
      .mapError(message => DataRepoError.UnexpectedError(message, new IllegalArgumentException(message)))

  private[data] val defaultPublicUrl = "http://localhost:8080"

  // The bundled data holds paths, so the host a client should call back on is
  // only known at startup. Resolving here rather than per request keeps the
  // promise `http-api` encodes its responses once on.
  private[data] def publicUrl: UIO[String] =
    System.envOrElse("PUBLIC_BASE_URL", defaultPublicUrl).orDie.map(_.stripSuffix("/"))

  private[data] def resolve(baseUrl: String)(path: String): String =
    if path.startsWith("/") then baseUrl + path else path

  // Every relation a universe records lives in `links`, so a new one resolves
  // here without a line of its own.
  private[data] def resolved(baseUrl: String)(person: Character): Character =
    val at = resolve(baseUrl)
    person.copy(
      films = person.films.map(at),
      portrayedBy = person.portrayedBy.map(at),
      links = person.links.view.mapValues(_.map(at)).toMap,
      url = at(person.url)
    )

  private[data] def resolved(baseUrl: String)(film: Film): Film =
    val at = resolve(baseUrl)
    film.copy(
      characters = film.characters.map(at),
      cast = film.cast.map(at),
      links = film.links.view.mapValues(_.map(at)).toMap,
      url = at(film.url)
    )

  private[data] def bundledEntities(universe: UniverseId): Task[(List[Character], List[Film])] =
    for
      baseUrl    <- publicUrl
      peopleJson <- readResource(s"${universe.slug}_people.json")
      filmJson   <- readResource(s"${universe.slug}_films.json")
      people     <- decode[Character](peopleJson, "people").map(_.map(resolved(baseUrl)))
      films      <- decode[Film](filmJson, "films").map(_.map(resolved(baseUrl)))
      _          <- ZIO.logInfo(s"Parsed ${people.size} people and ${films.size} films for ${universe.label}")
    yield (people, films)

  def of(universe: UniverseId): Task[DataRepo] =
    bundledEntities(universe).flatMap((people, films) => fromEntities(people, films))

  def layer: RLayer[Any, DataRepo] = ZLayer.fromZIO(of(UniverseId.default))

  private[data] def readResource(name: String): Task[String] =
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

final private case class InMemoryDataRepo(peopleById: Map[EntityId, Character], filmsById: Map[EntityId, Film])
    extends DataRepo:

  private val orderedPeople = peopleById.toList.sortBy(_._1).map(_._2)
  private val orderedFilms  = filmsById.toList.sortBy(_._1).map(_._2)

  override def getFilm(id: EntityId): IO[DataRepoError, Film] =
    ZIO.fromOption(filmsById.get(id)).orElseFail(DataRepoError.FilmNotFound("Film not found", id))

  override def getCharacter(id: EntityId): IO[DataRepoError, Character] =
    ZIO.fromOption(peopleById.get(id)).orElseFail(DataRepoError.CharacterNotFound("Character not found", id))

  override def getFilms(from: Option[PageNumber], fetchSize: Option[PageSize]): IO[DataRepoError, Films] =
    ZIO.succeed(Films(orderedFilms.size, DataRepo.paginate(orderedFilms, from, fetchSize)))

  override def getCharacters(
    from: Option[PageNumber],
    fetchSize: Option[PageSize],
    sortBy: Option[List[SortBy]]
  ): IO[DataRepoError, Characters] =
    val sorted = sortBy.fold(orderedPeople)(Sorting.characters(orderedPeople, _))
    ZIO.succeed(Characters(orderedPeople.size, DataRepo.paginate(sorted, from, fetchSize)))
