import sqlite3
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient


@pytest.fixture()
def test_db(tmp_path: Path):
    """Create an in-memory-like test SQLite database with sample data."""
    db_path = tmp_path / "ciqual.db"
    conn = sqlite3.connect(str(db_path))
    conn.executescript("""
        CREATE TABLE food (
            alim_code TEXT PRIMARY KEY,
            alim_name_fr TEXT NOT NULL,
            alim_group_code TEXT,
            alim_group_name_fr TEXT,
            alim_ssgroup_code TEXT,
            alim_ssgroup_name_fr TEXT
        );
        CREATE TABLE nutrient (
            const_code TEXT PRIMARY KEY,
            const_name_fr TEXT NOT NULL,
            const_unit TEXT
        );
        CREATE TABLE food_nutrient (
            food_code TEXT NOT NULL,
            nutrient_code TEXT NOT NULL,
            value REAL,
            confidence_code TEXT,
            raw_value TEXT,
            PRIMARY KEY (food_code, nutrient_code)
        );
        CREATE INDEX idx_food_name ON food(alim_name_fr);

        INSERT INTO food VALUES ('1234', 'Pomme crue', '03', 'fruits', '0301', 'fruits frais');
        INSERT INTO food VALUES ('5678', 'Pomme de terre cuite', '04', 'légumes', '0401', 'tubercules');
        INSERT INTO food VALUES ('2001', 'Poulet, filet, cru', '07', 'viandes', '0701', 'volailles');
        INSERT INTO food VALUES ('2002', 'Poulet, cuisse, rôti', '07', 'viandes', '0701', 'volailles');
        INSERT INTO food VALUES ('2003', 'Nugget de poulet, cuit',
            '01', 'entrées et plats composés', '0101', 'plats composés');
        INSERT INTO food VALUES ('2004', 'Bouillon de poulet déshydraté',
            '01', 'entrées et plats composés', '0102', 'bouillons');

        INSERT INTO nutrient VALUES ('328', 'Energie (kcal/100 g)', 'kcal/100 g');
        INSERT INTO nutrient VALUES ('25000', 'Protéines (g/100 g)', 'g/100 g');
        INSERT INTO nutrient VALUES ('31000', 'Glucides (g/100 g)', 'g/100 g');
        INSERT INTO nutrient VALUES ('40000', 'Lipides (g/100 g)', 'g/100 g');
        INSERT INTO nutrient VALUES ('400', 'Vitamine C (mg/100 g)', 'mg/100 g');

        INSERT INTO food_nutrient VALUES ('1234', '328', 52.0, 'A', '52');
        INSERT INTO food_nutrient VALUES ('1234', '25000', 0.3, 'A', '0,3');
        INSERT INTO food_nutrient VALUES ('1234', '31000', 11.4, 'A', '11,4');
        INSERT INTO food_nutrient VALUES ('1234', '40000', 0.2, 'A', '0,2');
        INSERT INTO food_nutrient VALUES ('1234', '400', 5.0, 'B', '5');

        INSERT INTO food_nutrient VALUES ('5678', '328', 80.0, 'A', '80');
        INSERT INTO food_nutrient VALUES ('5678', '25000', 2.0, 'A', '2');
        INSERT INTO food_nutrient VALUES ('5678', '31000', 17.0, 'A', '17');
        INSERT INTO food_nutrient VALUES ('5678', '40000', 0.1, 'A', '0,1');

        INSERT INTO food_nutrient VALUES ('2001', '328', 121.0, 'A', '121');
        INSERT INTO food_nutrient VALUES ('2001', '25000', 22.0, 'A', '22');
        INSERT INTO food_nutrient VALUES ('2001', '31000', 0.0, 'A', '0');
        INSERT INTO food_nutrient VALUES ('2001', '40000', 3.5, 'A', '3,5');

        INSERT INTO food_nutrient VALUES ('2002', '328', 180.0, 'A', '180');
        INSERT INTO food_nutrient VALUES ('2002', '25000', 26.0, 'A', '26');
        INSERT INTO food_nutrient VALUES ('2002', '31000', 0.0, 'A', '0');
        INSERT INTO food_nutrient VALUES ('2002', '40000', 8.5, 'A', '8,5');

        INSERT INTO food_nutrient VALUES ('2003', '328', 280.0, 'A', '280');
        INSERT INTO food_nutrient VALUES ('2003', '25000', 15.0, 'A', '15');
        INSERT INTO food_nutrient VALUES ('2003', '31000', 18.0, 'A', '18');
        INSERT INTO food_nutrient VALUES ('2003', '40000', 16.0, 'A', '16');

        INSERT INTO food_nutrient VALUES ('2004', '328', 15.0, 'A', '15');
        INSERT INTO food_nutrient VALUES ('2004', '25000', 1.0, 'A', '1');
        INSERT INTO food_nutrient VALUES ('2004', '31000', 1.0, 'A', '1');
        INSERT INTO food_nutrient VALUES ('2004', '40000', 0.5, 'A', '0,5');
    """)
    conn.close()
    return db_path


