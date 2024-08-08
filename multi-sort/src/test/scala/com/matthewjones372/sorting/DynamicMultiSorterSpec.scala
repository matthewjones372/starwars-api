package com.matthewjones372.sorting

import zio.test.*
import zio.test.Assertion.*

object DynamicMultiSorterSpec extends ZIOSpecDefault:
  case class SimpleCaseClass(name: String) derives DynamicMultiSorter
  case class MultipleFields(name: String, age: Int) derives DynamicMultiSorter

  def spec = suite("Dynamic sorter")(
    test("can generate a sorter for a simple case class") {
      val simpleCases = List(SimpleCaseClass("Z"), SimpleCaseClass("B"), SimpleCaseClass("A"), SimpleCaseClass("D"))

      val sorts = List(SortBy("name", FieldOrdering.ASC))

      val expectedCases = List(
        SimpleCaseClass(name = "A"),
        SimpleCaseClass(name = "B"),
        SimpleCaseClass(name = "D"),
        SimpleCaseClass(name = "Z")
      )

      assertTrue(DynamicMultiSorter.sort(simpleCases, sorts) == expectedCases)
    },
    test("can generate a sorter with multiple fields of different types") {
      val multipleFields = List(
        MultipleFields("R", 5),
        MultipleFields("C", 2000),
        MultipleFields("B", 3),
        MultipleFields("Z", 100)
      )

      val sorts = List(SortBy("age", FieldOrdering.DESC), SortBy("name", FieldOrdering.ASC))
      val expectedCases = List(
        MultipleFields(
          name = "C",
          age = 2000
        ),
        MultipleFields(
          name = "Z",
          age = 100
        ),
        MultipleFields(
          name = "R",
          age = 5
        ),
        MultipleFields(
          name = "B",
          age = 3
        )
      )

      assertTrue(DynamicMultiSorter.sort(multipleFields, sorts) == expectedCases)
    },
    test("ignores a sort which refers to a name which doesn't exist") {
      val simpleCases = List(SimpleCaseClass("Z"), SimpleCaseClass("B"), SimpleCaseClass("A"), SimpleCaseClass("D"))
      val sorts       = List(SortBy("non-class name blah bla", FieldOrdering.ASC))
      assertTrue(DynamicMultiSorter.sort(simpleCases, sorts) == simpleCases)
    },
    test("returns the list  in order when given no sorts") {
      assertTrue(
        DynamicMultiSorter.sort(
          List(SimpleCaseClass("Z"), SimpleCaseClass("B"), SimpleCaseClass("A"), SimpleCaseClass("D")),
          List.empty
        ) == List(SimpleCaseClass("Z"), SimpleCaseClass("B"), SimpleCaseClass("A"), SimpleCaseClass("D"))
      )
    }
  )
