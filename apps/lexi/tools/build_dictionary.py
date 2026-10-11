#!/usr/bin/env python3
"""Build the complete, pinned ECDICT base dictionary with Python stdlib only.

Raw upstream downloads are immutable cache files under ignored build/. Every
source hash is verified before processing. Run --verify to audit the shipped
asset without downloading. Run --reproducibility to rebuild twice and compare.
"""
import argparse
import csv
import gzip
import hashlib
import json
from pathlib import Path
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
SOURCE_ID = "ecdict-base-" + COMMIT[:12] + "-schema1"
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
PRAGMA application_id = 1279613001;
PRAGMA user_version = 1;
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
"""
PREFIX_IDS = """SELECT id FROM entries INDEXED BY entries_lookup
WHERE lookup >= ? AND lookup < ? AND lookup != ?
ORDER BY frequency, length(lookup), lookup, id LIMIT ?"""


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(name):
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / name
    expected = EXPECTED_HASHES.get(name)
    if not target.exists():
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
    batch, alias_batch = [], []
    translated = phonetic = 0
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
            translated += bool(translation)
            phonetic += bool(pronunciation)
            if len(batch) == 5000:
                db.executemany("INSERT INTO entries VALUES(?,?,?,?,?,?,?,?,?)", batch)
                db.executemany("INSERT OR IGNORE INTO aliases VALUES(?,?)", alias_batch)
                batch.clear()
                alias_batch.clear()
        db.executemany("INSERT INTO entries VALUES(?,?,?,?,?,?,?,?,?)", batch)
        db.executemany("INSERT OR IGNORE INTO aliases VALUES(?,?)", alias_batch)
    db.execute("CREATE INDEX entries_lookup ON entries(lookup, frequency)")
    db.executemany("INSERT INTO metadata VALUES (?,?)", [
        ("source_id", SOURCE_ID), ("source_commit", COMMIT), ("entry_count", str(count))])
    db.commit()
    db.execute("VACUUM")
    integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
    if integrity != "ok":
        raise ValueError(integrity)
    aliases = db.execute("SELECT count(*) FROM aliases").fetchone()[0]
    db.close()
    return {"entries": count, "with_translation": translated, "with_phonetic": phonetic,
            "inflection_aliases": aliases, "sqlite_integrity": integrity}


def compressed_copy(source, target):
    # mtime=0 and filename='' remove timestamps/paths from the gzip header.
    # Stage outside assets so a parallel APK build cannot include a partial file.
    CACHE.mkdir(parents=True, exist_ok=True)
    temporary = CACHE / (target.name + ".tmp")
    with source.open("rb") as incoming, temporary.open("wb") as output:
        with gzip.GzipFile(filename="", mode="wb", fileobj=output, compresslevel=9, mtime=0) as archive:
            shutil.copyfileobj(incoming, archive, 1024 * 1024)
    temporary.replace(target)


def validate(database):
    db = sqlite3.connect(f"file:{database.as_posix()}?mode=ro", uri=True)
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    count = db.execute("SELECT count(*) FROM entries").fetchone()[0]
    assert count >= 760_000, f"Incomplete dictionary: {count} entries"
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
    result = {"entry_count": count, "integrity_check": "ok", "queries": queries, "prefix_plan": plan,
              "prefix_timings_ms": timings, "sqlite_version": sqlite3.sqlite_version}
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
    for name, expected in info["packaged_licenses"].items():
        assert sha256(ASSETS / name) == expected
    return validate(unpacked)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Verify existing packaged asset only")
    parser.add_argument("--reproducibility", action="store_true", help="Rebuild a second database and compare exact hashes")
    parser.add_argument("--report", type=Path, help="Write validation JSON to a new evidence file")
    args = parser.parse_args()
    if args.verify:
        result = verify_asset()
    else:
        sources = {name: fetch(name) for name in ("ecdict.csv", "LICENSE", "README.md")}
        ASSETS.mkdir(parents=True, exist_ok=True)
        (ASSETS / "licenses").mkdir(exist_ok=True)
        shutil.copyfile(sources["LICENSE"], ASSETS / "licenses/ECDICT-MIT.txt")
        database = CACHE / "dictionary.db"
        counts = build(sources["ecdict.csv"], database)
        db_hash = sha256(database)
        # AAPT treats a .gz suffix specially and may expand/rename the asset.
        # Keep gzip bytes under .pack and declare noCompress 'pack' in Gradle.
        asset = ASSETS / "dictionary.pack"
        compressed_copy(database, asset)
        info = {
            "name": "ECDICT English-Chinese base dictionary", "source_id": SOURCE_ID,
            "repository": "https://github.com/skywind3000/ECDICT", "commit": COMMIT,
            "upstream_license": "MIT", "upstream_copyright": "Copyright (c) 2025 Linwei",
            "source_files": {name: {"url": BASE_URL + name, "sha256": sha256(path), "bytes": path.stat().st_size}
                             for name, path in sources.items()},
            "counts": counts,
            "database": {"schema_version": 1, "sha256": db_hash, "bytes": database.stat().st_size,
                         "sqlite_version": sqlite3.sqlite_version},
            "asset": {"filename": asset.name, "compression": "gzip", "sha256": sha256(asset), "bytes": asset.stat().st_size},
            "packaged_licenses": {"licenses/ECDICT-MIT.txt": sha256(ASSETS / "licenses/ECDICT-MIT.txt")},
            "transformations": ["All CSV rows kept in original order; id is 1-based CSV logical record number",
                                "Unescape literal backslash-n in definitions, translations and phonetics",
                                "Lookup is trimmed lowercase; curly apostrophes and en/em dashes normalized",
                                "Frequency is min of positive frq/bnc ranks, otherwise 1000000000",
                                "Forward inflection aliases from exchange p/d/i/3/r/t/s/b/z/f; original exchange retained",
                                "SQLite entries/index/aliases; deterministic gzip mtime=0 without filename"]}
        manifest_temporary = CACHE / "dictionary-source.json.tmp"
        manifest_temporary.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        manifest_temporary.replace(ASSETS / "dictionary-source.json")
        result = validate(database)
        result["database_sha256"] = db_hash
        result["asset_sha256"] = info["asset"]["sha256"]
        if args.reproducibility:
            second = CACHE / "reproducibility-dictionary.db"
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
