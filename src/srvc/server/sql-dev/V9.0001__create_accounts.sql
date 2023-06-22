insert into account (buddy_hash, email, level, username, verified) values (
  -- tpwd
  'bcrypt+sha512$45ed7743bc2d5bb47f70403b7fc3b3d5$12$79e4147f05c7ab6574bce3dee4f2fbc372f9f4e9a6dc6aba',
  'tester@insilica.co',
  'user',
  'tester',
  now()
), (
  -- spwd
  'bcrypt+sha512$1aa42f747808f37151d51ab9aa36d849$12$371f4d44c31d6d487e07f967b40444433e89303e3ddd1170',
  'su@insilica.co',
  'superadmin',
  'su',
  now()
);

insert into api_key (account, secret_key) values (
  (select id from account where username = 'tester'),
  'CNfHpzOYFWJgtUS/YOEoEs03f1ueiiac9r2i6LhDdz3r'
), (
  (select id from account where username = 'su'),
  '9HimCb7l3b1mK8cqDdMsnDOrUipLfFc9JzNfOZeVUgtV'
);

insert into api_key_scope (api_key, level, scope) values (
  (select id from api_key where account = (select id from account where username = 'tester')),
  'write',
  'root'
), (
  (select id from api_key where account = (select id from account where username = 'su')),
  'write',
  'root'
);
