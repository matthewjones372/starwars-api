create table people_actors (
  person_id integer not null references people (id) on delete cascade,
  actor_url text    not null,
  primary key (person_id, actor_url)
);

create table film_cast (
  film_id   integer not null references films (id) on delete cascade,
  actor_url text    not null,
  primary key (film_id, actor_url)
);
