package com.matthewjones372.domain

import com.matthewjones372.sorting.DynamicMultiSorter
import zio.schema.*
import zio.schema.annotation.fieldName

import scala.math.Ordered.orderingToOrdered
import scala.math.Ordering.ordered

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
) derives Schema,
      DynamicMultiSorter

final case class Films(count: Int, results: List[Film]) extends Paged[Film] derives Schema
