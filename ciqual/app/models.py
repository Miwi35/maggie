from pydantic import BaseModel


class FoodSummary(BaseModel):
    alim_code: str
    alim_name_fr: str
    alim_group_code: str | None = None
    alim_group_name_fr: str | None = None
    alim_ssgroup_code: str | None = None
    alim_ssgroup_name_fr: str | None = None
    kcal_per100g: float | None = None
    protein_per100g: float | None = None
    carbs_per100g: float | None = None
    fat_per100g: float | None = None


class Nutrient(BaseModel):
    const_code: str
    const_name_fr: str
    const_unit: str | None = None
    value: float | None = None
    confidence_code: str | None = None
    raw_value: str | None = None


class FoodDetail(BaseModel):
    alim_code: str
    alim_name_fr: str
    alim_group_code: str | None = None
    alim_group_name_fr: str | None = None
    alim_ssgroup_code: str | None = None
    alim_ssgroup_name_fr: str | None = None
    nutrients: list[Nutrient] = []
