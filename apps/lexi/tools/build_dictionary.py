#!/usr/bin/env python3
"""Build the complete, pinned ECDICT base dictionary with Python stdlib only.

Raw upstream downloads are immutable cache files under ignored build/. Every
source hash is verified before processing. Run --verify to audit the shipped
asset without downloading. Run --reproducibility to rebuild twice and compare.
"""
import argparse
from array import array
from collections import Counter
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
CACHE = ROOT / "build/dictionary-source"
COMMIT = "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b"
BASE_URL = f"https://raw.githubusercontent.com/skywind3000/ECDICT/{COMMIT}/"
SOURCE_ID = "ecdict-base-" + COMMIT[:12] + "-schema2"
EXPECTED_HASHES = {
    # Pinned content hashes; calculated from the exact raw GitHub commit.
    "ecdict.csv": "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf",
    "LICENSE": "f8552dd246f61a4e064569eae6194a01c6b3d63b03bf27c6ca863593c549ed0f",
    "README.md": "0c3f95fc7e15f7eccf65feedb58ba3ebace1fe7b7cbdd1af60c9ace4cef9094b",
}
SCHEMA = """
PRAGMA page_size = 4096;
PRAGMA journal_mode = OFF;
PRAGMA synchronous = OFF;
PRAGMA temp_store = MEMORY;
PRAGMA cache_size = -65536;
PRAGMA application_id = 1279613001;
PRAGMA user_version = 2;
CREATE TABLE entries (
 id INTEGER PRIMARY KEY, word TEXT NOT NULL, lookup TEXT NOT NULL,
 phonetic TEXT NOT NULL, translation TEXT NOT NULL, definition TEXT NOT NULL,
 tags TEXT NOT NULL, exchange TEXT NOT NULL, frequency INTEGER NOT NULL
);
CREATE TABLE aliases (
 form TEXT NOT NULL, entry_id INTEGER NOT NULL,
 PRIMARY KEY(form, entry_id)
) WITHOUT ROWID;
CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
CREATE VIRTUAL TABLE chinese_search USING fts4(terms, content='', tokenize=simple);
CREATE TABLE chinese_meanings (
 meaning TEXT NOT NULL, entry_id INTEGER NOT NULL,
 PRIMARY KEY(meaning, entry_id)
) WITHOUT ROWID;
"""
PREFIX_IDS = """SELECT id FROM entries INDEXED BY entries_lookup
WHERE lookup >= ? AND lookup < ? AND lookup != ?
ORDER BY frequency, length(lookup), lookup, id LIMIT ?"""
CHINESE_IDS = """SELECT entries.id, entries.word, entries.translation FROM chinese_search
JOIN entries ON entries.id = chinese_search.docid
WHERE chinese_search MATCH ? AND instr(lower(entries.translation), ?) > 0
ORDER BY entries.frequency, length(entries.translation), length(entries.lookup), entries.lookup, entries.id
LIMIT ?"""
MEANING_IDS = """SELECT entries.id, entries.word, entries.translation FROM chinese_meanings
JOIN entries ON entries.id = chinese_meanings.entry_id
WHERE chinese_meanings.meaning = ? AND instr(lower(entries.translation), ?) > 0
ORDER BY entries.frequency, length(entries.translation), length(entries.lookup), entries.lookup, entries.id
LIMIT ?"""
MEANING_FORMAT = "lower-no-ascii-space-segments-v1"
FTS4_INTEGRITY_BUG = "https://sqlite.org/bugs/info/442c6e4a5af8adc63c91e5eebb980e7405d8f859538f4897231e7b5617e13be8?t=c"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(name, offline=False):
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / name
    expected = EXPECTED_HASHES.get(name)
    if not target.exists():
        if offline:
            raise FileNotFoundError(f"Offline build requires the pinned cache file: {target}")
        temporary = target.with_suffix(target.suffix + ".tmp")
        with urllib.request.urlopen(BASE_URL + name, timeout=120) as response:
            with temporary.open("wb") as output:
                shutil.copyfileobj(response, output, 1024 * 1024)
        if expected and sha256(temporary) != expected:
            temporary.unlink()
            raise ValueError(f"Downloaded {name} failed SHA-256 verification")
        temporary.replace(target)
    actual = sha256(target)
    if expected and actual != expected:
        raise ValueError(f"Cached {name} differs from pinned upstream: {actual}")
    return target


