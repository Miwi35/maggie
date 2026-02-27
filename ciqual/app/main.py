from fastapi import FastAPI, HTTPException, Query

from app.database import get_db
from app.models import FoodDetail, FoodSummary, Nutrient

KCAL_CODE = "328"
PROTEIN_CODE = "25000"
CARBS_CODE = "31000"
FAT_CODE = "40000"
MACRO_CODES = (KCAL_CODE, PROTEIN_CODE, CARBS_CODE, FAT_CODE)

FRENCH_STOP_WORDS = frozenset(
    {
        "de",
        "du",
        "d",
        "le",
        "la",
        "les",
        "l",
        "au",
        "aux",
        "un",
        "une",
        "des",
        "et",
        "ou",
        "en",
    }
)

# Group code for composed dishes / processed foods
COMPOSED_DISHES_GROUP = "01"

app = FastAPI(title="Ciqual", root_path="/ciqual")


def _parse_search_words(q: str) -> list[str]:
    """Split query into significant words, filtering French stop words."""
    words = q.lower().split()
    filtered = [w for w in words if w not in FRENCH_STOP_WORDS]
    return filtered or words


def _build_search_query(words: list[str], limit: int) -> tuple[str, list]:
    """Build SQL with multi-word AND matching and relevance ranking."""
    where_clauses = ["alim_name_fr LIKE ?"] * len(words)
    where_params = [f"%{w}%" for w in words]

    prefix_clauses = ["lower(alim_name_fr) LIKE ?"] * len(words)
    prefix_params = [f"{w}%" for w in words]

    where_sql = " AND ".join(where_clauses)
    prefix_sql = " OR ".join(prefix_clauses)

    sql = f"""
        SELECT *
        FROM food
        WHERE {where_sql}
        ORDER BY
            CASE WHEN ({prefix_sql}) THEN 0 ELSE 1 END,
            CASE WHEN alim_group_code = '{COMPOSED_DISHES_GROUP}' THEN 1 ELSE 0 END,
            length(alim_name_fr),
            alim_name_fr
        LIMIT ?
    """
    return sql, where_params + prefix_params + [limit]


@app.get("/foods", response_model=list[FoodSummary])
def search_foods(q: str = Query(min_length=1), limit: int = Query(default=20, ge=1, le=100)):
    with get_db() as conn:
        words = _parse_search_words(q)
        sql, params = _build_search_query(words, limit)
        foods = conn.execute(sql, params).fetchall()

        if not foods:
            return []

        alim_codes = [f["alim_code"] for f in foods]
        placeholders = ",".join("?" for _ in alim_codes)
        macro_placeholders = ",".join("?" for _ in MACRO_CODES)

        macros = conn.execute(
            f"""
            SELECT food_code, nutrient_code, value
            FROM food_nutrient
            WHERE food_code IN ({placeholders})
              AND nutrient_code IN ({macro_placeholders})
            """,
            [*alim_codes, *MACRO_CODES],
        ).fetchall()

        macro_map: dict[str, dict[str, float | None]] = {}
        for row in macros:
            food_code = row["food_code"]
            if food_code not in macro_map:
                macro_map[food_code] = {}
            macro_map[food_code][row["nutrient_code"]] = row["value"]

        results = []
        for f in foods:
            m = macro_map.get(f["alim_code"], {})
            results.append(
                FoodSummary(
                    alim_code=f["alim_code"],
                    alim_name_fr=f["alim_name_fr"],
                    alim_group_code=f["alim_group_code"],
                    alim_group_name_fr=f["alim_group_name_fr"],
                    alim_ssgroup_code=f["alim_ssgroup_code"],
                    alim_ssgroup_name_fr=f["alim_ssgroup_name_fr"],
                    kcal_per100g=m.get(KCAL_CODE),
                    protein_per100g=m.get(PROTEIN_CODE),
                    carbs_per100g=m.get(CARBS_CODE),
                    fat_per100g=m.get(FAT_CODE),
                )
            )

        return results


@app.get("/foods/{alim_code}", response_model=FoodDetail)
def get_food(alim_code: str):
    with get_db() as conn:
        food = conn.execute("SELECT * FROM food WHERE alim_code = ?", (alim_code,)).fetchone()

        if food is None:
            raise HTTPException(status_code=404, detail="Food not found")

        nutrients = conn.execute(
            """
            SELECT n.const_code, n.const_name_fr, n.const_unit,
                   fn.value, fn.confidence_code, fn.raw_value
            FROM food_nutrient fn
            JOIN nutrient n ON n.const_code = fn.nutrient_code
            WHERE fn.food_code = ?
            ORDER BY n.const_code
            """,
            (alim_code,),
        ).fetchall()

        return FoodDetail(
            alim_code=food["alim_code"],
            alim_name_fr=food["alim_name_fr"],
            alim_group_code=food["alim_group_code"],
            alim_group_name_fr=food["alim_group_name_fr"],
            alim_ssgroup_code=food["alim_ssgroup_code"],
            alim_ssgroup_name_fr=food["alim_ssgroup_name_fr"],
            nutrients=[
                Nutrient(
                    const_code=n["const_code"],
                    const_name_fr=n["const_name_fr"],
                    const_unit=n["const_unit"],
                    value=n["value"],
                    confidence_code=n["confidence_code"],
                    raw_value=n["raw_value"],
                )
                for n in nutrients
            ],
        )
