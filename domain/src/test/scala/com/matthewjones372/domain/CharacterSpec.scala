package com.matthewjones372.domain

import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec
import zio.test.*

import scala.io.Source

object CharacterSpec extends ZIOSpecDefault:
  def spec = suite("Character Spec")(
    test("Character should be able to be decoded from JSON") {
      val json   = Source.fromResource("people_json.json").getLines().mkString
      val people = json.to[Character]

      val expectedPerson = Character(
        name = "C-3PO",
        films = Set("/films/1/?format=json", "/films/2/?format=json"),
        portrayedBy = Set.empty,
        attributes = Map(
          "height"     -> "167",
          "mass"       -> "75",
          "hair_color" -> "n/a",
          "skin_color" -> "gold",
          "eye_color"  -> "yellow",
          "birth_year" -> "112BBY",
          "gender"     -> "n/a"
        ),
        links = Map(
          "homeworld" -> Set("https://swapi.dev/api/planets/1/"),
          "species"   -> Set("https://swapi.dev/api/species/2/"),
          "vehicles"  -> Set.empty,
          "starships" -> Set.empty
        ),
        url = "https://swapi.dev/api/people/2/"
      )

      assertTrue(people == Right(expectedPerson))
    },
    test("a character carries only the attributes its universe records") {
      val wizard = Character(
        name = "Harry Potter",
        films = Set("/hp/films/1/"),
        portrayedBy = Set("/actors/1/"),
        attributes = Map("house" -> "Gryffindor", "patronus" -> "Stag"),
        links = Map("wand" -> Set("/hp/wands/1/")),
        url = "/hp/people/1/"
      )

      val encoded = encodeAs(wizard)

      assertTrue(
        encoded.contains("\"house\":\"Gryffindor\""),
        !encoded.contains("homeworld"),
        encoded.to[Character] == Right(wizard)
      )
    },
    test("an attribute the source did not measure is absent rather than empty") {
      val unmeasured = Character(
        name = "Arvel Crynyd",
        films = Set.empty,
        portrayedBy = Set.empty,
        attributes = Map("mass" -> "182"),
        links = Map.empty,
        url = "https://swapi.dev/api/people/62/"
      )

      val encoded = encodeAs(unmeasured)

      assertTrue(
        !encoded.contains("\"height\""),
        encoded.contains("\"mass\":\"182\""),
        encoded.to[Character].map(_.attributes.get("height")) == Right(None)
      )
    }
  )
