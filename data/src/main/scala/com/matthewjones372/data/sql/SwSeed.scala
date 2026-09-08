package com.matthewjones372.data.sql

import com.augustnagro.magnum.*
import com.matthewjones372.data.SWDataRepo
import com.matthewjones372.domain.*
import zio.*

object SwSeed:
  def fromBundledData: RIO[ZTransactor, Unit] =
    SWDataRepo.bundledEntities.flatMap(seed)

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
    ZIO.fromEither(SWDataRepo.parseEntityId(url)).mapError(new IllegalArgumentException(_))

  private def insertPerson(entry: (Int, Character))(using DbCon): Unit =
    val (id, person) = entry
    val _            =
      sql"""insert into people (id, name, height, mass, hair_color, skin_color, eye_color, birth_year, gender, homeworld, url)
            values ($id, ${person.name}, ${person.height}, ${person.mass}, ${person.hairColor}, ${person.skinColor},
                    ${person.eyeColor}, ${person.birthYear}, ${person.gender}, ${person.homeworld}, ${person.url})
            on conflict (id) do nothing""".update.run()

    insertUrls("people_films", "person_id", "film_url", id, person.films)
    insertUrls("people_species", "person_id", "species_url", id, person.species.getOrElse(Set.empty))
    insertUrls("people_vehicles", "person_id", "vehicle_url", id, person.vehicles.getOrElse(Set.empty))
    insertUrls("people_starships", "person_id", "starship_url", id, person.starships.getOrElse(Set.empty))

  private def insertFilm(entry: (Int, Film))(using DbCon): Unit =
    val (id, film) = entry
    val _          =
      sql"""insert into films (id, title, episode_id, opening_crawl, director, producer, release_date, created, edited, url)
            values ($id, ${film.title}, ${film.episodeId}, ${film.openingCrawl}, ${film.director}, ${film.producer},
                    ${film.releaseDate}, ${film.created}, ${film.edited}, ${film.url})
            on conflict (id) do nothing""".update.run()

    insertUrls("film_characters", "film_id", "character_url", id, film.characters)
    insertUrls("film_planets", "film_id", "planet_url", id, film.planets)
    insertUrls("film_starships", "film_id", "starship_url", id, film.starships)
    insertUrls("film_vehicles", "film_id", "vehicle_url", id, film.vehicles)
    insertUrls("film_species", "film_id", "species_url", id, film.species)

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
