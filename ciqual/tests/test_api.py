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
