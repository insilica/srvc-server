create table account_level (
  id text primary key
);

insert into account_level values ('user'), ('admin'), ('superadmin');

create table account (
  id serial primary key,
  buddy_hash text not null,
  created timestamp with time zone not null default now(),
  email text not null,
  level text not null references account_level (id) default 'user',
  password_reset_code text,
  password_reset_code_expires timestamp with time zone,
  username text not null,
  verification_code text not null,
  verified timestamp with time zone
);

create unique index account_email_idx on account (lower(email));
create unique index account_username_idx on account (lower(username));
create index account_verification_code_idx on account (verification_code);

alter table account add constraint account_username_valid
    check (char_length(username) <= 40
           AND username ~ '^([A-Za-z0-9]+-)*[A-Za-z0-9]+$');
