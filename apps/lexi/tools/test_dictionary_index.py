"""Checks that the independent Chinese-index audit rejects incomplete data."""
import sqlite3
import unittest

import build_dictionary as dictionary


class ChineseIndexAuditTest(unittest.TestCase):
    def fixture(self, translations=("苹果", "香蕉")):
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.executescript(dictionary.SCHEMA)
        for identifier, translation in enumerate(translations, 1):
            db.execute("INSERT INTO entries VALUES(?,?,?,?,?,?,?,?,?)",
                       (identifier, str(identifier), str(identifier), "", translation, "", "", "", identifier))
        db.execute("INSERT INTO metadata VALUES('chinese_index_count',?)", (str(len(translations)),))
        return db

    def test_swapped_postings_are_rejected_even_with_correct_counts(self):
        db = self.fixture()
        for identifier, translation in ((1, "香蕉"), (2, "苹果")):
            db.execute("INSERT INTO chinese_search(docid,terms) VALUES(?,?)",
                       (identifier, " ".join(dictionary.chinese_tokens(translation))))
        with self.assertRaisesRegex(AssertionError, "Chinese term docids differ"):
            dictionary.validate_chinese_index(db)

    def test_missing_indexed_translation_is_rejected(self):
        db = self.fixture()
        db.execute("INSERT INTO chinese_search(docid,terms) VALUES(1,'h679c h82f9')")
        with self.assertRaisesRegex(AssertionError, "cover exactly all Han translations"):
            dictionary.validate_chinese_index(db)

    def test_duplicate_tokens_are_rejected(self):
        db = self.fixture(("苹果",))
        db.execute("INSERT INTO chinese_search(docid,terms) VALUES(1,'h679c h82f9 h82f9')")
        with self.assertRaisesRegex(AssertionError, "Repeated Han token"):
            dictionary.validate_chinese_index(db)

    def test_literal_and_supplementary_plane_queries(self):
        cases = dictionary.validate_literal_search()
        self.assertGreaterEqual(len(cases), 15)

    def test_empty_and_latin_queries_do_not_access_a_table(self):
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        for query in ("", " ", "apple", "OR * % _"):
            with self.subTest(query=query):
                self.assertEqual([], dictionary.chinese_results(db, query))

    def meaning_fixture(self):
        db = self.fixture()
        db.executemany("INSERT INTO metadata VALUES(?,?)", [
            ("chinese_meaning_format", dictionary.MEANING_FORMAT),
            ("chinese_meaning_max_utf16_length", "256"),
            ("chinese_meaning_count", "2")])
        return db

    def test_meaning_separators_and_normalization_contract(self):
        self.assertEqual({"苹果", "香蕉", "梨", "桃", "西瓜", "[计", "电脑"}, set(
            dictionary.meaning_segments("N. 苹 果, 香蕉；梨; 桃. 西瓜\n[计] 电 脑\r")))
        self.assertEqual(["甲a"], dictionary.meaning_segments("甲A;甲a;ascii only"))

    def test_meaning_length_is_counted_in_utf16_units(self):
        accepted_bmp, accepted_supplementary = "甲" * 256, "\U00020000" * 128
        text = ",".join((accepted_bmp, "甲" * 257, accepted_supplementary, "\U00020000" * 129))
        self.assertEqual({accepted_bmp, accepted_supplementary}, set(dictionary.meaning_segments(text)))

    def test_swapped_meaning_entry_pairs_are_rejected(self):
        db = self.meaning_fixture()
        db.executemany("INSERT INTO chinese_meanings VALUES(?,?)", [("苹果", 2), ("香蕉", 1)])
        with self.assertRaisesRegex(AssertionError, "meaning pair differs"):
            dictionary.validate_chinese_meanings(db)

    def test_missing_meaning_entry_pair_is_rejected(self):
        db = self.meaning_fixture()
        db.execute("INSERT INTO chinese_meanings VALUES('苹果',1)")
        with self.assertRaisesRegex(AssertionError, "meaning pair differs"):
            dictionary.validate_chinese_meanings(db)

    def test_orphaned_meaning_entry_pair_is_rejected(self):
        db = self.meaning_fixture()
        db.executemany("INSERT INTO chinese_meanings VALUES(?,?)", [("苹果", 1), ("香蕉", 2), ("额外", 999)])
        with self.assertRaisesRegex(AssertionError, "extra or orphaned"):
            dictionary.validate_chinese_meanings(db)


if __name__ == "__main__":
    unittest.main()
