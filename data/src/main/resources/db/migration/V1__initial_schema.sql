create table films (
  id            integer primary key,
  title         text    not null,
  episode_id    integer not null,
  opening_crawl text    not null,
  director      text    not null,
  producer      text    not null,
  release_date  text    not null,
  created       text    not null,
  edited        text    not null,
  url           text    not null
);

create table people (
  id         integer primary key,
  name       text    not null,
  height     integer,
  mass       integer,
  hair_color text    not null,
  skin_color text    not null,
  eye_color  text    not null,
  birth_year text    not null,
  gender     text,
  homeworld  text,
  url        text    not null
);

create table people_films (
  person_id integer not null references people (id) on delete cascade,
  film_url  text    not null,
  primary key (person_id, film_url)
);

create table people_species (
  person_id   integer not null references people (id) on delete cascade,
  species_url text    not null,
  primary key (person_id, species_url)
);

create table people_vehicles (
  person_id   integer not null references people (id) on delete cascade,
  vehicle_url text    not null,
  primary key (person_id, vehicle_url)
);

create table people_starships (
  person_id    integer not null references people (id) on delete cascade,
  starship_url text    not null,
  primary key (person_id, starship_url)
);

create table film_characters (
  film_id       integer not null references films (id) on delete cascade,
  character_url text    not null,
  primary key (film_id, character_url)
);

create table film_planets (
  film_id     integer not null references films (id) on delete cascade,
  planet_url  text    not null,
  primary key (film_id, planet_url)
);

create table film_starships (
  film_id      integer not null references films (id) on delete cascade,
  starship_url text    not null,
  primary key (film_id, starship_url)
);

create table film_vehicles (
  film_id     integer not null references films (id) on delete cascade,
  vehicle_url text    not null,
  primary key (film_id, vehicle_url)
);

create table film_species (
  film_id     integer not null references films (id) on delete cascade,
  species_url text    not null,
  primary key (film_id, species_url)
);

create index people_films_film_url_idx on people_films (film_url);
create index people_name_idx on people (name);
