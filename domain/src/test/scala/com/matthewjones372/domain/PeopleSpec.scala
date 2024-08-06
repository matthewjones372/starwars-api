package com.matthewjones372.domain

import zio.schema.codec.DecodeError
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec
import zio.test.*

import scala.io.Source

object PeopleSpec extends ZIOSpecDefault:
  def spec = suite("People Spec")(
    test("People should be able to be decoded from JSON") {
      val json   = Source.fromResource("people_json.json").getLines().mkString
      val people = json.to[People]

      val expectedPerson = People(
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
        starships = Some(Set.empty)
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
                      |    ]
                      |  }
                      |""".stripMargin

      val result = aPerson.to[People]
      assertTrue(result.map(_.height) == Right(None))
    }
  )