@pytest.fixture()
def client(test_db: Path):
    with patch("app.database.DB_PATH", test_db):
        from app.main import app

        yield TestClient(app)


def test_search_foods(client: TestClient):
    response = client.get("/foods?q=pomme")
    assert response.status_code == 200
    data = response.json()
    assert len(data) == 2
    assert data[0]["alim_code"] == "1234"
    assert data[0]["alim_name_fr"] == "Pomme crue"
    assert data[0]["kcal_per100g"] == 52.0
    assert data[0]["protein_per100g"] == 0.3


def test_search_foods_limit(client: TestClient):
    response = client.get("/foods?q=pomme&limit=1")
    assert response.status_code == 200
    data = response.json()
    assert len(data) == 1


def test_search_foods_no_match(client: TestClient):
    response = client.get("/foods?q=zzzzz")
    assert response.status_code == 200
    assert response.json() == []


def test_search_foods_empty_query(client: TestClient):
    response = client.get("/foods?q=")
    assert response.status_code == 422


def test_search_basic_ingredients_ranked_first(client: TestClient):
    """Basic ingredients (e.g. 'Poulet, filet') should rank before processed foods (e.g. 'Nugget de poulet')."""
    response = client.get("/foods?q=poulet")
    assert response.status_code == 200
    data = response.json()
    assert len(data) == 4
    names = [d["alim_name_fr"] for d in data]
    # "Poulet, ..." entries first (prefix match), then composed dishes last
    assert names[0].startswith("Poulet")
    assert names[1].startswith("Poulet")
    # Composed dishes (group 01) at the end
    assert data[-1]["alim_group_code"] == "01" or data[-2]["alim_group_code"] == "01"


def test_search_multi_word(client: TestClient):
    """'filet poulet' should match 'Poulet, filet, cru' (word order independent)."""
    response = client.get("/foods?q=filet poulet")
    assert response.status_code == 200
    data = response.json()
    assert len(data) == 1
    assert data[0]["alim_name_fr"] == "Poulet, filet, cru"


def test_search_multi_word_with_stop_words(client: TestClient):
    """'filet de poulet' should match the same as 'filet poulet' (stop word 'de' filtered)."""
    response = client.get("/foods?q=filet de poulet")
    assert response.status_code == 200
    data = response.json()
    assert len(data) == 1
    assert data[0]["alim_name_fr"] == "Poulet, filet, cru"


def test_search_only_stop_words_still_works(client: TestClient):
    """A query of only stop words should still perform a search (fallback)."""
    response = client.get("/foods?q=de")
    assert response.status_code == 200
    data = response.json()
    # Should match foods containing "de" (e.g. "Pomme de terre cuite", "Nugget de poulet")
    assert len(data) > 0


def test_get_food(client: TestClient):
    response = client.get("/foods/1234")
    assert response.status_code == 200
    data = response.json()
    assert data["alim_code"] == "1234"
    assert data["alim_name_fr"] == "Pomme crue"
    assert len(data["nutrients"]) == 5
    # Check one nutrient
    kcal = next(n for n in data["nutrients"] if n["const_code"] == "328")
    assert kcal["value"] == 52.0
    assert kcal["const_name_fr"] == "Energie (kcal/100 g)"


def test_get_food_not_found(client: TestClient):
    response = client.get("/foods/9999")
    assert response.status_code == 404
