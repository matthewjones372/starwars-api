package com.matthewjones372.domain

import com.matthewjones372.sorting.DynamicMultiSorter
import zio.schema.*
import zio.schema.annotation.fieldName

final case class Film(
  title: String,
  @fieldName("episode_id") episodeId: Int,
  director: String,
  producer: String,
  @fieldName("release_date") releaseDate: String,
  characters: Set[String],
  attributes: Map[String, String],
  links: Map[String, Set[String]],
  url: String,
  @fieldName("media_type") mediaType: Option[MediaKind]
) derives Schema,
      DynamicMultiSorter

final case class Films(count: Int, results: List[Film]) extends Paged[Film] derives Schema
