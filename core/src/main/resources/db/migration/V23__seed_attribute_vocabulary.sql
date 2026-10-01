-- Seeds the field vocabulary drawn from the infobox and listing mockups, plus the kind labels,
-- in fa/en/ar. Farsi is canonical. Everything here is an ordinary admin-editable row from then
-- on: none of it is dev-marked, so the dev fixture routes and sweepers never touch it.
--
-- Rows are joined by key rather than by hard-coded ids, so a typo in a key drops the row
-- instead of failing. The check at the end turns that into a loud failure.

-- Groups ---------------------------------------------------------------------------------------

-- Species and places never share a group, so each set only needs its own relative order.
INSERT INTO attribute_groups (key, sort) VALUES
    ('classification', 10),
    ('conservation', 20),
    ('ecology', 30),
    ('morphology', 40),
    ('geography', 50),
    ('administrative_divisions', 60),
    ('demography', 70);

INSERT INTO labels (group_id, language, value)
SELECT g.id, v.language, v.value
FROM (VALUES
    ('classification', 'fa', 'رده‌بندی'),
    ('classification', 'en', 'Classification'),
    ('classification', 'ar', 'التصنيف العلمي'),
    ('conservation', 'fa', 'حفاظت'),
    ('conservation', 'en', 'Conservation'),
    ('conservation', 'ar', 'الحفظ'),
    ('ecology', 'fa', 'بوم‌شناسی'),
    ('ecology', 'en', 'Ecology'),
    ('ecology', 'ar', 'البيئة'),
    ('morphology', 'fa', 'ریخت‌شناسی'),
    ('morphology', 'en', 'Morphology'),
    ('morphology', 'ar', 'الصفات الشكلية'),
    ('geography', 'fa', 'جغرافیا'),
    ('geography', 'en', 'Geography'),
    ('geography', 'ar', 'الجغرافيا'),
    ('administrative_divisions', 'fa', 'تقسیمات کشوری'),
    ('administrative_divisions', 'en', 'Administrative divisions'),
    ('administrative_divisions', 'ar', 'التقسيمات الإدارية'),
    ('demography', 'fa', 'جمعیت‌شناسی'),
    ('demography', 'en', 'Demographics'),
    ('demography', 'ar', 'السكان')
) AS v (key, language, value)
JOIN attribute_groups g ON g.key = v.key;

-- Definitions ----------------------------------------------------------------------------------

-- `country` is multi: the Strait of Hormuz lies between Iran and Oman, and an offshore field
-- can straddle a border.
--
-- species_features and place_features, not one shared `features`: keys are unique, and options
-- belong to a definition rather than to a kind, so a shared field would let a species be tagged
-- FREE_ZONE and would list it, at count 0, in the species filter panel. Both carry the same
-- labels, so the split is invisible to readers.
INSERT INTO attribute_definitions
    (key, value_type, unit, group_id, sort, is_multi, is_required, is_infobox, is_filterable, ref_kind, min_value)
SELECT v.key, v.value_type, v.unit, g.id, v.sort, v.is_multi, v.is_required, true, v.is_filterable, v.ref_kind,
       v.min_value
