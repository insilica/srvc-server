create table api_key (
  id serial primary key,
  account int not null references account (id),
  created timestamp with time zone not null default now(),
  expires timestamp with time zone,
  secret_key text not null
);

create index api_key_account on api_key (account);
create index api_key_secret_key on api_key (secret_key);

create table api_key_scope_level (
  id text primary key
);

insert into api_key_scope_level (id) values ('read'), ('write');

create table api_key_scope (
  id serial primary key,
  api_key int references api_key (id),
  created timestamp with time zone not null default now(),
  level text references api_key_scope_level (id),
  scope text not null
);

create index api_key_scope_api_key_idx on api_key_scope (api_key);
