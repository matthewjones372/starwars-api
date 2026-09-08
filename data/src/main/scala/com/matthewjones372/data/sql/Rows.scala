package com.matthewjones372.data.sql

import com.augustnagro.magnum.DbCodec
import com.matthewjones372.domain.{Film, Character}

private[sql] final case class CharacterRow(
  id: Int,
  name: String,
  height: Option[Int],
  mass: Option[Int],
  hairColor: String,
  skinColor: String,
  eyeColor: String,
  birthYear: String,
  gender: Option[String],
  homeworld: Option[String],
  url: String
) derives DbCodec:
  def toCharacter(
    films: Set[String],
    species: Set[String],
    vehicles: Set[String],
    starships: Set[String]
  ): Character =
    Character(
      name = name,
      height = height,
      mass = mass,
      hairColor = hairColor,
      skinColor = skinColor,
      eyeColor = eyeColor,
      birthYear = birthYear,
      gender = gender,
      homeworld = homeworld,
      films = films,
      species = Some(species),
      vehicles = Some(vehicles),
      starships = Some(starships),
      url = url
    )

private[sql] final case class FilmRow(
  id: Int,
  title: String,
  episodeId: Int,
  openingCrawl: String,
  director: String,
  producer: String,
  releaseDate: String,
  created: String,
  edited: String,
  url: String
) derives DbCodec:
  def toFilm(
    characters: Set[String],
    planets: Set[String],
    starships: Set[String],
    vehicles: Set[String],
    species: Set[String]
  ): Film =
    Film(
      title = title,
      episodeId = episodeId,
      openingCrawl = openingCrawl,
      director = director,
      producer = producer,
      releaseDate = releaseDate,
      characters = characters,
      planets = planets,
      starships = starships,
      vehicles = vehicles,
      species = species,
      created = created,
      edited = edited,
      url = url
    )