FROM (VALUES
    ('scientific_name', 'PLAIN_TEXT', NULL::VARCHAR, 'classification', 10, false, true, false, NULL::VARCHAR, NULL::NUMERIC),
    ('category', 'ENUM', NULL, 'classification', 20, false, false, true, NULL, NULL),
    ('iucn_status', 'ENUM', NULL, 'conservation', 10, false, false, true, NULL, NULL),
    ('habitat', 'ENUM', NULL, 'ecology', 10, true, false, true, NULL, NULL),
    ('species_features', 'ENUM', NULL, 'ecology', 20, true, false, true, NULL, NULL),
    ('max_length', 'NUMBER', 'cm', 'morphology', 10, false, false, false, NULL, 0),
    ('located_in', 'SUBJECT_REF', NULL, 'geography', 10, true, false, false, 'STRAIT', NULL),
    ('area', 'NUMBER', 'km2', 'geography', 20, false, false, false, NULL, 0),
    ('place_features', 'ENUM', NULL, 'geography', 30, true, false, true, NULL, NULL),
    ('country', 'ENUM', NULL, 'administrative_divisions', 10, true, false, true, NULL, NULL),
    ('province', 'SUBJECT_REF', NULL, 'administrative_divisions', 20, false, false, true, 'PROVINCE', NULL),
    ('county', 'LOCALIZED_TEXT', NULL, 'administrative_divisions', 30, true, false, false, NULL, NULL),
    ('district', 'LOCALIZED_TEXT', NULL, 'administrative_divisions', 40, true, false, false, NULL, NULL),
    ('population', 'NUMBER', NULL, 'demography', 10, false, false, false, NULL, 0),
    ('ethnic_groups', 'LOCALIZED_TEXT', NULL, 'demography', 20, false, false, false, NULL, NULL),
    ('languages', 'ENUM', NULL, 'demography', 30, true, false, false, NULL, NULL)
) AS v (key, value_type, unit, group_key, sort, is_multi, is_required, is_filterable, ref_kind, min_value)
JOIN attribute_groups g ON g.key = v.group_key;

INSERT INTO attribute_definition_kinds (attribute_id, kind)
SELECT d.id, v.kind
FROM (VALUES
    ('scientific_name', 'SPECIES'),
    ('category', 'SPECIES'),
    ('iucn_status', 'SPECIES'),
    ('habitat', 'SPECIES'),
    ('species_features', 'SPECIES'),
    ('max_length', 'SPECIES'),
    -- A strait isn't located in a strait.
    ('located_in', 'ISLAND'),
    ('located_in', 'PORT'),
    ('located_in', 'FISHING_GROUND'),
    ('located_in', 'OIL_FIELD'),
    ('area', 'ISLAND'),
    ('area', 'FISHING_GROUND'),
    ('area', 'OIL_FIELD'),
    ('place_features', 'ISLAND'),
    ('place_features', 'PORT'),
    ('place_features', 'FISHING_GROUND'),
    ('country', 'ISLAND'),
    ('country', 'PORT'),
    ('country', 'FISHING_GROUND'),
    ('country', 'OIL_FIELD'),
    ('country', 'STRAIT'),
    -- Not STRAIT: a strait borders provinces on both shores rather than lying in one.
    ('province', 'ISLAND'),
    ('province', 'PORT'),
    ('province', 'FISHING_GROUND'),
    ('province', 'OIL_FIELD'),
    ('county', 'ISLAND'),
    ('county', 'PORT'),
    ('district', 'ISLAND'),
    ('district', 'PORT'),
    ('population', 'ISLAND'),
    ('population', 'PORT'),
    ('ethnic_groups', 'ISLAND'),
    ('ethnic_groups', 'PORT'),
    ('languages', 'ISLAND'),
    ('languages', 'PORT')
) AS v (key, kind)
JOIN attribute_definitions d ON d.key = v.key;

