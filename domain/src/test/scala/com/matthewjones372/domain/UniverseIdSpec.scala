package com.matthewjones372.domain

import zio.test.*

object UniverseIdSpec extends ZIOSpecDefault:
  def spec = suite("UniverseIdSpec")(
    test("round trips every slug") {
      assertTrue(UniverseId.values.forall(universe => UniverseId.from(universe.slug).contains(universe)))
    },
    test("rejects a slug it does not know") {
      assertTrue(UniverseId.from("startrek").isLeft)
    },
    test("no slug collides with a segment the api already answers on") {
      assertTrue(UniverseId.slugs.forall(slug => !UniverseId.reservedSegments.contains(slug)))
    },
    test("slugs are distinct") {
      assertTrue(UniverseId.slugs.distinct.size == UniverseId.values.length)
    }
  )