def normalize(value):
    return value.strip().lower().replace("’", "'").replace("‘", "'").replace("–", "-").replace("—", "-")


def text(value):
    # ECDICT represents definition line breaks as the literal backslash-n.
    return (value or "").replace("\\r\\n", "\n").replace("\\n", "\n")


def is_han(codepoint):
    # Keep these ranges identical to Android DictionaryRepository.isHan().
    return (0x3400 <= codepoint <= 0x4DBF or 0x4E00 <= codepoint <= 0x9FFF
            or 0xF900 <= codepoint <= 0xFAFF or 0x20000 <= codepoint <= 0x323AF)


def chinese_tokens(value):
    """Safe FTS terms: one ASCII token per distinct Han Unicode codepoint."""
    return [f"h{codepoint:x}" for codepoint in sorted({ord(character) for character in value
                                                      if is_han(ord(character))})]


def meaning_key(value):
    return value.lower().replace(" ", "").replace("\r", "")


def meaning_segments(translation):
    # The limit is Android String.length(), i.e. UTF-16 code units.
    return sorted({segment for segment in re.split(r"[\n,，;；.\]]", meaning_key(translation))
                   if segment and sum(2 if ord(character) > 0xFFFF else 1 for character in segment) <= 256
                   and any(is_han(ord(character)) for character in segment)})


def chinese_results(db, query, limit=40):
    query = normalize(query)
    tokens = chinese_tokens(query)
    # No full scan for blank, Latin-only or punctuation-only input in this path.
    if not tokens:
        return []
    exact = db.execute(MEANING_IDS, (meaning_key(query), query, limit)).fetchall()
    if len(exact) == limit:
        return exact
    seen = {row[0] for row in exact}
    partial = db.execute(CHINESE_IDS, (" AND ".join(tokens), query, limit + len(exact)))
    for row in partial:
        if row[0] not in seen:
            exact.append(row)
            seen.add(row[0])
            if len(exact) == limit:
                break
    return exact


def check_physical_integrity(db):
    """Keep the global result verbatim; check all real tables independently.

    SQLite 3.44.0..3.53.x has a confirmed integrity-check bug for contentless
    FTS4. Only its exact known diagnostic is accepted here; the reverse index
    is additionally compared with every source token/docid in validate().
    """
    global_result = [row[0] for row in db.execute("PRAGMA integrity_check")]
    known_diagnostic = "unable to validate the inverted index for FTS4 table main.chinese_search: SQL logic error"
    if global_result not in (["ok"], [known_diagnostic]):
        raise ValueError(f"Database integrity_check failed: {global_result}")
    physical_tables = {}
    for name, sql in db.execute("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name"):
        if sql.lstrip().upper().startswith("CREATE VIRTUAL TABLE"):
            continue
        quoted_name = '"' + name.replace('"', '""') + '"'
        result = [row[0] for row in db.execute(f"PRAGMA integrity_check({quoted_name})")]
        if result != ["ok"]:
            raise ValueError(f"Physical table {name} failed integrity_check: {result}")
        physical_tables[name] = "ok"
    return {"global_pragma_integrity_check": global_result,
            "all_physical_tables": physical_tables,
            "contentless_fts4_global_check_limitation": global_result != ["ok"],
            "limitation_reference": FTS4_INTEGRITY_BUG if global_result != ["ok"] else None}


def rank(row):
    values = []
    for key in ("frq", "bnc"):
        value = (row.get(key) or "").strip()
        if value.isdigit() and int(value) > 0:
            values.append(int(value))
    return min(values) if values else 1_000_000_000


def inflections(exchange, original):
    for item in exchange.split("/"):
        if len(item) >= 3 and item[1] == ":" and item[0] in "pdi3rtsbzf":
            for value in item[2:].split(","):
                form = normalize(value)
                if form and form != original and len(form) <= 256:
                    yield form