INSERT INTO labels (attribute_id, language, value)
SELECT d.id, v.language, v.value
FROM (VALUES
    ('scientific_name', 'fa', 'نام علمی'),
    ('scientific_name', 'en', 'Scientific name'),
    ('scientific_name', 'ar', 'الاسم العلمي'),
    ('category', 'fa', 'دسته‌بندی'),
    ('category', 'en', 'Category'),
    ('category', 'ar', 'الفئة'),
    ('iucn_status', 'fa', 'وضعیت حفاظتی IUCN'),
    ('iucn_status', 'en', 'IUCN status'),
    ('iucn_status', 'ar', 'حالة الحفظ وفق IUCN'),
    ('habitat', 'fa', 'زیستگاه'),
    ('habitat', 'en', 'Habitat'),
    ('habitat', 'ar', 'الموطن'),
    ('species_features', 'fa', 'ویژگی‌ها'),
    ('species_features', 'en', 'Features'),
    ('species_features', 'ar', 'الخصائص'),
    ('max_length', 'fa', 'بیشینهٔ طول'),
    ('max_length', 'en', 'Maximum length'),
    ('max_length', 'ar', 'الطول الأقصى'),
    ('located_in', 'fa', 'موقعیت'),
    ('located_in', 'en', 'Location'),
    ('located_in', 'ar', 'الموقع'),
    ('area', 'fa', 'مساحت'),
    ('area', 'en', 'Area'),
    ('area', 'ar', 'المساحة'),
    ('place_features', 'fa', 'ویژگی‌ها'),
    ('place_features', 'en', 'Features'),
    ('place_features', 'ar', 'الخصائص'),
    ('country', 'fa', 'کشور'),
    ('country', 'en', 'Country'),
    ('country', 'ar', 'الدولة'),
    ('province', 'fa', 'استان'),
    ('province', 'en', 'Province'),
    ('province', 'ar', 'المحافظة'),
    ('county', 'fa', 'شهرستان'),
    ('county', 'en', 'County'),
    ('county', 'ar', 'المقاطعة'),
    ('district', 'fa', 'بخش'),
    ('district', 'en', 'District'),
    ('district', 'ar', 'الناحية'),
    ('population', 'fa', 'جمعیت'),
    ('population', 'en', 'Population'),
    ('population', 'ar', 'عدد السكان'),
    ('ethnic_groups', 'fa', 'قومیت‌ها'),
    ('ethnic_groups', 'en', 'Ethnic groups'),
    ('ethnic_groups', 'ar', 'المجموعات العرقية'),
    ('languages', 'fa', 'زبان‌ها'),
    ('languages', 'en', 'Languages'),
    ('languages', 'ar', 'اللغات')
) AS v (key, language, value)
JOIN attribute_definitions d ON d.key = v.key;

-- Options --------------------------------------------------------------------------------------

-- Country codes are ISO 3166-1 alpha-2 and language codes ISO 639 (639-1 where one exists,
-- 639-3 otherwise), so the frontend can map them without a lookup table of its own.
INSERT INTO attribute_options (attribute_id, code, sort)
SELECT d.id, v.code, v.sort
FROM (VALUES
    ('category', 'FISH', 10),
    ('category', 'CRUSTACEAN', 20),
    ('category', 'MOLLUSC', 30),
    ('category', 'CORAL', 40),
    ('category', 'CNIDARIAN', 50),
    ('category', 'SEA_PLANT', 60),
    ('category', 'MAMMAL', 70),
    ('category', 'REPTILE', 80),
    ('category', 'BIRD', 90),
    ('iucn_status', 'CR', 10),
    ('iucn_status', 'EN', 20),
    ('iucn_status', 'VU', 30),
    ('iucn_status', 'NT', 40),
    ('iucn_status', 'LC', 50),
    ('iucn_status', 'DD', 60),
    ('habitat', 'CORAL_REEF', 10),
    ('habitat', 'MANGROVE', 20),
    ('habitat', 'MUDFLAT', 30),
    ('habitat', 'SEAGRASS', 40),
    ('habitat', 'PELAGIC', 50),
    ('species_features', 'ENDEMIC', 10),
    ('species_features', 'FISHERY', 20),
    ('species_features', 'MIGRATORY', 30),
    ('place_features', 'FREE_ZONE', 10),
    ('place_features', 'UNESCO_GEOPARK', 20),
    ('place_features', 'PROTECTED_AREA', 30),
    ('country', 'IR', 10),
    ('country', 'IQ', 20),
    ('country', 'KW', 30),
    ('country', 'SA', 40),
    ('country', 'BH', 50),
    ('country', 'QA', 60),
    ('country', 'AE', 70),
    ('country', 'OM', 80),
    ('languages', 'FA', 10),
    ('languages', 'AR', 20),
    ('languages', 'BAL', 30),
    ('languages', 'LRL', 40),
    ('languages', 'ZUM', 50)
) AS v (key, code, sort)
JOIN attribute_definitions d ON d.key = v.key;

