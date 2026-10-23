"""Exercise the production Java Chinese lookup SQL against real SQLite / FTS4.

Only synthetic definitions are used. The Java helper is compiled in a temporary
directory, so this requires a JDK but neither Android nor third-party Python
packages. Run with JAVA_HOME set to the same JDK as the Android build.
"""
import base64
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest


class ChineseLookupSqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="lexi-chinese-sql-")
        cls.directory = Path(cls.temporary.name)
        cls.helper_cache = {}
        java_home = os.environ.get("JAVA_HOME")
        suffix = ".exe" if os.name == "nt" else ""
        cls.java = str(Path(java_home) / "bin" / ("java" + suffix)) if java_home else shutil.which("java")
        javac = str(Path(java_home) / "bin" / ("javac" + suffix)) if java_home else shutil.which("javac")
        if not cls.java or not javac:
            raise RuntimeError("Set JAVA_HOME to the JDK used to build Lexi")
        probe = cls.directory / "ChineseLookupSqlProbe.java"
        probe.write_text("""package com.ethan.lexi;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public final class ChineseLookupSqlProbe {
    public static void main(String[] args) {
        String output;
        if (args[0].equals("--exact")) output = ChineseLookup.exactMeaningsSql("e.id,e.word,e.translation");
        else if (args[0].equals("--partial")) output = ChineseLookup.partialMeaningsSql("e.id,e.word,e.translation");
        else if (args[0].equals("--key")) output = ChineseLookup.meaningKey(args[1]);
        else output = ChineseLookup.matchExpression(args[0]);
        System.out.print(Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8)));
    }
}
""", encoding="utf-8")
        helper = Path(__file__).resolve().parents[1] / "app/src/main/java/com/ethan/lexi/ChineseLookup.java"
        subprocess.run([javac, "-encoding", "UTF-8", "-d", str(cls.directory), str(helper), str(probe)], check=True)
        cls.exact_sql = cls.run_helper("--exact")
        cls.partial_sql = cls.run_helper("--partial")

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    @classmethod
    def run_helper(cls, *args):
        if args in cls.helper_cache:
            return cls.helper_cache[args]
        result = subprocess.check_output([cls.java, "-cp", str(cls.directory), "com.ethan.lexi.ChineseLookupSqlProbe", *args])
        output = base64.b64decode(result).decode("utf-8")
        cls.helper_cache[args] = output
        return output

    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript("""
            CREATE TABLE entries (id INTEGER PRIMARY KEY, word TEXT, lookup TEXT, translation TEXT, frequency INTEGER);
            CREATE VIRTUAL TABLE chinese_search USING fts4(terms, content='', tokenize=simple);
            CREATE TABLE chinese_meanings (meaning TEXT NOT NULL, entry_id INTEGER NOT NULL,
                PRIMARY KEY(meaning,entry_id)) WITHOUT ROWID;
        """)
        rows = [
            (1, "malic", "n. 苹果酸", 1),
            (2, "apple", "n. 苹果, 苹果树", 100),
            (3, "exact", "苹果", 9999),
            (4, "port", "[计] USB接口", 20),
            (5, "reordered", "果味和苹香", 1),
            (6, "separated", "苹, 果", 1),
            (7, "percent", "n. 苹果%配料", 50),
            (8, "quote", "n. 苹果'果", 50),
            (9, "traditional", "n. 蘋果", 50),
            (10, "supplementary", "n. " + chr(0x20000) + "字", 50),
            (11, "compoundport", "n. 大型USB接口", 1),
        ]
        for identifier, word, translation, frequency in rows:
            self.db.execute("INSERT INTO entries VALUES (?,?,?,?,?)", (identifier, word, word, translation, frequency))
            self.db.execute("INSERT INTO chinese_search(docid,terms) VALUES (?,?)", (identifier, self.run_helper(translation)))
        # Explicit synthetic meaning keys exercise the packaged-index contract.
        meanings = {1: ["苹果酸"], 2: ["苹果", "苹果树"], 3: ["苹果"], 4: ["usb接口"],
                    5: ["果味和苹香"], 6: ["苹", "果"], 7: ["苹果%配料"], 8: ["苹果'果"],
                    9: ["蘋果"], 10: [chr(0x20000) + "字"], 11: ["大型usb接口"]}
        for identifier, keys in meanings.items():
            self.db.executemany("INSERT INTO chinese_meanings VALUES (?,?)", ((key, identifier) for key in keys))

    def query(self, text, limit=30):
        normalized = text.strip().lower()
        key = self.run_helper("--key", normalized)
        exact = self.db.execute(self.exact_sql, (key, normalized, limit)).fetchall()
        if len(exact) == limit:
            return exact
        partial = self.db.execute(self.partial_sql, (self.run_helper(normalized), normalized, limit)).fetchall()
        result = {row[0]: row for row in exact}
        for row in partial:
            result.setdefault(row[0], row)
        return list(result.values())[:limit]

    def test_exact_sense_beats_more_frequent_compound(self):
        identifiers = [row[0] for row in self.query("苹果")]
        self.assertEqual([2, 3], identifiers[:2])
        self.assertGreater(identifiers.index(1), identifiers.index(2))

    def test_character_candidates_still_require_the_literal_contiguous_phrase(self):
        identifiers = [row[0] for row in self.query("苹果")]
        self.assertNotIn(5, identifiers)
        self.assertNotIn(6, identifiers)
        self.assertEqual([], self.query("苹果OR*"))

    def test_quotes_percent_and_fts_operators_are_literal(self):
        self.assertEqual([7], [row[0] for row in self.query("苹果%")])
        self.assertEqual([8], [row[0] for row in self.query("苹果'果")])
        self.assertEqual([], self.query("苹果' OR 1=1 --"))
        self.assertEqual([], self.query('苹果" NOT *'))

    def test_mixed_ascii_case_traditional_and_supplementary_characters(self):
        self.assertEqual([4, 11], [row[0] for row in self.query("  USB接口  ")])
        self.assertEqual([9], [row[0] for row in self.query("蘋果")])
        self.assertEqual([10], [row[0] for row in self.query(chr(0x20000) + "字")])

    def test_limit_applies_to_ranked_results(self):
        self.assertEqual([2, 3], [row[0] for row in self.query("苹果", 2)])
        self.assertEqual([], self.query("苹果", 0))

    def test_removed_spaces_are_only_an_index_key_and_do_not_change_literal_matching(self):
        self.assertEqual([], self.query("苹 果"))
        self.assertEqual([], self.query("USB 接口"))

    def test_duplicate_exact_results_cannot_consume_partial_result_capacity(self):
        for identifier in range(100, 160):
            text = "香蕉" if identifier < 103 else "香蕉配料" + str(identifier)
            self.db.execute("INSERT INTO entries VALUES (?,?,?,?,?)", (identifier, str(identifier), str(identifier), text, identifier))
            self.db.execute("INSERT INTO chinese_search(docid,terms) VALUES (?,?)", (identifier, self.run_helper("香蕉")))
            if identifier < 103:
                self.db.execute("INSERT INTO chinese_meanings VALUES ('香蕉',?)", (identifier,))
        result = self.query("香蕉", 40)
        self.assertEqual(40, len(result))
        self.assertEqual(40, len({row[0] for row in result}))
        self.assertEqual([100, 101, 102], [row[0] for row in result[:3]])

    def test_enough_complete_meanings_do_not_execute_the_partial_fts_stage(self):
        self.db.execute("DROP TABLE chinese_search")
        self.assertEqual([2, 3], [row[0] for row in self.query("苹果", 2)])

    def test_plan_uses_the_fts_index_then_entry_primary_keys(self):
        arguments = (self.run_helper("苹果"), "苹果", 30)
        plan = [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + self.partial_sql, arguments)]
        self.assertTrue(any("VIRTUAL TABLE INDEX" in step for step in plan), plan)
        self.assertTrue(any("SEARCH e USING INTEGER PRIMARY KEY" in step for step in plan), plan)
        self.assertFalse(any(step.startswith("SCAN e") for step in plan), plan)
        plan = [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + self.exact_sql, ("苹果", "苹果", 30))]
        self.assertTrue(any("SEARCH m USING PRIMARY KEY" in step for step in plan), plan)
        self.assertTrue(any("SEARCH e USING INTEGER PRIMARY KEY" in step for step in plan), plan)
        self.assertFalse(any(step.startswith("SCAN e") or step.startswith("SCAN m") for step in plan), plan)


if __name__ == "__main__":
    unittest.main()
