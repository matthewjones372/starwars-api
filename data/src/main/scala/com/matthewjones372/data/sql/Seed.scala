package com.matthewjones372.data.sql

import com.augustnagro.magnum.*
import com.matthewjones372.data.DataRepo
import com.matthewjones372.domain.*
import zio.*

object Seed:
  def fromBundledData: RIO[ZTransactor, Unit] =
    DataRepo.bundledEntities(UniverseId.default).flatMap(seed)

  def seed(entities: (List[Character], List[Film])): RIO[ZTransactor, Unit] =
    val (people, films) = entities
    for
      transactor <- ZIO.service[ZTransactor]
      peopleById <- ZIO.foreach(people)(person => idOf(person.url).map(_ -> person))
      filmsById  <- ZIO.foreach(films)(film => idOf(film.url).map(_ -> film))
      _          <- transactor.transact {
             filmsById.foreach(insertFilm)
             peopleById.foreach(insertPerson)
           }
      _ <- ZIO.logInfo(s"Seeded ${peopleById.size} people and ${filmsById.size} films")
    yield ()

  private def idOf(url: String): Task[Int] =
    ZIO.fromEither(DataRepo.parseEntityId(url)).mapError(new IllegalArgumentException(_))

  private def insertPerson(entry: (Int, Character))(using DbCon): Unit =
    val (id, person) = entry
    val _            =
      sql"""insert into people (id, name, url)
            values ($id, ${person.name}, ${person.url})
            on conflict (id) do nothing""".update.run()

    insertUrls("people_films", "person_id", "film_url", id, person.films)
    insertUrls("people_actors", "person_id", "actor_url", id, person.portrayedBy)
    insertAttributes("character_attributes", "person_id", id, person.attributes)
    insertLinks("character_links", "person_id", id, person.links)

  private def insertFilm(entry: (Int, Film))(using DbCon): Unit =
    val (id, film) = entry
    val _          =
      sql"""insert into films (id, title, episode_id, director, producer, release_date, url, media_type)
            values ($id, ${film.title}, ${film.episodeId}, ${film.director}, ${film.producer},
                    ${film.releaseDate}, ${film.url}, ${film.mediaType.map(MediaKind.name)})
            on conflict (id) do nothing""".update.run()

    insertUrls("film_characters", "film_id", "character_url", id, film.characters)
    insertUrls("film_cast", "film_id", "actor_url", id, film.cast)
    insertAttributes("film_attributes", "film_id", id, film.attributes)
    insertLinks("film_links", "film_id", id, film.links)

  private def insertAttributes(table: String, ownerColumn: String, ownerId: Int, attributes: Map[String, String])(using
    DbCon
  ): Unit =
    attributes.foreach { (key, value) =>
      val _ = Frag(
        s"insert into $table ($ownerColumn, key, value) values ($ownerId, ?, ?) on conflict do nothing",
        Seq(key, value),
        (statement, index) =>
          statement.setString(index, key)
          statement.setString(index + 1, value)
          index + 2
      ).update.run()
    }

  private def insertLinks(table: String, ownerColumn: String, ownerId: Int, links: Map[String, Set[String]])(using
    DbCon
  ): Unit =
    links.foreach { (rel, urls) =>
      urls.foreach { url =>
        val _ = Frag(
          s"insert into $table ($ownerColumn, rel, url) values ($ownerId, ?, ?) on conflict do nothing",
          Seq(rel, url),
          (statement, index) =>
            statement.setString(index, rel)
            statement.setString(index + 1, url)
            index + 2
        ).update.run()
      }
    }

  private def insertUrls(table: String, ownerColumn: String, urlColumn: String, ownerId: Int, urls: Set[String])(using
    DbCon
  ): Unit =
    urls.foreach { url =>
      val _ = Frag(
        s"insert into $table ($ownerColumn, $urlColumn) values ($ownerId, ?) on conflict do nothing",
        Seq(url),
        (statement, index) =>
          statement.setString(index, url)
          index + 1
      ).update.run()
    }
