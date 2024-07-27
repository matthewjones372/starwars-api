package com.jones
package model

import zio.test.*
import zio.test.Assertion.*

import scala.io.Source
import zio.json.*

object PeopleRawSpec extends ZIOSpecDefault:
  def spec = suite("People Spec")(
    test("People should be able to be decoded from JSON") {
      val json   = Source.fromResource("people_json.json").getLines().mkString
      val people = json.mkString.fromJson[PeopleRaw]

      val expectedPerson = PeopleRaw(
        "Luke Skywalker",
        172,
        77,
        "blond",
        "fair",
        "blue",
        "19BBY",
        "male",
        "https://swapi.dev/api/planets/1/",
        List(
          "https://swapi.dev/api/films/1/",
          "https://swapi.dev/api/films/2/",
          "https://swapi.dev/api/films/3/",
          "https://swapi.dev/api/films/6/"
        ),
        List(),
        List("https://swapi.dev/api/vehicles/14/", "https://swapi.dev/api/vehicles/30/"),
        List("https://swapi.dev/api/starships/12/", "https://swapi.dev/api/starships/22/")
      )

      assertTrue(people == Right(expectedPerson))
    }
  )
