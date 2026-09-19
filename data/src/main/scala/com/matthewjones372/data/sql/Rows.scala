package com.matthewjones372.data.sql

import com.augustnagro.magnum.DbCodec
import com.matthewjones372.domain.{Character, Film, MediaKind}

private[sql] final case class CharacterRow(id: Int, name: String, url: String) derives DbCodec:
  def toCharacter(
    films: Set[String],
    portrayedBy: Set[String],
    attributes: Map[String, String],
    links: Map[String, Set[String]]
  ): Character =
    Character(
      name = name,
      films = films,
      portrayedBy = portrayedBy,
      attributes = attributes,
      links = links,
      url = url
    )

private[sql] final case class FilmRow(
  id: Int,
  title: String,
  episodeId: Int,
  director: String,
  producer: String,
  releaseDate: String,
  url: String,
  mediaType: Option[String]
) derives DbCodec:
  def toFilm(
    characters: Set[String],
    cast: Set[String],
    attributes: Map[String, String],
    links: Map[String, Set[String]]
  ): Film =
    Film(
      title = title,
      episodeId = episodeId,
      director = director,
      producer = producer,
      releaseDate = releaseDate,
      characters = characters,
      cast = cast,
      attributes = attributes,
      links = links,
      url = url,
      mediaType = mediaType.flatMap(MediaKind.from(_).toOption)
    )