def build(csv_path, output):
    if output.exists():
        output.unlink()
    db = sqlite3.connect(output)
    db.executescript(SCHEMA)
    batch, alias_batch, chinese_batch, meaning_batch = [], [], [], []
    translated = phonetic = chinese = token_occurrences = meaning_rows = meaning_entries = 0
    with csv_path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required = {"word", "phonetic", "translation", "definition", "tag", "exchange", "frq", "bnc"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError("ECDICT CSV schema changed")
        count = 0
        for count, row in enumerate(reader, 1):
            word = row["word"]
            if not word or None in row:
                raise ValueError(f"Malformed/empty source row {count}")
            lookup = normalize(word)
            translation = text(row["translation"])
            pronunciation = text(row["phonetic"])
            batch.append((count, word, lookup, pronunciation, translation,
                          text(row["definition"]), row["tag"] or "", row["exchange"] or "", rank(row)))
            alias_batch.extend((form, count) for form in inflections(row["exchange"] or "", lookup))
            tokens = chinese_tokens(translation)
            if tokens:
                chinese_batch.append((count, " ".join(tokens)))
                chinese += 1
                token_occurrences += len(tokens)
            meanings = meaning_segments(translation)
            meaning_batch.extend((meaning, count) for meaning in meanings)
            meaning_rows += len(meanings)
            meaning_entries += bool(meanings)
            translated += bool(translation)
            phonetic += bool(pronunciation)
            if len(batch) == 5000:
                db.executemany("INSERT INTO entries VALUES(?,?,?,?,?,?,?,?,?)", batch)
                db.executemany("INSERT OR IGNORE INTO aliases VALUES(?,?)", alias_batch)
                db.executemany("INSERT INTO chinese_search(docid,terms) VALUES(?,?)", chinese_batch)
                db.executemany("INSERT INTO chinese_meanings VALUES(?,?)", meaning_batch)
                batch.clear()
                alias_batch.clear()
                chinese_batch.clear()
                meaning_batch.clear()
        db.executemany("INSERT INTO entries VALUES(?,?,?,?,?,?,?,?,?)", batch)
        db.executemany("INSERT OR IGNORE INTO aliases VALUES(?,?)", alias_batch)
        db.executemany("INSERT INTO chinese_search(docid,terms) VALUES(?,?)", chinese_batch)
        db.executemany("INSERT INTO chinese_meanings VALUES(?,?)", meaning_batch)
    db.execute("CREATE INDEX entries_lookup ON entries(lookup, frequency)")
    db.executemany("INSERT INTO metadata VALUES (?,?)", [
        ("source_id", SOURCE_ID), ("source_commit", COMMIT), ("entry_count", str(count)),
        ("chinese_index_count", str(chinese)), ("chinese_token_format", "unique-han-codepoint-h-lowercase-hex"),
        ("chinese_meaning_format", MEANING_FORMAT), ("chinese_meaning_count", str(meaning_rows)),
        ("chinese_meaning_max_utf16_length", "256")])
    db.execute("INSERT INTO chinese_search(chinese_search) VALUES('optimize')")
    db.commit()
    db.execute("VACUUM")
    integrity = check_physical_integrity(db)
    aliases = db.execute("SELECT count(*) FROM aliases").fetchone()[0]
    db.close()
    return {"entries": count, "with_translation": translated, "with_phonetic": phonetic,
            "inflection_aliases": aliases, "chinese_index_entries": chinese,
            "chinese_index_token_occurrences": token_occurrences,
            "chinese_meaning_rows": meaning_rows, "chinese_meaning_entries": meaning_entries,
            "sqlite_integrity": "all_physical_tables_ok",
            "global_integrity_check": integrity["global_pragma_integrity_check"]}


def compressed_copy(source, target):
    # mtime=0 and filename='' remove timestamps/paths from the gzip header.
    # Stage outside assets so a parallel APK build cannot include a partial file.
    CACHE.mkdir(parents=True, exist_ok=True)
    temporary = CACHE / (target.name + ".tmp")
    with source.open("rb") as incoming, temporary.open("wb") as output:
        with gzip.GzipFile(filename="", mode="wb", fileobj=output, compresslevel=9, mtime=0) as archive:
            shutil.copyfileobj(incoming, archive, 1024 * 1024)
    temporary.replace(target)


def validate_literal_search():
    """Exercise characters that must never become FTS or SQL syntax."""
    db = sqlite3.connect(":memory:")
    db.executescript(SCHEMA)
    fixtures = ["苹果", "苹果%", "苹果_", "苹果\" OR 香蕉", "苹果*", "电脑 computer", "\U00020000苹果", "电脑Computer"]
    for identifier, translation in enumerate(fixtures, 1):
        db.execute("INSERT INTO entries VALUES(?,?,?,?,?,?,?,?,?)",
                   (identifier, str(identifier), str(identifier), "", translation, "", "", "", identifier))
        db.execute("INSERT INTO chinese_search(docid,terms) VALUES(?,?)",
                   (identifier, " ".join(chinese_tokens(translation))))
        db.executemany("INSERT INTO chinese_meanings VALUES(?,?)",
                       [(meaning, identifier) for meaning in meaning_segments(translation)])
    cases = []
    for query in fixtures + ["", " ", "%", "_", "*", "OR", "computer", "不存在的苹果", "电脑 Computer", "电 脑"]:
        expected = [identifier for identifier, translation in enumerate(fixtures, 1)
                    if chinese_tokens(query) and normalize(query) in translation.lower()]
        actual = [row[0] for row in chinese_results(db, query)]
        assert actual == expected, (query, actual, expected)
        cases.append({"query": query, "ids": actual})
    for lower, upper in ((0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF), (0x20000, 0x323AF)):
        assert is_han(lower) and is_han(upper)
        assert not is_han(lower - 1) and not is_han(upper + 1)
    assert chinese_tokens("果苹苹") == ["h679c", "h82f9"]
    assert chinese_tokens("\U00020000") == ["h20000"]
    db.close()
    return cases


def validate_chinese_index(db):
    expected_ids = []
    expected_terms = Counter()
    expected_postings = {}
    for identifier, translation in db.execute("SELECT id, translation FROM entries ORDER BY id"):
        tokens = chinese_tokens(translation)
        if tokens:
            expected_ids.append(identifier)
            expected_terms.update(tokens)
            for token in tokens:
                expected_postings.setdefault(token, array("I")).append(identifier)
    # Contentless FTS4 cannot SELECT its missing content. The docsize shadow
    # table records the exact docids; fts4aux independently checks every term.
    indexed_ids = [row[0] for row in db.execute("SELECT docid FROM chinese_search_docsize ORDER BY docid")]
    assert indexed_ids == expected_ids, "Chinese index does not cover exactly all Han translations"
    assert int(db.execute("SELECT value FROM metadata WHERE name='chinese_index_count'").fetchone()[0]) == len(expected_ids)
    db.execute("CREATE VIRTUAL TABLE temp.chinese_terms USING fts4aux(main,chinese_search)")
    actual_terms = {}
    for term, documents, occurrences in db.execute("SELECT term, documents, occurrences FROM chinese_terms WHERE col='*'"):
        assert documents == occurrences, f"Repeated Han token in a translation: {term}"
        actual_terms[term] = documents
    assert actual_terms == expected_terms, "Chinese term postings differ from source translations"
    db.execute("DROP TABLE temp.chinese_terms")
    for token, expected_docids in expected_postings.items():
        actual_docids = array("I", (row[0] for row in db.execute(
            "SELECT docid FROM chinese_search WHERE chinese_search MATCH ? ORDER BY docid", (token,))))
        assert actual_docids == expected_docids, f"Chinese term docids differ from source: {token}"
    queries = {}
    timings = {}
    for query, expected in (("苹果", "apple"), ("你好", "How do you do"), ("词典", "dictionary"),
                            ("计算机", "computer"), ("猫", "cat"), ("学习", "learn")):
        start = time.perf_counter()
        rows = chinese_results(db, query)
        timings[query] = round((time.perf_counter() - start) * 1000, 3)
        assert expected in [row[1] for row in rows], f"Missing Chinese-English result: {query} -> {expected}"
        assert all(query in row[2] for row in rows)
        queries[query] = [{"id": row[0], "word": row[1], "translation": row[2]} for row in rows[:5]]
    for query in ("", " ", "%", "_", "OR", "完全不存在的罕见占位词条龘龘龘", "苹果%", "苹果_"):
        assert chinese_results(db, query) == [], f"Unexpected result for literal or empty query: {query!r}"
    query = "苹果"
    plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + CHINESE_IDS,
                                       (" AND ".join(chinese_tokens(query)), query, 40))]
    assert any("VIRTUAL TABLE INDEX" in step for step in plan), plan
    assert any("INTEGER PRIMARY KEY" in step for step in plan), plan
    for query in ("的", "人", "计算机", "学习", "苹果", "百分之"):
        start = time.perf_counter()
        rows = chinese_results(db, query)
        timings[query] = {"results": len(rows), "elapsed_ms": round((time.perf_counter() - start) * 1000, 3)}
    return {"indexed_entries": len(expected_ids), "unique_han_tokens": len(expected_terms),
            "token_occurrences": sum(expected_terms.values()), "all_translation_ids_verified": True,
            "all_term_posting_counts_verified": True, "all_term_docids_verified": True, "queries": queries,
            "query_plan": plan, "query_timings_ms": timings,
            "literal_search_cases": validate_literal_search()}


