package com.matthewjones372.domain

import com.matthewjones372.sorting.DynamicMultiSorter
import zio.schema.*

/**
 * A character in one of the universes this API serves.
 *
 * Only what every universe has is a field. A homeworld, a house and a realm are
 * all facts about a character that only one universe records, so they ride in
 * [attributes] when they are values and in [links] when they are urls. Keeping
 * them out of the type is what lets one schema, one sorter and one set of
 * endpoints serve every dataset.
 */
final case class Character(
  name: String,
  films: Set[String],
  attributes: Map[String, String],
  links: Map[String, Set[String]],
  url: String
) derives Schema,
      DynamicMultiSorter

object Character:
  def apply(name: String, films: Set[String], url: String): Character =
    Character(name, films, Map.empty, Map.empty, url)

final case class Characters(count: Int, results: List[Character]) extends Paged[Character] derives Schema
