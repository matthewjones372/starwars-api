package com.matthewjones372.data.sql

import com.augustnagro.magnum.*
import com.matthewjones372.data.{DataRepoError, DataRepo}
import com.matthewjones372.domain.*
import com.matthewjones372.sorting.{DynamicMultiSorter, FieldOrdering, SortBy}
import zio.*

object SqlDataRepo:
  // The url sets live in child tables, so they can be sorted in memory but never named in an order by.
  private val nonSortableFields = Set("films", "attributes", "links")

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

  // An attribute key also arrives from the query string and also cannot be bound
  // as a parameter inside an order by. Anything that is not a plain identifier is
  // dropped rather than escaped.
  private val attributeKey = "[a-z][a-z0-9_]{0,39}".r

  private[sql] def attributeOrder(key: String, direction: String): String =
    val value = s"(select value from character_attributes where person_id = people.id and key = '$key')"
    // Values are text, so the ones that are numbers are compared as numbers and
    // everything else falls through to the comparison behind them.
    s"case when $value glob '[0-9]*' then cast($value as real) end $direction, $value $direction"

  private[sql] def orderByClause(sortBy: Option[List[SortBy]]): String =
    val clauses = sortBy.getOrElse(Nil).flatMap { sort =>
      val direction = sort.ordering match
        case FieldOrdering.ASC  => "asc"
        case FieldOrdering.DESC => "desc"
      sortableColumns
        .get(sort.key)
        .map(column => s"$column $direction")
        .orElse(Option.when(attributeKey.matches(sort.key))(attributeOrder(sort.key, direction)))
    }
    (clauses :+ "id asc").mkString("order by ", ", ", "")

  private[sql] def offsetFor(page: Option[PageNumber], pageSize: Option[PageSize]): Option[(Int, Int)] =
    (page, pageSize) match
      case (None, None) => None
      case _            =>
        val size = pageSize.getOrElse(PageSize.default)
        Some((page.getOrElse(PageNumber.first) - 1) * size -> size)

  def apply(transactor: ZTransactor): DataRepo = SqlDataRepoLive(transactor)

  val layer: URLayer[ZTransactor, DataRepo] =
    ZLayer.fromFunction(SqlDataRepoLive.apply)

final private case class SqlDataRepoLive(transactor: ZTransactor) extends DataRepo:
  import SqlDataRepo.*

  private val personColumns = "id, name, url"

  private val filmColumns = "id, title, episode_id, director, producer, release_date, url, media_type"

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

  private def attributesFor(table: String, ownerColumn: String, ids: Seq[Int])(using
    DbCon
  ): Map[Int, Map[String, String]] =
    if ids.isEmpty then Map.empty
    else
      Frag(s"select $ownerColumn, key, value from $table where $ownerColumn in (${ids.mkString(",")})")
        .query[(Int, String, String)]
        .run()
        .groupMap(_._1)(row => row._2 -> row._3)
        .view
        .mapValues(_.toMap)
        .toMap

  private def linksFor(table: String, ownerColumn: String, ids: Seq[Int])(using
    DbCon
  ): Map[Int, Map[String, Set[String]]] =
    if ids.isEmpty then Map.empty
    else
      Frag(s"select $ownerColumn, rel, url from $table where $ownerColumn in (${ids.mkString(",")})")
        .query[(Int, String, String)]
        .run()
        .groupMap(_._1)(row => row._2 -> row._3)
        .view
        .mapValues(_.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap)
        .toMap

  private def peopleFrom(rows: Seq[CharacterRow])(using DbCon): List[Character] =
    val ids        = rows.map(_.id)
    val films      = urlsFor("people_films", "person_id", "film_url", ids)
    val attributes = attributesFor("character_attributes", "person_id", ids)
    val links      = linksFor("character_links", "person_id", ids)

    rows.map { row =>
      row.toCharacter(
        films = films.getOrElse(row.id, Set.empty),
        attributes = attributes.getOrElse(row.id, Map.empty),
        links = links.getOrElse(row.id, Map.empty)
      )
    }.toList

  private def filmsFrom(rows: Seq[FilmRow])(using DbCon): List[Film] =
    val ids        = rows.map(_.id)
    val characters = urlsFor("film_characters", "film_id", "character_url", ids)
    val attributes = attributesFor("film_attributes", "film_id", ids)
    val links      = linksFor("film_links", "film_id", ids)

    rows.map { row =>
      row.toFilm(
        characters = characters.getOrElse(row.id, Set.empty),
        attributes = attributes.getOrElse(row.id, Map.empty),
        links = links.getOrElse(row.id, Map.empty)
      )
    }.toList

  private def run[A](label: String)(query: DbCon ?=> A): IO[DataRepoError, A] =
    transactor
      .connect(query)
      .mapError(error => DataRepoError.UnexpectedError(s"$label failed", new RuntimeException(error)))

  override def getCharacter(id: EntityId): IO[DataRepoError, Character] =
    run(s"Looking up person $id") {
      Frag(s"select $personColumns from people where id = $id").query[CharacterRow].run()
    }.flatMap { rows =>
      run(s"Loading person $id")(peopleFrom(rows)).flatMap { people =>
        ZIO.fromOption(people.headOption).orElseFail(DataRepoError.CharacterNotFound("Character not found", id))
      }
    }

  override def getFilm(id: EntityId): IO[DataRepoError, Film] =
    run(s"Looking up film $id") {
      Frag(s"select $filmColumns from films where id = $id").query[FilmRow].run()
    }.flatMap { rows =>
      run(s"Loading film $id")(filmsFrom(rows)).flatMap { films =>
        ZIO.fromOption(films.headOption).orElseFail(DataRepoError.FilmNotFound("Film not found", id))
      }
    }

  override def getCharacters(
    from: Option[PageNumber],
    fetchSize: Option[PageSize],
    sortBy: Option[List[SortBy]]
  ): IO[DataRepoError, Characters] =
    val limits = offsetFor(from, fetchSize).fold("")((offset, size) => s" limit $size offset $offset")
    run("Listing people") {
      val count = Frag("select count(*) from people").query[Int].run().head
      val rows  = Frag(s"select $personColumns from people ${orderByClause(sortBy)}$limits").query[CharacterRow].run()
      Characters(count, peopleFrom(rows))
    }

  override def getFilms(from: Option[PageNumber], fetchSize: Option[PageSize]): IO[DataRepoError, Films] =
    val limits = offsetFor(from, fetchSize).fold("")((offset, size) => s" limit $size offset $offset")
    run("Listing films") {
      val count = Frag("select count(*) from films").query[Int].run().head
      val rows  = Frag(s"select $filmColumns from films order by id asc$limits").query[FilmRow].run()
      Films(count, filmsFrom(rows))
    }