INSERT INTO labels (option_id, language, value)
SELECT o.id, v.language, v.value
FROM (VALUES
    ('category', 'FISH', 'fa', 'ماهیان'),
    ('category', 'FISH', 'en', 'Fish'),
    ('category', 'FISH', 'ar', 'الأسماك'),
    ('category', 'CRUSTACEAN', 'fa', 'سخت‌پوستان'),
    ('category', 'CRUSTACEAN', 'en', 'Crustaceans'),
    ('category', 'CRUSTACEAN', 'ar', 'القشريات'),
    ('category', 'MOLLUSC', 'fa', 'نرم‌تنان'),
    ('category', 'MOLLUSC', 'en', 'Molluscs'),
    ('category', 'MOLLUSC', 'ar', 'الرخويات'),
    ('category', 'CORAL', 'fa', 'مرجان‌ها'),
    ('category', 'CORAL', 'en', 'Corals'),
    ('category', 'CORAL', 'ar', 'المرجان'),
    ('category', 'CNIDARIAN', 'fa', 'کیسه‌تنان'),
    ('category', 'CNIDARIAN', 'en', 'Cnidarians'),
    ('category', 'CNIDARIAN', 'ar', 'اللاسعات'),
    ('category', 'SEA_PLANT', 'fa', 'گیاهان دریایی'),
    ('category', 'SEA_PLANT', 'en', 'Sea plants'),
    ('category', 'SEA_PLANT', 'ar', 'النباتات البحرية'),
    ('category', 'MAMMAL', 'fa', 'پستانداران'),
    ('category', 'MAMMAL', 'en', 'Mammals'),
    ('category', 'MAMMAL', 'ar', 'الثدييات'),
    ('category', 'REPTILE', 'fa', 'خزندگان'),
    ('category', 'REPTILE', 'en', 'Reptiles'),
    ('category', 'REPTILE', 'ar', 'الزواحف'),
    ('category', 'BIRD', 'fa', 'پرندگان ساحلی'),
    ('category', 'BIRD', 'en', 'Coastal birds'),
    ('category', 'BIRD', 'ar', 'الطيور الساحلية'),
    ('iucn_status', 'CR', 'fa', 'بحرانی'),
    ('iucn_status', 'CR', 'en', 'Critically endangered'),
    ('iucn_status', 'CR', 'ar', 'مهدد بشدة بالانقراض'),
    ('iucn_status', 'EN', 'fa', 'در خطر'),
    ('iucn_status', 'EN', 'en', 'Endangered'),
    ('iucn_status', 'EN', 'ar', 'مهدد بالانقراض'),
    ('iucn_status', 'VU', 'fa', 'آسیب‌پذیر'),
    ('iucn_status', 'VU', 'en', 'Vulnerable'),
    ('iucn_status', 'VU', 'ar', 'معرض للانقراض'),
    ('iucn_status', 'NT', 'fa', 'نزدیک به تهدید'),
    ('iucn_status', 'NT', 'en', 'Near threatened'),
    ('iucn_status', 'NT', 'ar', 'قريب من التهديد'),
    ('iucn_status', 'LC', 'fa', 'کم‌خطر'),
    ('iucn_status', 'LC', 'en', 'Least concern'),
    ('iucn_status', 'LC', 'ar', 'غير مهدد'),
    ('iucn_status', 'DD', 'fa', 'کمبود داده'),
    ('iucn_status', 'DD', 'en', 'Data deficient'),
    ('iucn_status', 'DD', 'ar', 'بيانات ناقصة'),
    ('habitat', 'CORAL_REEF', 'fa', 'آب‌سنگ مرجانی'),
    ('habitat', 'CORAL_REEF', 'en', 'Coral reef'),
    ('habitat', 'CORAL_REEF', 'ar', 'الشعاب المرجانية'),
    ('habitat', 'MANGROVE', 'fa', 'جنگل حرا'),
    ('habitat', 'MANGROVE', 'en', 'Mangrove'),
    ('habitat', 'MANGROVE', 'ar', 'غابات القرم'),
    ('habitat', 'MUDFLAT', 'fa', 'پهنهٔ گلی جزرومدی'),
    ('habitat', 'MUDFLAT', 'en', 'Tidal mudflat'),
    ('habitat', 'MUDFLAT', 'ar', 'المسطحات الطينية المدّية'),
    ('habitat', 'SEAGRASS', 'fa', 'بستر علف دریایی'),
    ('habitat', 'SEAGRASS', 'en', 'Seagrass bed'),
    ('habitat', 'SEAGRASS', 'ar', 'مروج الأعشاب البحرية'),
    ('habitat', 'PELAGIC', 'fa', 'آب‌های آزاد'),
    ('habitat', 'PELAGIC', 'en', 'Open water'),
    ('habitat', 'PELAGIC', 'ar', 'المياه المفتوحة'),
    ('species_features', 'ENDEMIC', 'fa', 'گونهٔ بومی'),
    ('species_features', 'ENDEMIC', 'en', 'Endemic'),
    ('species_features', 'ENDEMIC', 'ar', 'متوطن'),
    ('species_features', 'FISHERY', 'fa', 'دارای ارزش شیلاتی'),
    ('species_features', 'FISHERY', 'en', 'Of fishery value'),
    ('species_features', 'FISHERY', 'ar', 'ذو قيمة سمكية'),
    ('species_features', 'MIGRATORY', 'fa', 'گونهٔ مهاجر'),
    ('species_features', 'MIGRATORY', 'en', 'Migratory'),
    ('species_features', 'MIGRATORY', 'ar', 'مهاجر'),
    ('place_features', 'FREE_ZONE', 'fa', 'منطقهٔ آزاد'),
    ('place_features', 'FREE_ZONE', 'en', 'Free zone'),
    ('place_features', 'FREE_ZONE', 'ar', 'منطقة حرة'),
    ('place_features', 'UNESCO_GEOPARK', 'fa', 'ژئوپارک یونسکو'),
    ('place_features', 'UNESCO_GEOPARK', 'en', 'UNESCO Global Geopark'),
    ('place_features', 'UNESCO_GEOPARK', 'ar', 'حديقة جيولوجية عالمية لليونسكو'),
    ('place_features', 'PROTECTED_AREA', 'fa', 'منطقهٔ حفاظت‌شده'),
    ('place_features', 'PROTECTED_AREA', 'en', 'Protected area'),
    ('place_features', 'PROTECTED_AREA', 'ar', 'منطقة محمية'),
    ('country', 'IR', 'fa', 'ایران'),
    ('country', 'IR', 'en', 'Iran'),
    ('country', 'IR', 'ar', 'إيران'),
    ('country', 'IQ', 'fa', 'عراق'),
    ('country', 'IQ', 'en', 'Iraq'),
    ('country', 'IQ', 'ar', 'العراق'),
    ('country', 'KW', 'fa', 'کویت'),
    ('country', 'KW', 'en', 'Kuwait'),
    ('country', 'KW', 'ar', 'الكويت'),
    ('country', 'SA', 'fa', 'عربستان سعودی'),
    ('country', 'SA', 'en', 'Saudi Arabia'),
    ('country', 'SA', 'ar', 'المملكة العربية السعودية'),
    ('country', 'BH', 'fa', 'بحرین'),
    ('country', 'BH', 'en', 'Bahrain'),
    ('country', 'BH', 'ar', 'البحرين'),
    ('country', 'QA', 'fa', 'قطر'),
    ('country', 'QA', 'en', 'Qatar'),
    ('country', 'QA', 'ar', 'قطر'),
    ('country', 'AE', 'fa', 'امارات متحدهٔ عربی'),
    ('country', 'AE', 'en', 'United Arab Emirates'),
    ('country', 'AE', 'ar', 'الإمارات العربية المتحدة'),
    ('country', 'OM', 'fa', 'عمان'),
    ('country', 'OM', 'en', 'Oman'),
    ('country', 'OM', 'ar', 'عُمان'),
    ('languages', 'FA', 'fa', 'فارسی'),
    ('languages', 'FA', 'en', 'Persian'),
    ('languages', 'FA', 'ar', 'الفارسية'),
    ('languages', 'AR', 'fa', 'عربی'),
    ('languages', 'AR', 'en', 'Arabic'),
    ('languages', 'AR', 'ar', 'العربية'),
    ('languages', 'BAL', 'fa', 'بلوچی'),
    ('languages', 'BAL', 'en', 'Balochi'),
    ('languages', 'BAL', 'ar', 'البلوشية'),
    ('languages', 'LRL', 'fa', 'اچمی (لارستانی)'),
    ('languages', 'LRL', 'en', 'Achomi (Larestani)'),
    ('languages', 'LRL', 'ar', 'اللارستانية'),
    ('languages', 'ZUM', 'fa', 'کمزاری'),
    ('languages', 'ZUM', 'en', 'Kumzari'),
    ('languages', 'ZUM', 'ar', 'الكمزارية')
) AS v (key, code, language, value)
JOIN attribute_definitions d ON d.key = v.key
JOIN attribute_options o ON o.attribute_id = d.id AND o.code = v.code;

