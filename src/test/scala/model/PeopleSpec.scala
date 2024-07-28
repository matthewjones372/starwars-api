package com.jones
package model

import zio.test.*
import zio.test.Assertion.*

import scala.io.Source
import zio.json.*

object PeopleSpec extends ZIOSpecDefault:
  def spec = suite("People Spec")(
    test("People should be able to be decoded from JSON") {
      val json   = Source.fromResource("people_json.json").getLines().mkString
      val people = json.mkString.fromJson[People]

      val expectedPerson = People(
        name = "C-3PO",
        height = 167,
        mass = 75,
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
    }
  )
