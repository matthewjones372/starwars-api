package scripts

import com.matthewjones372.domain.*
import zio.*
import zio.http.*
import zio.schema.*
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

import java.nio.file.{Files, Paths}

/**
 * Builds the bundled datasets from Wikidata.
 *
 * One query shape serves every franchise, and the same pass that finds a film's
 * cast finds the character each of them played, which is what makes an actor
 * graph spanning four universes possible at all. Run it by hand; what ships is
 * the JSON it writes.
 */
object GenerateUniverseData extends ZIOAppDefault:

  private final case class Cell(value: String) derives Schema
  private final case class Results(bindings: List[Map[String, Cell]]) derives Schema
  private final case class SparqlResponse(results: Results) derives Schema

  private final case class Source(
    universe: UniverseId,
    series: String,
    extra: List[String] = Nil,
    scraped: Boolean = true
  ):
    // The Star Wars live-action series are not part of any series item, so they
    // cannot be reached through P179 and have to be named one by one.
    def films: String =
      val bySeries = s"{ ?film wdt:P179 wd:$series }"
      if extra.isEmpty then bySeries
      else s"""{ $bySeries UNION { VALUES ?film { ${extra.map("wd:" + _).mkString(" ")} } } }"""

  // Star Wars keeps its swapi data, which records heights and homeworlds that
  // Wikidata does not. Only its cast is taken from here.
  private val starWarsSeries = List(
    "Q56876444",  // The Mandalorian
    "Q60462338",  // Andor
    "Q104153367", // Ahsoka
    "Q104154217", // Obi-Wan Kenobi
    "Q104161310", // The Acolyte
    "Q104396610", // The Book of Boba Fett
    "Q113275091", // Star Wars: Skeleton Crew
    "Q19590955",  // Rogue One: A Star Wars Story
    "Q27038847",  // Solo: A Star Wars Story
    "Q124246549"  // The Mandalorian and Grogu
  )

  private val sources = List(
    Source(UniverseId.StarWars, "Q22092344", extra = starWarsSeries, scraped = false),
    Source(UniverseId.Marvel, "Q642878"),
    Source(UniverseId.MiddleEarth, "Q190214"),
    Source(UniverseId.Wizarding, "Q216930")
  )

  private val endpoint  = "https://query.wikidata.org/sparql"
  private val resources = Paths.get("data/src/main/resources")

  // Wikidata types nearly every fictional person as a film, literary and
  // theatrical character. True, and not what anyone means by species.
  private val noise =
    Set("film character", "literary character", "theatrical character", "television character", "human")

  private def ask(query: String): RIO[Client & Scope, List[Map[String, String]]] =
    val request =
      Request
        .get(URL.decode(endpoint).toOption.get.addQueryParam("query", query))
        .addHeader(Header.Accept.name, "application/sparql-results+json")
        .addHeader(Header.UserAgent.name, "filmverse-data/0.1 (https://github.com/matthewjones372)")

    val call: RIO[Client & Scope, List[Map[String, String]]] =
      for
        client   <- ZIO.service[Client]
        response <- client.batched(request)
        body     <- response.body.asString
        _        <- ZIO
               .fail(new RuntimeException(s"${response.status}: ${body.take(200)}"))
               .when(!response.status.isSuccess)
        parsed <- ZIO.fromEither(body.to[SparqlResponse]).mapError(error => new RuntimeException(error.toString))
      yield parsed.results.bindings.map(_.view.mapValues(_.value).toMap)

    // The public endpoint throttles and times out under load, and a query that
    // fails once usually succeeds a minute later.
    call.retry(Schedule.exponential(5.seconds) && Schedule.recurs(4))

  private def qid(uri: String): String = uri.split("/").last

  private def universeOf(url: String): Option[UniverseId] =
    url.split("/").filter(_.nonEmpty).headOption.flatMap(slug => UniverseId.from(slug).toOption)

  private def splitList(value: String): List[String] =
    value.split("; ").toList.map(_.trim).filter(_.nonEmpty).distinct

  private def filmsQuery(films: String) =
    s"""SELECT ?film (SAMPLE(?label) AS ?title) (MIN(?date) AS ?released)
       |       (GROUP_CONCAT(DISTINCT ?dirName; separator="; ") AS ?directors)
       |       (GROUP_CONCAT(DISTINCT ?prodName; separator="; ") AS ?producers)
       |       (SAMPLE(?kind) AS ?type) WHERE {
       |  $films
       |  ?film rdfs:label ?label . FILTER(LANG(?label) = "en")
       |  OPTIONAL { ?film wdt:P577 ?date }
       |  OPTIONAL { ?film wdt:P57 ?dir . ?dir rdfs:label ?dirName . FILTER(LANG(?dirName)="en") }
       |  OPTIONAL { ?film wdt:P162 ?prod . ?prod rdfs:label ?prodName . FILTER(LANG(?prodName)="en") }
       |  OPTIONAL { ?film wdt:P31 ?kind . VALUES ?kind { wd:Q11424 wd:Q5398426 } }
       |}
       |GROUP BY ?film ORDER BY ?released""".stripMargin

  private def castQuery(films: String) =
    s"""SELECT ?film ?actor ?actorLabel ?character ?characterLabel WHERE {
       |  $films
       |  ?film p:P161 ?st .
       |  ?st ps:P161 ?actor .
       |  OPTIONAL { ?st pq:P453 ?character }
       |  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
       |}""".stripMargin

  private def charactersQuery(films: String) =
    s"""SELECT ?character (SAMPLE(?label) AS ?name)
       |       (GROUP_CONCAT(DISTINCT ?spName; separator="; ") AS ?species)
       |       (GROUP_CONCAT(DISTINCT ?gName; separator="; ") AS ?gender)
       |       (GROUP_CONCAT(DISTINCT ?aName; separator="; ") AS ?affiliation) WHERE {
       |  $films
       |  ?film p:P161/pq:P453 ?character .
       |  ?character rdfs:label ?label . FILTER(LANG(?label)="en")
       |  OPTIONAL { ?character wdt:P31 ?sp . ?sp rdfs:label ?spName . FILTER(LANG(?spName)="en") }
       |  OPTIONAL { ?character wdt:P21 ?g . ?g rdfs:label ?gName . FILTER(LANG(?gName)="en") }
       |  OPTIONAL { ?character wdt:P463 ?a . ?a rdfs:label ?aName . FILTER(LANG(?aName)="en") }
       |}
       |GROUP BY ?character""".stripMargin

  private def actorsQuery(films: String) =
    s"""SELECT ?actor (SAMPLE(?label) AS ?name) (SAMPLE(?born) AS ?birth)
       |       (GROUP_CONCAT(DISTINCT ?cName; separator="; ") AS ?citizenship) WHERE {
       |  $films
       |  ?film wdt:P161 ?actor .
       |  ?actor rdfs:label ?label . FILTER(LANG(?label)="en")
       |  OPTIONAL { ?actor wdt:P569 ?born }
       |  OPTIONAL { ?actor wdt:P27 ?c . ?c rdfs:label ?cName . FILTER(LANG(?cName)="en") }
       |}
       |GROUP BY ?actor""".stripMargin

  private final case class Raw(
    universe: UniverseId,
    films: List[Map[String, String]],
    cast: List[Map[String, String]],
    characters: List[Map[String, String]],
    actors: List[Map[String, String]]
  )

  private def fetch(source: Source): RIO[Client & Scope, Raw] =
    for
      _          <- ZIO.logInfo(s"Fetching ${source.universe.label}")
      films      <- ask(filmsQuery(source.films))
      cast       <- ask(castQuery(source.films))
      characters <- if source.scraped then ask(charactersQuery(source.films)) else ZIO.succeed(Nil)
      actors     <- ask(actorsQuery(source.films))
      _          <- ZIO.logInfo(
             s"  ${films.size} titles, ${cast.size} cast rows, ${characters.size} characters, ${actors.size} actors"
           )
    yield Raw(source.universe, films, cast, characters, actors)

  private def run(raws: List[Raw]): Task[Unit] =
    // Actors are global, so their ids are assigned once across every universe.
    // Keyed off the cast rows rather than the actor query, because that query
    // asks for an English label and a few performers have none. They are still
    // in a film's cast, so they still need an id and a url.
    val actorRows           = raws.flatMap(_.actors).groupBy(row => qid(row("actor"))).view.mapValues(_.head).toMap
    val credited            = raws.flatMap(_.cast).map(row => qid(row("actor"))).distinct
    val actorIds            = (credited ++ actorRows.keys).distinct.sorted.zipWithIndex.map((q, i) => q -> (i + 1)).toMap
    def actorUrl(q: String) = s"/actors/${actorIds(q)}/"

    for
      assembled <- ZIO.foreach(raws)(assemble(_, actorUrl))
      // Star Wars keeps swapi's entities, which are numbered differently from
      // Wikidata's. Both sides spell a url the same way, so an actor's credits
      // have to be translated onto the entities actually served or they point
      // at whatever happens to hold that number.
      merged <- ZIO.foreach(assembled)((universe, entities, _) => merge(universe, entities))
      remap   = merged.flatMap(_.remap).toMap
      served  = merged.flatMap(m => m.characters.map(_.url) ++ m.films.map(_.url)).toSet
      // A role names the film it was played in, and should name it the way the
      // dataset does rather than the way the source did.
      titleOf = merged.flatMap(_.films).map(film => film.url -> film.title).toMap
      nameOf  = merged.flatMap(_.characters).map(person => person.url -> person.name).toMap
      at      = (url: String) => remap.getOrElse(url, url)
      // In a translated universe the remap is the only authority. A url it does
      // not carry is not merely unknown -- the same number is in use over there
      // for somebody else, so letting it through silently renames the role.
      translated = merged.filter(_.translated).map(_.universe.slug).toSet
      slugOf     = (url: String) => url.split("/").filter(_.nonEmpty).headOption.getOrElse("")
      known      = (url: String) => if translated.contains(slugOf(url)) then remap.contains(url) else served.contains(url)
      byActor    = assembled
                  .flatMap(_._3._1)
                  .filter((_, role) => known(role.character) && known(role.film))
                  .map { (q, role) =>
                    val film      = at(role.film)
                    val character = at(role.character)
                    q -> role.copy(
                      character = character,
                      characterName = nameOf.getOrElse(character, role.characterName),
                      film = film,
                      filmTitle = titleOf.getOrElse(film, role.filmTitle)
                    )
                  }
                  .groupBy(_._1)
      // Every film an actor was credited in, not only the ones where Wikidata
      // also recorded which character they played. The actor graph is built on
      // this, and an actor with no named role is still in a film's cast.
      creditsBy = assembled.flatMap(_._3._2).filter((_, film) => known(film)).groupMap(_._1)(t => at(t._2))
      labelsBy  = assembled.flatMap(_._3._3).toMap
      actors    = actorIds.toList.sortBy(_._2).map { (q, _) =>
                 val row     = actorRows.getOrElse(q, Map.empty)
                 val roles   = byActor.getOrElse(q, Nil).map(_._2).distinct.sorted
                 val credits = creditsBy.getOrElse(q, Nil).toSet
                 Actor(
                   name = row.get("name").orElse(labelsBy.get(q)).getOrElse(q),
                   films = credits,
                   // From the films they were credited in rather than from the
                   // roles: Wikidata calls him Count Dooku and swapi calls him
                   // Dooku, so the role does not survive the name match, but
                   // Christopher Lee is in Star Wars either way.
                   universes = credits.flatMap(universeOf),
                   roles = roles,
                   attributes = Map(
                     "wikidata"    -> q,
                     "birth_date"  -> row.getOrElse("birth", "").take(10),
                     "citizenship" -> row.getOrElse("citizenship", "")
                   ).filter(_._2.nonEmpty),
                   url = actorUrl(q)
                 )
               }
      // An actor the actors query found but no film in these datasets credits is
      // an isolated node and nothing links to them, so they are not shipped.
      credited = actors.filter(_.films.nonEmpty)
      _       <- ZIO.foreachDiscard(merged)(write)
      _       <- writeFile("actors.json", encodeAs(credited))
      _       <- ZIO.logInfo(
             s"Wrote ${credited.size} actors, ${credited.count(_.universes.size > 1)} of them in more than one universe"
           )
    yield ()

  private final case class Merged(
    universe: UniverseId,
    characters: List[Character],
    films: List[Film],
    remap: Map[String, String],
    translated: Boolean
  )

  /**
   * The entities to ship for a universe, and how to say its urls.
   *
   * Only Star Wars merges: its swapi entities stay, gaining the cast Wikidata
   * knows, and [remap] carries every Wikidata url onto the swapi one it means.
   * The other three are built from Wikidata throughout, so their urls already
   * agree and the remap is empty.
   */
  private def merge(universe: UniverseId, entities: (List[Character], List[Film])): Task[Merged] =
    val (characters, films) = entities
    if universe != UniverseId.StarWars then ZIO.succeed(Merged(universe, characters, films, Map.empty, false))
    else
      readEntities(universe).map { (existingPeople, existingFilms) =>
        val byName = characters.map(person => person.name.toLowerCase -> person).toMap
        val people = existingPeople.map { person =>
          byName.get(person.name.toLowerCase).fold(person)(scraped => person.copy(portrayedBy = scraped.portrayedBy))
        }
        val castByFilm = castByTitle(films)
        val withCast   = existingFilms.map { film =>
          film.copy(cast = titleKeys(film.title).flatMap(castByFilm.getOrElse(_, Set.empty)))
        }

        val filmRemap =
          for
            scraped  <- films
            existing <- existingFilms.find(e => titleKeys(e.title).intersect(titleKeys(scraped.title)).nonEmpty)
          yield scraped.url -> existing.url

        val existingByName = existingPeople.flatMap(person => nameKeys(person.name).map(_ -> person)).toMap

        val characterRemap =
          for
            scraped  <- characters
            existing <- nameKeys(scraped.name).flatMap(existingByName.get).headOption
          yield scraped.url -> existing.url

        Merged(universe, people, withCast, (filmRemap ++ characterRemap).toMap, translated = true)
      }

  private def assemble(raw: Raw, actorUrl: String => String) =
    val slug = raw.universe.slug

    val filmIds            = raw.films.map(row => qid(row("film"))).zipWithIndex.map((q, i) => q -> (i + 1)).toMap
    def filmUrl(q: String) = s"/$slug/films/${filmIds(q)}/"
    val titles             = raw.films.map(row => qid(row("film")) -> row.getOrElse("title", "")).toMap

    val castRows = raw.cast.filter(row => filmIds.contains(qid(row("film"))))

    val characterIds =
      castRows
        .flatMap(row => row.get("character").map(qid))
        .distinct
        .sorted
        .zipWithIndex
        .map((q, i) => q -> (i + 1))
        .toMap
    def characterUrl(q: String) = s"/$slug/people/${characterIds(q)}/"

    val roles = castRows.flatMap { row =>
      val actorQ = qid(row("actor"))
      row.get("character").map(qid).filter(characterIds.contains).map { characterQ =>
        val filmQ = qid(row("film"))
        actorQ -> Role(
          universe = raw.universe,
          character = characterUrl(characterQ),
          characterName = row.getOrElse("characterLabel", characterQ),
          film = filmUrl(filmQ),
          filmTitle = titles.getOrElse(filmQ, "")
        )
      }
    }

    val castByFilm        = castRows.groupMap(row => qid(row("film")))(row => actorUrl(qid(row("actor"))))
    val actorsByCharacter =
      castRows.flatMap(row => row.get("character").map(c => qid(c) -> actorUrl(qid(row("actor"))))).groupMap(_._1)(_._2)
    val filmsByCharacter =
      castRows.flatMap(row => row.get("character").map(c => qid(c) -> filmUrl(qid(row("film"))))).groupMap(_._1)(_._2)
    val charactersByFilm = castRows
      .flatMap(row => row.get("character").map(c => qid(row("film")) -> characterUrl(qid(c))))
      .groupMap(_._1)(_._2)

    val films = raw.films.map { row =>
      val q = qid(row("film"))
      Film(
        title = titles.getOrElse(q, q),
        episodeId = filmIds(q),
        director = row.getOrElse("directors", ""),
        producer = row.getOrElse("producers", ""),
        releaseDate = row.getOrElse("released", "").take(10),
        characters = charactersByFilm.getOrElse(q, Nil).toSet,
        cast = castByFilm.getOrElse(q, Nil).toSet,
        attributes = Map("wikidata" -> q).filter(_._2.nonEmpty),
        links = Map.empty,
        url = filmUrl(q),
        mediaType = Some(if row.get("type").map(qid).contains("Q5398426") then MediaKind.Series else MediaKind.Film)
      )
    }

    val attributesByCharacter = raw.characters.map(row => qid(row("character")) -> row).toMap

    val characters = characterIds.toList.sortBy(_._2).map { (q, _) =>
      val row   = attributesByCharacter.getOrElse(q, Map.empty)
      val named = castRows.find(_.get("character").map(qid).contains(q)).flatMap(_.get("characterLabel"))
      Character(
        name = row.getOrElse("name", named.getOrElse(q)),
        films = filmsByCharacter.getOrElse(q, Nil).toSet,
        portrayedBy = actorsByCharacter.getOrElse(q, Nil).toSet,
        attributes = Map(
          "wikidata"    -> q,
          "species"     -> splitList(row.getOrElse("species", "")).filterNot(noise).mkString(", "),
          "gender"      -> row.getOrElse("gender", ""),
          "affiliation" -> splitList(row.getOrElse("affiliation", "")).mkString(", ")
        ).filter(_._2.nonEmpty),
        links = Map.empty,
        url = characterUrl(q)
      )
    }

    val credits = castRows.map(row => qid(row("actor")) -> filmUrl(qid(row("film"))))
    val names   = castRows.flatMap(row => row.get("actorLabel").map(qid(row("actor")) -> _))

    ZIO.succeed((raw.universe, (characters, films), (roles, credits, names)))

  private def write(merged: Merged) =
    writeFile(s"${merged.universe.slug}_people.json", encodeAs(merged.characters)) *>
      writeFile(s"${merged.universe.slug}_films.json", encodeAs(merged.films)) *>
      ZIO
        .logInfo(
          s"  ${merged.universe.label}: ${merged.characters.count(_.portrayedBy.nonEmpty)} of " +
            s"${merged.characters.size} characters have an actor, " +
            s"${merged.films.count(_.cast.nonEmpty)} of ${merged.films.size} titles have a cast"
        )

  /**
   * The ways a title can be written, so a swapi one and a Wikidata one can
   * meet.
   *
   * swapi calls them "Return of the Jedi", "Rogue One" and "Skeleton Crew";
   * Wikidata calls the same three "Star Wars: Episode VI - Return of the Jedi",
   * "Rogue One: A Star Wars Story" and "Star Wars: Skeleton Crew". The real
   * title is a different part of the string each time, so both sides offer
   * every part and a shared one is a match.
   */
  private val genericTitleParts = Set("star wars", "a star wars story", "")

  private def titleKeys(title: String): Set[String] =
    val normalise = (part: String) => part.toLowerCase.replaceAll("[^a-z0-9 ]", " ").replaceAll(" +", " ").trim
    (title +: title.split("[-\u2013\u2014:]").toList)
      .map(normalise)
      .toSet
      .diff(genericTitleParts)

  // swapi drops the rank or title Wikidata keeps: Dooku against Count Dooku.
  private val honorifics =
    Set("count", "general", "admiral", "captain", "chancellor", "senator", "queen", "king", "lord", "master")

  private def nameKeys(name: String): Set[String] =
    val normalised = name.toLowerCase.replaceAll("[^a-z0-9 ]", " ").replaceAll(" +", " ").trim
    val words      = normalised.split(" ").toList
    Set(normalised) ++ Option.when(words.sizeIs > 1 && honorifics(words.head))(words.tail.mkString(" "))

  private def castByTitle(films: List[Film]): Map[String, Set[String]] =
    films.flatMap(film => titleKeys(film.title).map(_ -> film.cast)).toMap

  private def readEntities(universe: UniverseId): Task[(List[Character], List[Film])] =
    for
      peopleJson <- ZIO.attemptBlocking(Files.readString(resources.resolve(s"${universe.slug}_people.json")))
      filmJson   <- ZIO.attemptBlocking(Files.readString(resources.resolve(s"${universe.slug}_films.json")))
      people     <- ZIO.fromEither(peopleJson.to[List[Character]]).mapError(e => new RuntimeException(e.toString))
      films      <- ZIO.fromEither(filmJson.to[List[Film]]).mapError(e => new RuntimeException(e.toString))
    yield (people, films)

  private def writeFile(name: String, contents: String) =
    ZIO.attemptBlocking(Files.writeString(resources.resolve(name), contents)).unit

  def run =
    ZIO
      .foreach(sources)(fetch)
      .flatMap(run)
      .provide(Client.default, Scope.default)
