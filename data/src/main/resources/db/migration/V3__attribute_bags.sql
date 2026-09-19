create table character_attributes (
  person_id integer not null references people (id) on delete cascade,
  key       text    not null,
  value     text    not null,
  primary key (person_id, key)
);

insert into character_attributes (person_id, key, value)
  select id, 'height', cast(height as text) from people where height is not null
  union all select id, 'mass', cast(mass as text) from people where mass is not null
  union all select id, 'hair_color', hair_color from people
  union all select id, 'skin_color', skin_color from people
  union all select id, 'eye_color', eye_color from people
  union all select id, 'birth_year', birth_year from people
  union all select id, 'gender', gender from people where gender is not null;

create table character_links (
  person_id integer not null references people (id) on delete cascade,
  rel       text    not null,
  url       text    not null,
  primary key (person_id, rel, url)
);

insert into character_links (person_id, rel, url)
  select id, 'homeworld', homeworld from people where homeworld is not null
  union all select person_id, 'species', species_url from people_species
  union all select person_id, 'vehicles', vehicle_url from people_vehicles
  union all select person_id, 'starships', starship_url from people_starships;

drop table people_species;
drop table people_vehicles;
drop table people_starships;

alter table people drop column height;
alter table people drop column mass;
alter table people drop column hair_color;
alter table people drop column skin_color;
alter table people drop column eye_color;
alter table people drop column birth_year;
alter table people drop column gender;
alter table people drop column homeworld;

create table film_attributes (
  film_id integer not null references films (id) on delete cascade,
  key     text    not null,
  value   text    not null,
  primary key (film_id, key)
);

insert into film_attributes (film_id, key, value)
  select id, 'opening_crawl', opening_crawl from films
  union all select id, 'created', created from films
  union all select id, 'edited', edited from films;

create table film_links (
  film_id integer not null references films (id) on delete cascade,
  rel     text    not null,
  url     text    not null,
  primary key (film_id, rel, url)
);

insert into film_links (film_id, rel, url)
  select film_id, 'planets', planet_url from film_planets
  union all select film_id, 'starships', starship_url from film_starships
  union all select film_id, 'vehicles', vehicle_url from film_vehicles
  union all select film_id, 'species', species_url from film_species;

drop table film_planets;
drop table film_starships;
drop table film_vehicles;
drop table film_species;

alter table films drop column opening_crawl;
alter table films drop column created;
alter table films drop column edited;

create index character_attributes_key_idx on character_attributes (key);
create index character_links_rel_idx on character_links (rel);
