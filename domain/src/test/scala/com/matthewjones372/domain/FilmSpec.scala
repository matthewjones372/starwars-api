package com.matthewjones372.domain

import zio.test.*
import scala.io.Source
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

object FilmSpec extends ZIOSpecDefault:
  def spec = suite("Films Spec")(
    test("Films should be able to be decoded from JSON") {
      val json = Source.fromResource("film1_json.json").getLines().mkString
      val film = json.mkString.to[Film]

      val expectedFilm =
        Film(
          title = "The Empire Strikes Back",
          episodeId = 5,
          openingCrawl = "opening",
          director = "Irvin Kershner",
          producer = "Gary Kurtz, Rick McCallum",
          releaseDate = "1980-05-17",
          characters = Set(
          ),
          planets = Set(
            "https://swapi.dev/api/planets/4/",
            "https://swapi.dev/api/planets/5/",
            "https://swapi.dev/api/planets/6/",
            "https://swapi.dev/api/planets/27/"
          ),
          starships = Set(
            "https://swapi.dev/api/starships/11/",
            "https://swapi.dev/api/starships/22/",
            "https://swapi.dev/api/starships/15/",
            "https://swapi.dev/api/starships/10/",
            "https://swapi.dev/api/starships/3/",
            "https://swapi.dev/api/starships/23/",
            "https://swapi.dev/api/starships/12/",
            "https://swapi.dev/api/starships/21/",
            "https://swapi.dev/api/starships/17/"
          ),
          vehicles = Set(
            "https://swapi.dev/api/vehicles/16/",
            "https://swapi.dev/api/vehicles/14/",
            "https://swapi.dev/api/vehicles/19/",
            "https://swapi.dev/api/vehicles/18/",
            "https://swapi.dev/api/vehicles/20/",
            "https://swapi.dev/api/vehicles/8/"
          ),
          species = Set(
            "https://swapi.dev/api/species/6/",
            "https://swapi.dev/api/species/3/",
            "https://swapi.dev/api/species/7/",
            "https://swapi.dev/api/species/1/",
            "https://swapi.dev/api/species/2/"
          ),
          created = "2014-12-12T11:26:24.656000Z",
          edited = "2014-12-15T13:07:53.386000Z",
          url = "https://swapi.dev/api/films/2/"
        )

      assertTrue(film == Right(expectedFilm))
    }
  )
