"""CLI script to build ciqual.db from XML data files."""

import re
import sqlite3
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "ciqual"
DB_PATH = Path(__file__).resolve().parent.parent / "db" / "ciqual.db"

SCHEMA = """
CREATE TABLE IF NOT EXISTS food (
    alim_code TEXT PRIMARY KEY,
    alim_name_fr TEXT NOT NULL,
    alim_group_code TEXT,
    alim_group_name_fr TEXT,
    alim_ssgroup_code TEXT,
    alim_ssgroup_name_fr TEXT
);

CREATE TABLE IF NOT EXISTS nutrient (
    const_code TEXT PRIMARY KEY,
    const_name_fr TEXT NOT NULL,
    const_unit TEXT
);

CREATE TABLE IF NOT EXISTS food_nutrient (
    food_code TEXT NOT NULL REFERENCES food(alim_code),
    nutrient_code TEXT NOT NULL REFERENCES nutrient(const_code),
    value REAL,
    confidence_code TEXT,
    raw_value TEXT,
    PRIMARY KEY (food_code, nutrient_code)
);

CREATE INDEX IF NOT EXISTS idx_food_name ON food(alim_name_fr);
"""


def parse_value(raw: str) -> float | None:
    """Parse a Ciqual raw value string into a float.

    Rules (ported from CiqualValueParser.php):
    - Empty or "-" → None
    - "traces" → 0.0
    - "< X" → X / 2
    - French comma decimals → float
    """
    trimmed = raw.strip()
    if not trimmed or trimmed == "-":
        return None
    if trimmed.lower() == "traces":
        return 0.0
    if trimmed.startswith("<"):
        number = trimmed.lstrip("<").strip().replace(",", ".")
        try:
            return float(number) / 2
        except ValueError:
            return None
    normalized = trimmed.replace(",", ".")
    try:
        return float(normalized)
    except ValueError:
        return None


def extract_unit(name_fr: str) -> str | None:
    """Extract unit from nutrient name, e.g. 'Energie (kcal/100 g)' → 'kcal/100 g'."""
    m = re.search(r"\(([^)]+)\)\s*$", name_fr)
    return m.group(1).strip() if m else None


def import_nutrients(conn: sqlite3.Connection) -> int:
    const_file = DATA_DIR / "const.xml"
    tree = ET.parse(const_file)
    root = tree.getroot()

    rows = []
    for node in root.iter("CONST"):
        const_code = (node.findtext("const_code") or "").strip()
        const_name_fr = (node.findtext("const_nom_fr") or "").strip()
        if not const_code:
            continue
        const_unit = extract_unit(const_name_fr)
        rows.append((const_code, const_name_fr, const_unit))

    conn.executemany(
        "INSERT OR IGNORE INTO nutrient (const_code, const_name_fr, const_unit) VALUES (?, ?, ?)",
        rows,
    )
    conn.commit()
    print(f"Imported {len(rows)} nutrients.")
    return len(rows)


def import_foods(conn: sqlite3.Connection) -> int:
    # Build group name lookup from alim_grp.xml
    grp_names: dict[str, str] = {}
    ssgrp_names: dict[str, str] = {}

    grp_file = DATA_DIR / "alim_grp.xml"
    if grp_file.exists():
        tree = ET.parse(grp_file)
        for node in tree.getroot().iter("ALIM_GRP"):
            grp_code = (node.findtext("alim_grp_code") or "").strip()
            grp_name = (node.findtext("alim_grp_nom_fr") or "").strip()
            ssgrp_code = (node.findtext("alim_ssgrp_code") or "").strip()
            ssgrp_name = (node.findtext("alim_ssgrp_nom_fr") or "").strip()

            if grp_code and grp_name and grp_code not in grp_names:
                grp_names[grp_code] = grp_name
            if ssgrp_code and ssgrp_name and ssgrp_name != "-":
                ssgrp_names[ssgrp_code] = ssgrp_name

    # Parse foods
    alim_file = DATA_DIR / "alim.xml"
    tree = ET.parse(alim_file)
    root = tree.getroot()

    rows = []
    for node in root.iter("ALIM"):
        alim_code = (node.findtext("alim_code") or "").strip()
        alim_name_fr = (node.findtext("alim_nom_fr") or "").strip()
        if not alim_code:
            continue

        grp_code = (node.findtext("alim_grp_code") or "").strip() or None
        ssgrp_code = (node.findtext("alim_ssgrp_code") or "").strip() or None

        rows.append(
            (
                alim_code,
                alim_name_fr,
                grp_code,
                grp_names.get(grp_code) if grp_code else None,
                ssgrp_code,
                ssgrp_names.get(ssgrp_code) if ssgrp_code else None,
            )
        )

    sql = (
        "INSERT OR IGNORE INTO food"
        " (alim_code, alim_name_fr, alim_group_code, alim_group_name_fr,"
        " alim_ssgroup_code, alim_ssgroup_name_fr) VALUES (?, ?, ?, ?, ?, ?)"
    )
    conn.executemany(sql, rows)
    conn.commit()
    print(f"Imported {len(rows)} foods.")
    return len(rows)


def import_compositions(conn: sqlite3.Connection) -> int:
    compo_file = DATA_DIR / "compo.xml"

    # Stream with iterparse to avoid loading 69MB into memory at once
    count = 0
    batch: list[tuple] = []
    batch_size = 5000
    insert_sql = (
        "INSERT OR IGNORE INTO food_nutrient"
        " (food_code, nutrient_code, value, confidence_code, raw_value)"
        " VALUES (?, ?, ?, ?, ?)"
    )

    for _event, elem in ET.iterparse(compo_file, events=("end",)):
        if elem.tag != "COMPO":
            continue

        alim_code = (elem.findtext("alim_code") or "").strip()
        const_code = (elem.findtext("const_code") or "").strip()
        raw_value = (elem.findtext("teneur") or "").strip()
        confidence_code = (elem.findtext("code_confiance") or "").strip() or None

        if not alim_code or not const_code:
            elem.clear()
            continue

        parsed = parse_value(raw_value) if raw_value else None

        batch.append((alim_code, const_code, parsed, confidence_code, raw_value or None))
        count += 1

        if count % batch_size == 0:
            conn.executemany(insert_sql, batch)
            conn.commit()
            batch = []
            print(f"  Inserted {count} compositions...", end="\r")

        # Free memory
        elem.clear()

    if batch:
        conn.executemany(insert_sql, batch)
        conn.commit()

    print(f"Imported {count} compositions.        ")
    return count


def main():
    # Verify data files exist
    for name in ("const.xml", "alim.xml", "alim_grp.xml", "compo.xml"):
        if not (DATA_DIR / name).exists():
            print(f"Error: {DATA_DIR / name} not found", file=sys.stderr)
            sys.exit(1)

    DB_PATH.parent.mkdir(parents=True, exist_ok=True)

    # Remove existing DB to rebuild from scratch
    if DB_PATH.exists():
        DB_PATH.unlink()

    conn = sqlite3.connect(str(DB_PATH))
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=OFF")
    conn.executescript(SCHEMA)

    print("Building Ciqual database...")
    import_nutrients(conn)
    import_foods(conn)
    import_compositions(conn)

    # Optimize for read-only usage
    conn.execute("ANALYZE")
    conn.execute("PRAGMA optimize")
    conn.close()

    size_mb = DB_PATH.stat().st_size / (1024 * 1024)
    print(f"Done! Database: {DB_PATH} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
