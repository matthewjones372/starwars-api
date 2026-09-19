package com.matthewjones372.http.api

import com.matthewjones372.sorting.{FieldOrdering, SortBy}
import zio.test.*

object SortByParsingSpec extends ZIOSpecDefault:
  def spec = suite("sortBy parsing")(
    test("reads a single field and direction") {
      assertTrue(ApiServer.parseSortBy("name:ASC").contains(SortBy("name", FieldOrdering.ASC)))
    },
    test("accepts a direction in any case") {
      assertTrue(
        ApiServer.parseSortBy("name:asc").contains(SortBy("name", FieldOrdering.ASC)),
        ApiServer.parseSortBy("name:desc").contains(SortBy("name", FieldOrdering.DESC))
      )
    },
    test("trims whitespace around the field and the direction") {
      assertTrue(ApiServer.parseSortBy(" name : ASC ").contains(SortBy("name", FieldOrdering.ASC)))
    },
    test("rejects a pair with no direction") {
      assertTrue(ApiServer.parseSortBy("name").isEmpty, ApiServer.parseSortBy("").isEmpty)
    },
    test("rejects a direction that is not a known ordering") {
      assertTrue(
        ApiServer.parseSortBy("name:sideways").isEmpty,
        ApiServer.parseSortBy("name:").isEmpty
      )
    },
    test("rejects a pair carrying more than one separator") {
      assertTrue(ApiServer.parseSortBy("name:ASC:DESC").isEmpty)
    },
    test("reads a comma separated list in the order given") {
      assertTrue(
        ApiServer.parseSortByList("name:ASC,height:DESC") ==
          List(SortBy("name", FieldOrdering.ASC), SortBy("height", FieldOrdering.DESC))
      )
    },
    test("drops the unparseable entries of a list and keeps the rest") {
      assertTrue(
        ApiServer.parseSortByList("name:ASC,rubbish,height:sideways,mass:DESC") ==
          List(SortBy("name", FieldOrdering.ASC), SortBy("mass", FieldOrdering.DESC))
      )
    },
    test("yields nothing when no entry parses") {
      assertTrue(ApiServer.parseSortByList("rubbish,,more rubbish").isEmpty)
    }
  )
