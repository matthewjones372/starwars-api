package com.jones
package model

import zio.json.*

@jsonMemberNames(SnakeCase)
final case class Film(
  title: String,
  episodeId: Int,
  openingCrawl: String,
  director: String,
  producer: String,
  releaseDate: String,
  characters: Set[String],
  planets: Set[String],
  starships: Set[String],
  vehicles: Set[String],
  species: Set[String],
  created: String,
  edited: String,
  url: String
) derives JsonCodec
