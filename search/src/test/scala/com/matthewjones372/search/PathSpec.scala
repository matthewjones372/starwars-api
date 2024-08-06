package com.matthewjones372.search

import zio.Chunk
import zio.test.*
import zio.test.Assertion.*

object PathSpec extends ZIOSpecDefault:
  def spec = suite("PathSpec")(
    test("Renders Paths correctly") {
      val path = Path("a", "b", Some(Chunk(("a", "b"), ("b", "c"), ("c", "d"))))
      // Removing the color codes
      val plainString = path.toString.replaceAll("\u001B\\[[;\\d]*m", "")
      assertTrue(plainString == "(a is in b) -> (b is in c) -> c")
    }
  )