def validate_chinese_meanings(db):
    assert db.execute("SELECT value FROM metadata WHERE name='chinese_meaning_format'").fetchone()[0] == MEANING_FORMAT
    assert db.execute("SELECT value FROM metadata WHERE name='chinese_meaning_max_utf16_length'").fetchone()[0] == "256"
    # Read independently by entry id, not in the insertion / primary-key order.
    # Compare every expected pair and also reject extra or orphaned pairs.
    actual = iter(db.execute("SELECT entry_id, meaning FROM chinese_meanings ORDER BY entry_id, meaning"))
    rows = entries = 0
    # An independent reference parser uses literal delimiter replacement and
    # encoded UTF-16 size, rather than the builder's regex/codepoint-length path.
    replacements = {ord(character): "\n" for character in "\n,，;；.]"}
    replacements.update({ord(" "): None, ord("\r"): None})
    for identifier, translation in db.execute("SELECT id, translation FROM entries ORDER BY id"):
        normalized = translation.lower().translate(replacements)
        meanings = sorted({part for part in normalized.split("\n")
                           if part and len(part.encode("utf-16-le")) <= 512
                           and any(is_han(ord(character)) for character in part)})
        entries += bool(meanings)
        for meaning in meanings:
            pair = next(actual, None)
            assert pair == (identifier, meaning), f"Chinese meaning pair differs from source at entry {identifier}"
            rows += 1
    assert next(actual, None) is None, "Chinese meanings contain extra or orphaned pairs"
    assert int(db.execute("SELECT value FROM metadata WHERE name='chinese_meaning_count'").fetchone()[0]) == rows
    unique = db.execute("SELECT count(DISTINCT meaning) FROM chinese_meanings").fetchone()[0]
    plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + MEANING_IDS, ("苹果", "苹果", 40))]
    assert any("PRIMARY KEY (meaning=?)" in step for step in plan), plan
    timings = {}
    for query in ("的", "一", "人", "学", "水", "苹果", "词典", "计算机", "学习"):
        exact = db.execute(MEANING_IDS, (meaning_key(query), query, 40)).fetchall()
        start = time.perf_counter()
        result = chinese_results(db, query)
        timings[query] = {"results": len(result), "full_meaning_results": len(exact),
                          "fts_fallback_used": len(exact) < 40,
                          "elapsed_ms": round((time.perf_counter() - start) * 1000, 3),
                          "first_words": [row[1] for row in result[:5]]}
    return {"format": MEANING_FORMAT, "max_utf16_length": 256, "rows": rows,
            "entries": entries, "unique_meanings": unique, "every_meaning_entry_pair_verified": True,
            "reference_method": "literal delimiter replacement and encoded UTF-16 length; independent of builder regex",
            "query_plan": plan, "two_stage_query_timings_ms": timings}


