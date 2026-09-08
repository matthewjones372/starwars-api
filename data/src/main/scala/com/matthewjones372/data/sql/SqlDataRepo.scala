package com.matthewjones372.data.sql

import com.augustnagro.magnum.*
import com.matthewjones372.data.{DataRepoError, SWDataRepo}
import com.matthewjones372.domain.*
import com.matthewjones372.sorting.{DynamicMultiSorter, FieldOrdering, SortBy}
import zio.*

object SqlDataRepo:
  private val defaultPageSize = 10

  // The url sets live in child tables, so they can be sorted in memory but never named in an order by.
  private val nonSortableFields = Set("films", "species", "vehicles", "starships")

  private[sql] def toColumn(field: String): String =
    field.flatMap(character => if character.isUpper then s"_${character.toLower}" else character.toString)

  // sortBy arrives from the query string and an order by clause cannot be parameterised, so the
  // accepted keys are derived from the case class rather than written out and left to drift.
  private[sql] val sortableColumns: Map[String, String] =
    DynamicMultiSorter
      .fieldNames[Character]
      .filterNot(nonSortableFields)
      .map(field => field -> toColumn(field))
      .toMap

  private[sql] def orderByClause(sortBy: Option[List[SortBy]]): String =
    val clauses = sortBy.getOrElse(Nil).flatMap { sort =>
      sortableColumns.get(sort.key).map { column =>
        val direction = sort.ordering match
          case FieldOrdering.ASC  => "asc"
          case FieldOrdering.DESC => "desc"
        s"$column $direction"
      }
    }
    (clauses :+ "id asc").mkString("order by ", ", ", "")

  private[sql] def offsetFor(page: Option[Int], pageSize: Option[Int]): Option[(Int, Int)] =
    (page, pageSize) match
      case (None, None) => None
      case _ =>
        val size = pageSize.getOrElse(defaultPageSize)
        Some(math.max((page.getOrElse(1) - 1) * size, 0) -> size)

  def apply(transactor: ZTransactor): SWDataRepo = SqlDataRepoLive(transactor)

  val layer: URLayer[ZTransactor, SWDataRepo] =
    ZLayer.fromFunction(SqlDataRepoLive.apply)

final private case class SqlDataRepoLive(transactor: ZTransactor) extends SWDataRepo:
  import SqlDataRepo.*

  private val personColumns =
    "id, name, height, mass, hair_color, skin_color, eye_color, birth_year, gender, homeworld, url"

  private val filmColumns =
    "id, title, episode_id, opening_crawl, director, producer, release_date, created, edited, url"

  private def urlsFor(table: String, ownerColumn: String, urlColumn: String, ids: Seq[Int])(using
    DbCon
  ): Map[Int, Set[String]] =
    if ids.isEmpty then Map.empty
    else
      // The ids are integers this class produced, so they cannot carry an injection.
      Frag(s"select $ownerColumn, $urlColumn from $table where $ownerColumn in (${ids.mkString(",")})")
        .query[(Int, String)]
        .run()
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

  private def peopleFrom(rows: Seq[CharacterRow])(using DbCon): List[Character] =
    val ids       = rows.map(_.id)
    val films     = urlsFor("people_films", "person_id", "film_url", ids)
    val species   = urlsFor("people_species", "person_id", "species_url", ids)
    val vehicles  = urlsFor("people_vehicles", "person_id", "vehicle_url", ids)
    val starships = urlsFor("people_starships", "person_id", "starship_url", ids)

    rows.map { row =>
      row.toCharacter(
        films = films.getOrElse(row.id, Set.empty),
        species = species.getOrElse(row.id, Set.empty),
        vehicles = vehicles.getOrElse(row.id, Set.empty),
        starships = starships.getOrElse(row.id, Set.empty)
      )
    }.toList

  private def filmsFrom(rows: Seq[FilmRow])(using DbCon): List[Film] =
    val ids        = rows.map(_.id)
    val characters = urlsFor("film_characters", "film_id", "character_url", ids)
    val planets    = urlsFor("film_planets", "film_id", "planet_url", ids)
    val starships  = urlsFor("film_starships", "film_id", "starship_url", ids)
    val vehicles   = urlsFor("film_vehicles", "film_id", "vehicle_url", ids)
    val species    = urlsFor("film_species", "film_id", "species_url", ids)

    rows.map { row =>
      row.toFilm(
        characters = characters.getOrElse(row.id, Set.empty),
        planets = planets.getOrElse(row.id, Set.empty),
        starships = starships.getOrElse(row.id, Set.empty),
        vehicles = vehicles.getOrElse(row.id, Set.empty),
        species = species.getOrElse(row.id, Set.empty)
      )
    }.toList

  private def run[A](label: String)(query: DbCon ?=> A): IO[DataRepoError, A] =
    transactor
      .connect(query)
      .mapError(error => DataRepoError.UnexpectedError(s"$label failed", new RuntimeException(error)))

  override def getCharacter(id: Int): IO[DataRepoError, Character] =
    run(s"Looking up person $id") {
      Frag(s"select $personColumns from people where id = $id").query[CharacterRow].run()
    }.flatMap { rows =>
      run(s"Loading person $id")(peopleFrom(rows)).flatMap { people =>
        ZIO.fromOption(people.headOption).orElseFail(DataRepoError.CharacterNotFound("Character not found", id))
      }
    }

  override def getFilm(id: Int): IO[DataRepoError, Film] =
    run(s"Looking up film $id") {
      Frag(s"select $filmColumns from films where id = $id").query[FilmRow].run()
    }.flatMap { rows =>
      run(s"Loading film $id")(filmsFrom(rows)).flatMap { films =>
        ZIO.fromOption(films.headOption).orElseFail(DataRepoError.FilmNotFound("Film not found", id))
      }
    }

  override def getCharacters(
    from: Option[Int],
    fetchSize: Option[Int],
    sortBy: Option[List[SortBy]]
  ): IO[DataRepoError, Characters] =
    val limits = offsetFor(from, fetchSize).fold("")((offset, size) => s" limit $size offset $offset")
    run("Listing people") {
      val count = Frag("select count(*) from people").query[Int].run().head
      val rows  = Frag(s"select $personColumns from people ${orderByClause(sortBy)}$limits").query[CharacterRow].run()
      Characters(count, peopleFrom(rows))
    }

  override def getFilms(from: Option[Int], fetchSize: Option[Int]): IO[DataRepoError, Films] =
    val limits = offsetFor(from, fetchSize).fold("")((offset, size) => s" limit $size offset $offset")
    run("Listing films") {
      val count = Frag("select count(*) from films").query[Int].run().head
      val rows  = Frag(s"select $filmColumns from films order by id asc$limits").query[FilmRow].run()
      Films(count, filmsFrom(rows))
    }
