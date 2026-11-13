-- Reference data (CLIENT_DECISIONS section 1.4, round 5): Zambia's ten provinces and their MAIN towns, and the options a parcel can go by.
-- Reference data only: a delivery PRICE is never seeded (it is the client's own figure, entered and published by admin), and a wrong list
-- here is corrected by admin (add a town, switch one off) or by a later migration, never by rewriting this one. The owner approves the list
-- of towns (owner question Q6); ids are derived from the slug so they are the same in every environment.

INSERT INTO province (id, code, name, position) VALUES
    (md5('province:CENTRAL')::uuid,       'CENTRAL',       'Central',       1),
    (md5('province:COPPERBELT')::uuid,    'COPPERBELT',    'Copperbelt',    2),
    (md5('province:EASTERN')::uuid,       'EASTERN',       'Eastern',       3),
    (md5('province:LUAPULA')::uuid,       'LUAPULA',       'Luapula',       4),
    (md5('province:LUSAKA')::uuid,        'LUSAKA',        'Lusaka',        5),
    (md5('province:MUCHINGA')::uuid,      'MUCHINGA',      'Muchinga',      6),
    (md5('province:NORTHERN')::uuid,      'NORTHERN',      'Northern',      7),
    (md5('province:NORTH_WESTERN')::uuid, 'NORTH_WESTERN', 'North-Western', 8),
    (md5('province:SOUTHERN')::uuid,      'SOUTHERN',      'Southern',      9),
    (md5('province:WESTERN')::uuid,       'WESTERN',       'Western',      10);

INSERT INTO town (id, province_id, name, slug, created_by)
SELECT md5('town:' || lower(regexp_replace(t.name, '[^A-Za-z0-9]+', '-', 'g')))::uuid,
       md5('province:' || t.province)::uuid,
       t.name,
       lower(regexp_replace(t.name, '[^A-Za-z0-9]+', '-', 'g')),
       'system:seed'
  FROM (VALUES
    ('CENTRAL', 'Kabwe'), ('CENTRAL', 'Kapiri Mposhi'), ('CENTRAL', 'Mkushi'), ('CENTRAL', 'Serenje'),
    ('COPPERBELT', 'Ndola'), ('COPPERBELT', 'Kitwe'), ('COPPERBELT', 'Chingola'), ('COPPERBELT', 'Mufulira'), ('COPPERBELT', 'Luanshya'),
    ('COPPERBELT', 'Kalulushi'), ('COPPERBELT', 'Chililabombwe'),
    ('EASTERN', 'Chipata'), ('EASTERN', 'Petauke'), ('EASTERN', 'Katete'), ('EASTERN', 'Lundazi'),
    ('LUAPULA', 'Mansa'), ('LUAPULA', 'Samfya'), ('LUAPULA', 'Kawambwa'),
    ('LUSAKA', 'Lusaka'), ('LUSAKA', 'Kafue'), ('LUSAKA', 'Chilanga'), ('LUSAKA', 'Chongwe'),
    ('MUCHINGA', 'Chinsali'), ('MUCHINGA', 'Mpika'), ('MUCHINGA', 'Isoka'), ('MUCHINGA', 'Nakonde'),
    ('NORTHERN', 'Kasama'), ('NORTHERN', 'Mbala'), ('NORTHERN', 'Mporokoso'),
    ('NORTH_WESTERN', 'Solwezi'), ('NORTH_WESTERN', 'Mwinilunga'), ('NORTH_WESTERN', 'Zambezi'),
    ('SOUTHERN', 'Livingstone'), ('SOUTHERN', 'Choma'), ('SOUTHERN', 'Mazabuka'), ('SOUTHERN', 'Monze'), ('SOUTHERN', 'Kalomo'),
    ('WESTERN', 'Mongu'), ('WESTERN', 'Senanga'), ('WESTERN', 'Sesheke'), ('WESTERN', 'Kaoma')
  ) AS t (province, name);

-- The options. Self-collect is not an option row: it is always offered, at no charge, outside the town matrix. Bus-company parcel services are
-- added by admin as their own options (each company its own row). Whether an option is offered in a town is a published price, not a row here.
INSERT INTO delivery_option (code, name, kind, position, created_by) VALUES
    ('OWN_DELIVERY', 'Oriah delivery', 'OWN_DELIVERY', 1, 'system:seed'),
    ('YANGO',        'Yango',          'YANGO',        2, 'system:seed'),
    ('PLATINUM',     'Platinum Courier', 'COURIER',    3, 'system:seed'),
    ('EMS',          'EMS (Zampost)',  'COURIER',      4, 'system:seed');
