package com.jones
package model

import zio.json.*

@jsonMemberNames(SnakeCase)
final case class FilmRaw(
  title: String,
  episodeId: Int,
  openingCrawl: String,
  director: String,
  producer: String,
  releaseDate: String,
  characters: List[String],
  planets: List[String],
  starships: List[String],
  vehicles: List[String],
  species: List[String],
  created: String,
  edited: String,
  url: String
) derives JsonCodec
