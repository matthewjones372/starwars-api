package com.matthewjones372.domain

import com.matthewjones372.sorting.DynamicMultiSorter
import zio.schema.*
import zio.schema.annotation.fieldName

/**
 * One performance: who played whom, in what.
 */
final case class Role(
  universe: UniverseId,
  character: String,
  @fieldName("character_name") characterName: String,
  film: String,
  @fieldName("film_title") filmTitle: String
) derives Schema

object Role:
  given Ordering[Role] =
    Ordering.by(role => (role.universe.ordinal, role.filmTitle, role.characterName))

/**
 * A real person, who unlike a character belongs to no single universe. An actor
 * in two of them is the only edge this API has that crosses a dataset, which is
 * what makes the graph over actors worth more than the one over characters.
 */
final case class Actor(
  name: String,
  films: Set[String],
  universes: Set[UniverseId],
  roles: List[Role],
  attributes: Map[String, String],
  url: String
) derives Schema,
      DynamicMultiSorter

final case class Actors(count: Int, results: List[Actor]) extends Paged[Actor] derives Schema
