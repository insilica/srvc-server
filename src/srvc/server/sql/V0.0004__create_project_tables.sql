create table project (
  id serial primary key,
  account int not null references account (id),
  created timestamp with time zone not null default now(),
  name text not null,
  public boolean not null default false
);

create index project_account_idx on project (account);
create index project_account_and_name_idx on project (account, lower(name));

alter table project add constraint project_name_valid
    check (char_length(name) <= 40
           AND name ~ '^([A-Za-z0-9]+-)*[A-Za-z0-9]+$');

create table project_account_level (
  id text primary key
);

insert into project_account_level values ('member'), ('admin');

create table project_account (
  account int not null references account (id),
  level text not null references project_account_level (id) default 'member',
  project int not null references project (id),
  primary key (account, project)
);
