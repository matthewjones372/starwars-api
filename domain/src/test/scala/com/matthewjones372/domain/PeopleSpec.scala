package com.matthewjones372.domain

import scala.io.Source

import zio.test.*
import zio.json.*

object PeopleSpec extends ZIOSpecDefault:
  def spec = suite("People Spec")(
    test("People should be able to be decoded from JSON") {
      val json   = Source.fromResource("people_json.json").getLines().mkString
      val people = json.mkString.fromJson[People]

      val expectedPerson = People(
        name = "C-3PO",
        height = Some(167),
        mass = Some(75),
        hairColor = "n/a",
        skinColor = "gold",
        eyeColor = "yellow",
        birthYear = "112BBY",
        gender = "n/a",
        homeworld = "https://swapi.dev/api/planets/1/",
        films = Set("/films/1/?format=json", "/films/2/?format=json"),
        species = Set("https://swapi.dev/api/species/2/"),
        vehicles = Set(),
        starships = Set()
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
                      |    "vehicles": [],
                      |    "starships": [],
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

      val result = aPerson.fromJson[People]
      assertTrue(result.map(_.height) == Right(None))
    }
  )