def validate(database):
    db = sqlite3.connect(f"file:{database.as_posix()}?mode=ro", uri=True)
    integrity = check_physical_integrity(db)
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    count = db.execute("SELECT count(*) FROM entries").fetchone()[0]
    assert count == 770_611, f"Incomplete dictionary: {count} entries"
    assert db.execute("PRAGMA user_version").fetchone()[0] == 2
    schema = db.execute("SELECT sql FROM sqlite_master WHERE name='chinese_search'").fetchone()[0].lower()
    assert "using fts4" in schema and "content=''" in schema and "tokenize=simple" in schema
    db.execute("SELECT docid FROM chinese_search WHERE chinese_search MATCH 'h82f9' LIMIT 0").fetchall()
    assert db.execute("SELECT count(*) FROM entries WHERE word = ''").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM aliases LEFT JOIN entries ON entries.id = aliases.entry_id WHERE entries.id IS NULL").fetchone()[0] == 0
    assert db.execute("SELECT value FROM metadata WHERE name='source_id'").fetchone()[0] == SOURCE_ID
    queries = {}
    for query in ("apple", "hello", "dictionary", "serendipity", "run", "computer", "can't"):
        row = db.execute("SELECT word, translation FROM entries WHERE lookup=? ORDER BY frequency, id LIMIT 1", (query,)).fetchone()
        assert row and row[1], f"Missing English-Chinese definition: {query}"
        queries[query] = {"word": row[0], "translation": row[1]}
    for query, expected in (("went", "go"), ("children", "child"), ("running", "run")):
        words = [row[0] for row in db.execute("SELECT entries.word FROM aliases JOIN entries ON entries.id=aliases.entry_id WHERE form=? ORDER BY frequency", (query,))]
        assert expected in words, f"Missing inflection {query} -> {expected}"
        queries[query + "_base_forms"] = words
    plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + PREFIX_IDS, ("app", "app\uffff", "app", 40))]
    assert any("COVERING INDEX entries_lookup" in step for step in plan), plan
    timings = {}
    for prefix in ("a", "app", "seren", "q", "micro"):
        start = time.perf_counter()
        result = db.execute(PREFIX_IDS, (prefix, prefix + "\uffff", prefix, 40)).fetchall()
        timings[prefix] = {"results": len(result), "elapsed_ms": round((time.perf_counter() - start) * 1000, 3)}
    result = {"entry_count": count, "schema_version": 2, "integrity_checks": integrity, "queries": queries, "prefix_plan": plan,
              "prefix_timings_ms": timings, "sqlite_version": sqlite3.sqlite_version}
    result["chinese_index"] = validate_chinese_index(db)
    result["chinese_meanings"] = validate_chinese_meanings(db)
    db.close()
    return result


