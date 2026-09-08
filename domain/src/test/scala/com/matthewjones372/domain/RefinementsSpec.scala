package com.matthewjones372.domain

import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec
import zio.test.*

object RefinementsSpec extends ZIOSpecDefault:
  def spec = suite("Refinements")(
    suite("EntityId")(
      test("accepts an id in the swapi range") {
        assertTrue(EntityId.from(1) == Right(EntityId(1)), EntityId.from(82) == Right(EntityId(82)))
      },
      test("rejects an id at or below zero") {
        assertTrue(EntityId.from(0).isLeft, EntityId.from(-1).isLeft)
      },
      test("carries the refinement through its schema") {
        assertTrue("3".to[EntityId] == Right(EntityId(3)), "0".to[EntityId].isLeft)
      }
    ),
    suite("PageNumber")(
      test("accepts the first page and everything after it") {
        assertTrue(PageNumber.from(1) == Right(PageNumber.first), PageNumber.from(99).isRight)
      },
      test("rejects a page that cannot be served") {
        assertTrue(PageNumber.from(0).isLeft, PageNumber.from(-5).isLeft)
      }
    ),
    suite("PageSize")(
      test("accepts a size within the bound") {
        assertTrue(PageSize.from(1).isRight, PageSize.from(10) == Right(PageSize.default), PageSize.from(100).isRight)
      },
      test("rejects a size outside the bound") {
        assertTrue(PageSize.from(0).isLeft, PageSize.from(101).isLeft)
      }
    )
  )