-- Kind labels ----------------------------------------------------------------------------------

-- Plural, because their consumer is the kind facet on listing pages, where they read as
-- checkbox options next to plural option labels such as "Fish" and "Crustaceans".
INSERT INTO labels (kind, language, value) VALUES
    ('ISLAND', 'fa', 'جزایر'),
    ('ISLAND', 'en', 'Islands'),
    ('ISLAND', 'ar', 'الجزر'),
    ('PORT', 'fa', 'بنادر'),
    ('PORT', 'en', 'Ports'),
    ('PORT', 'ar', 'الموانئ'),
    ('OIL_FIELD', 'fa', 'میدان‌های نفتی'),
    ('OIL_FIELD', 'en', 'Oil fields'),
    ('OIL_FIELD', 'ar', 'حقول النفط'),
    ('SPECIES', 'fa', 'گونه‌ها'),
    ('SPECIES', 'en', 'Species'),
    ('SPECIES', 'ar', 'الأنواع'),
    ('STRAIT', 'fa', 'تنگه‌ها'),
    ('STRAIT', 'en', 'Straits'),
    ('STRAIT', 'ar', 'المضائق'),
    ('FISHING_GROUND', 'fa', 'صیدگاه‌ها'),
    ('FISHING_GROUND', 'en', 'Fishing grounds'),
    ('FISHING_GROUND', 'ar', 'مناطق الصيد'),
    ('PROVINCE', 'fa', 'استان‌ها'),
    ('PROVINCE', 'en', 'Provinces'),
    ('PROVINCE', 'ar', 'المحافظات');

-- Sanity check ---------------------------------------------------------------------------------

-- Every seeded referent must have all three labels and every definition at least one kind; a
-- miss here means a mistyped key above silently dropped rows.
DO $$
DECLARE
    unlabeled BIGINT;
    kindless  BIGINT;
BEGIN
    SELECT (SELECT count(*) FROM attribute_groups g
            WHERE (SELECT count(*) FROM labels l WHERE l.group_id = g.id) <> 3)
         + (SELECT count(*) FROM attribute_definitions d
            WHERE (SELECT count(*) FROM labels l WHERE l.attribute_id = d.id) <> 3)
         + (SELECT count(*) FROM attribute_options o
            WHERE (SELECT count(*) FROM labels l WHERE l.option_id = o.id) <> 3)
    INTO unlabeled;
    SELECT count(*) INTO kindless
    FROM attribute_definitions d
    WHERE NOT EXISTS (SELECT 1 FROM attribute_definition_kinds k WHERE k.attribute_id = d.id);
    IF unlabeled > 0 OR kindless > 0 THEN
        RAISE EXCEPTION 'V23: % referent(s) without exactly three labels, % definition(s) without a kind',
            unlabeled, kindless;
    END IF;
END $$;
