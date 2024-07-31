package com.jones
package domain

import zio.json.*
import zio.schema.*
import zio.schema.annotation.fieldName

@jsonMemberNames(SnakeCase)
final case class Film(
  title: String,
  @fieldName("episode_id") episodeId: Int,
  @fieldName("opening_crawl") openingCrawl: String,
  director: String,
  producer: String,
  @fieldName("release_date") releaseDate: String,
  characters: Set[String],
  planets: Set[String],
  starships: Set[String],
  vehicles: Set[String],
  species: Set[String],
  created: String,
  edited: String,
  url: String
) derives JsonCodec

object Film:
  given Schema[Film] = DeriveSchema.gen

@jsonMemberNames(SnakeCase)
final case class Films(count: Int, next: Option[String], previous: Option[String], results: List[Film])
    derives JsonCodec
