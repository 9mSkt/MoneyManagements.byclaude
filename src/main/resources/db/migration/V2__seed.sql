-- Starter categories (EN + TH), default budget rules and Thai PIT brackets. No personal data.

INSERT INTO category (code, name_en, name_th, type, budget_group, sort_order) VALUES
 ('SALARY',          'Salary',            'เงินเดือน',        'INCOME',  NULL,         10),
 ('INCENTIVE',       'Incentive',         'อินเซนทีฟ',        'INCOME',  NULL,         20),
 ('BONUS',           'Bonus',             'โบนัส',            'INCOME',  NULL,         30),
 ('RENT_INCOME',     'Rent income',       'รายได้ค่าเช่า',     'INCOME',  NULL,         40),
 ('PHONE_ALLOWANCE', 'Phone allowance',   'ค่าโทรศัพท์ (รับ)', 'INCOME',  NULL,         50),
 ('OTHER_INCOME',    'Other income',      'รายได้อื่น',        'INCOME',  NULL,         90),

 ('RENT',            'Rent',              'ค่าเช่าบ้าน',       'EXPENSE', 'FIXED',     110),
 ('CAR_PAYMENT',     'Car payment',       'ค่างวดรถ',         'EXPENSE', 'FIXED',     120),
 ('INSURANCE',       'Insurance',         'ประกัน',           'EXPENSE', 'FIXED',     130),
 ('ELECTRICITY',     'Electricity',       'ค่าไฟ',            'EXPENSE', 'FIXED',     140),
 ('WATER',           'Water',             'ค่าน้ำ',            'EXPENSE', 'FIXED',     150),
 ('INTERNET',        'Internet',          'ค่าอินเทอร์เน็ต',    'EXPENSE', 'FIXED',     160),
 ('PHONE_BILL',      'Phone bill',        'ค่าโทรศัพท์',       'EXPENSE', 'FIXED',     170),
 ('FAMILY',          'Mom',               'ให้แม่',            'EXPENSE', 'FIXED',     180),
 ('SOCIAL_SECURITY', 'Social security',   'ประกันสังคม',        'EXPENSE', 'FIXED',     182),
 ('PVD',             'Provident fund',    'กองทุนสำรองเลี้ยงชีพ', 'EXPENSE', 'FIXED',     184),
 ('MOM_DEBT',        'Mom debt',          'หนี้แม่',            'EXPENSE', 'FIXED',     186),
 ('INCOME_TAX',      'Income tax',        'ภาษีเงินได้',         'EXPENSE', 'FIXED',     188),
 ('OTHER_FIXED',     'Other fixed cost',  'ค่าใช้จ่ายคงที่อื่น', 'EXPENSE', 'FIXED',     190),

 ('GASOLINE',        'Gasoline',          'ค่าน้ำมัน',         'EXPENSE', 'GAS',       210),
 ('NECESSARY',       'Necessities',       'ของใช้จำเป็น',      'EXPENSE', 'NECESSARY', 310),
 ('HEALTH',          'Health',            'สุขภาพ',           'EXPENSE', 'NECESSARY', 320),
 ('SHOPPING',        'Shopping',          'ช้อปปิ้ง',          'EXPENSE', 'SHOPPING',  410),
 ('EAT_LIFESTYLE',   'Eat / Lifestyle',   'กิน / ไลฟ์สไตล์',     'EXPENSE', 'EAT',       510),

 ('DEPOSIT',         'Deposit',           'เงินฝาก',           'TRANSFER','SAVING',    610),
 ('INVESTMENT',      'Investment',        'การลงทุน',          'TRANSFER','INVESTMENT',710);

-- Actual budget (month tabs): Eat 40 / Shopping 40 / Gas 20 of income after fixed cost.
-- Goal budget (Summary Report): Deposit 10 / Investment 15 / Gas 15 / Necessary 15 / Shopping 15 / Eat 30.
INSERT INTO budget_rule (budget_month, kind, budget_group, percent) VALUES
 (DATE '2026-01-01', 'ACTUAL', 'EAT',        40.00),
 (DATE '2026-01-01', 'ACTUAL', 'SHOPPING',   40.00),
 (DATE '2026-01-01', 'ACTUAL', 'GAS',        20.00),
 (DATE '2026-01-01', 'GOAL',   'SAVING',     10.00),
 (DATE '2026-01-01', 'GOAL',   'INVESTMENT', 15.00),
 (DATE '2026-01-01', 'GOAL',   'GAS',        15.00),
 (DATE '2026-01-01', 'GOAL',   'NECESSARY',  15.00),
 (DATE '2026-01-01', 'GOAL',   'SHOPPING',   15.00),
 (DATE '2026-01-01', 'GOAL',   'EAT',        30.00);

-- Thai personal income tax brackets (net income, baht).
INSERT INTO tax_bracket (income_from, income_to, rate) VALUES
 (0,          150000,   0),
 (150000,     300000,   5),
 (300000,     500000,  10),
 (500000,     750000,  15),
 (750000,    1000000,  20),
 (1000000,   2000000,  25),
 (2000000,   5000000,  30),
 (5000000,      NULL,  35);