def verify_asset():
    info = json.loads((ASSETS / "dictionary-source.json").read_text(encoding="utf-8"))
    asset = ASSETS / info["asset"]["filename"]
    assert sha256(asset) == info["asset"]["sha256"]
    unpacked = CACHE / "verify-dictionary.db"
    CACHE.mkdir(parents=True, exist_ok=True)
    with gzip.open(asset, "rb") as source, unpacked.open("wb") as target:
        shutil.copyfileobj(source, target)
    assert sha256(unpacked) == info["database"]["sha256"]
    assert unpacked.stat().st_size == info["database"]["bytes"]
    assert info["database"]["schema_version"] == 2
    for name, expected in info["packaged_licenses"].items():
        assert sha256(ASSETS / name) == expected
    return validate(unpacked)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Verify existing packaged asset only")
    parser.add_argument("--reproducibility", action="store_true", help="Rebuild a second database and compare exact hashes")
    parser.add_argument("--offline", action="store_true", help="Require cached pinned sources; never download")
    parser.add_argument("--report", type=Path, help="Write validation JSON to a new evidence file")
    args = parser.parse_args()
    if args.verify:
        result = verify_asset()
    else:
        sources = {name: fetch(name, offline=args.offline) for name in ("ecdict.csv", "LICENSE", "README.md")}
        ASSETS.mkdir(parents=True, exist_ok=True)
        (ASSETS / "licenses").mkdir(exist_ok=True)
        shutil.copyfile(sources["LICENSE"], ASSETS / "licenses/ECDICT-MIT.txt")
        database = CACHE / "dictionary-schema2-meanings.db"
        counts = build(sources["ecdict.csv"], database)
        db_hash = sha256(database)
        result = validate(database)
        # AAPT treats a .gz suffix specially and may expand/rename the asset.
        # Keep gzip bytes under .pack and declare noCompress 'pack' in Gradle.
        asset = ASSETS / "dictionary.pack"
        compressed_copy(database, asset)
        assert asset.stat().st_size < 100_000_000, "Packaged dictionary exceeds the 100 MB budget"
        info = {
            "name": "ECDICT bilingual base dictionary with offline Chinese reverse index", "source_id": SOURCE_ID,
            "repository": "https://github.com/skywind3000/ECDICT", "commit": COMMIT,
            "upstream_license": "MIT", "upstream_copyright": "Copyright (c) 2025 Linwei",
            "source_files": {name: {"url": BASE_URL + name, "sha256": sha256(path), "bytes": path.stat().st_size}
                             for name, path in sources.items()},
            "counts": counts,
            "database": {"schema_version": 2, "sha256": db_hash, "bytes": database.stat().st_size,
                         "sqlite_version": sqlite3.sqlite_version},
            "asset": {"filename": asset.name, "compression": "gzip", "sha256": sha256(asset), "bytes": asset.stat().st_size},
            "packaged_licenses": {"licenses/ECDICT-MIT.txt": sha256(ASSETS / "licenses/ECDICT-MIT.txt")},
            "transformations": ["All CSV rows kept in original order; id is 1-based CSV logical record number",
                                "Unescape literal backslash-n in definitions, translations and phonetics",
                                "Lookup is trimmed lowercase; curly apostrophes and en/em dashes normalized",
                                "Frequency is min of positive frq/bnc ranks, otherwise 1000000000",
                                "Forward inflection aliases from exchange p/d/i/3/r/t/s/b/z/f; original exchange retained",
                                "Contentless FTS4 reverse index: distinct Han codepoints in each translation, ASCII h + lowercase hex tokens",
                                "Han ranges U+3400-U+4DBF, U+4E00-U+9FFF, U+F900-U+FAFF, U+20000-U+323AF; docid equals entries.id",
                                "Chinese MATCH intersects all query Han tokens; instr(translation, query) enforces the literal substring",
                                "Full-meaning index lower-no-ascii-space-segments-v1: lowercase, remove ASCII space and CR, split LF/comma/CJK comma/semicolon/CJK semicolon/period/right square bracket; distinct per entry; Han segments up to 256 UTF-16 units",
                                "Chinese full-meaning equality query runs first; enough matches skip FTS, otherwise append literal FTS substring results without duplicate ids",
                                "SQLite entries/index/aliases; deterministic gzip mtime=0 without filename"]}
        manifest_temporary = CACHE / "dictionary-source.json.tmp"
        manifest_temporary.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        manifest_temporary.replace(ASSETS / "dictionary-source.json")
        result["database_sha256"] = db_hash
        result["asset_sha256"] = info["asset"]["sha256"]
        if args.reproducibility:
            second = CACHE / "reproducibility-dictionary-schema2-meanings.db"
            build(sources["ecdict.csv"], second)
            assert sha256(second) == db_hash, "Second build not byte-identical"
            second_asset = CACHE / "reproducibility-dictionary.pack"
            compressed_copy(second, second_asset)
            assert sha256(second_asset) == info["asset"]["sha256"], "Second gzip not byte-identical"
            result["reproducibility"] = {"database_byte_identical": True, "asset_byte_identical": True}
        result["counts"] = counts
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        if args.report.exists():
            raise FileExistsError(f"Evidence already exists, choose a new path: {args.report}")
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
