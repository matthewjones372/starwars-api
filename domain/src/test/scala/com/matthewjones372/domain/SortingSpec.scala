package com.matthewjones372.domain

import com.matthewjones372.sorting.{FieldOrdering, SortBy}
import zio.test.*

object SortingSpec extends ZIOSpecDefault:
  private def person(name: String, attributes: (String, String)*) =
    Character(name = name, films = Set.empty, attributes = attributes.toMap, links = Map.empty, url = s"/p/$name")

  private val yoda       = person("Yoda", "height" -> "66")
  private val yaraelPoof = person("Yarael Poof", "height" -> "264")
  private val luke       = person("Luke", "height" -> "172")
  private val unmeasured = person("Arvel", "mass" -> "80")
  private val people     = List(yoda, yaraelPoof, luke, unmeasured)

  private def names(sorted: List[Character]) = sorted.map(_.name)

  def spec = suite("Sorting")(
    test("orders a numeric attribute as a number rather than as text") {
      val tallest = Sorting.characters(people, List(SortBy("height", FieldOrdering.DESC)))

      assertTrue(names(tallest).take(3) == List("Yarael Poof", "Luke", "Yoda"))
    },
    test("an absent attribute sorts last either way") {
      val ascending  = Sorting.characters(people, List(SortBy("height", FieldOrdering.ASC)))
      val descending = Sorting.characters(people, List(SortBy("height", FieldOrdering.DESC)))

      assertTrue(names(ascending).head == "Yoda", names(descending).head == "Yarael Poof")
    },
    test("still sorts on a field of the case class") {
      val byName = Sorting.characters(people, List(SortBy("name", FieldOrdering.ASC)))

      assertTrue(names(byName) == List("Arvel", "Luke", "Yarael Poof", "Yoda"))
    },
    test("mixes a field and an attribute, applying the first key first") {
      val tied = List(
        person("b", "house" -> "Gryffindor"),
        person("a", "house" -> "Gryffindor"),
        person("c", "house" -> "Slytherin")
      )

      val sorted = Sorting.characters(tied, List(SortBy("house", FieldOrdering.ASC), SortBy("name", FieldOrdering.ASC)))

      assertTrue(names(sorted) == List("a", "b", "c"))
    },
    test("ignores a key that is neither a field nor an attribute anyone carries") {
      assertTrue(names(Sorting.characters(people, List(SortBy("patronus", FieldOrdering.ASC)))).length == 4)
    },
    test("orders a non-numeric attribute as text") {
      val wizards = List(person("a", "house" -> "Slytherin"), person("b", "house" -> "Gryffindor"))

      assertTrue(names(Sorting.characters(wizards, List(SortBy("house", FieldOrdering.ASC)))) == List("b", "a"))
    }
  )
