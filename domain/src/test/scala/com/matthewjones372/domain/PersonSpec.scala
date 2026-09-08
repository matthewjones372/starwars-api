package com.matthewjones372.domain

import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec
import zio.test.*

import scala.io.Source

object PersonSpec extends ZIOSpecDefault:
  def spec = suite("Person Spec")(
    test("Person should be able to be decoded from JSON") {
      val json   = Source.fromResource("people_json.json").getLines().mkString
      val people = json.to[Person]

      val expectedPerson = Person(
        name = "C-3PO",
        height = Some(167),
        mass = Some(75),
        hairColor = "n/a",
        skinColor = "gold",
        eyeColor = "yellow",
        birthYear = "112BBY",
        gender = Some("n/a"),
        homeworld = Some("https://swapi.dev/api/planets/1/"),
        films = Set("/films/1/?format=json", "/films/2/?format=json"),
        species = Some(Set("https://swapi.dev/api/species/2/")),
        vehicles = Some(Set.empty),
        starships = Some(Set.empty),
        url = "https://swapi.dev/api/people/2/"
      )

      assertTrue(people == Right(expectedPerson))
    },
    test("Can deal with unknown int values") {
      val aPerson = """
                      |  {
                      |    "name": "Cliegg Lars",
                      |    "height": "unknown",
                      |    "mass": "182",
                      |    "eye_color": "blue",
                      |    "species": [],
                      |    "hair_color": "brown",
                      |    "skin_color": "fair",
                      |    "eyeColor": "blue",
                      |    "birth_year": "82BBY",
                      |    "gender": "male",
                      |    "homeworld": "https://swapi.dev/api/planets/1/",
                      |    "films": [
                      |    ],
                      |    "url": "https://swapi.dev/api/species/2/"
                      |  }
                      |""".stripMargin

      val result = aPerson.to[Person]
      assertTrue(result.map(_.height) == Right(None))
    },
    test("an unmeasured height is encoded as absent rather than an empty string") {
      val unmeasured = Person(
        name = "Cliegg Lars",
        height = None,
        mass = Some(182),
        hairColor = "brown",
        skinColor = "fair",
        eyeColor = "blue",
        birthYear = "82BBY",
        gender = Some("male"),
        homeworld = None,
        films = Set.empty,
        species = None,
        vehicles = None,
        starships = None,
        url = "https://swapi.dev/api/people/62/"
      )

      val encoded = encodeAs(unmeasured)

      assertTrue(
        !encoded.contains("\"height\":\"\""),
        encoded.contains("\"mass\":\"182\""),
        encoded.to[Person].map(_.height) == Right(None)
      )
    }
  )
