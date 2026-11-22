from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app.auth import get_current_user_id
from app.skills.document import render_markdown
from app.skills.index import skill_index

router = APIRouter()


class SkillCreate(BaseModel):
    name: str = Field(max_length=200)
    description: str
    tags: list[str]
    content: str


class SkillUpdate(BaseModel):
    description: str | None = None
    tags: list[str] | None = None
    content: str | None = None


@router.get("/skills")
async def get_skills(_user_id: str = Depends(get_current_user_id)):
    """List all skills (name, description, tags)."""
    await skill_index.refresh()
    entries = skill_index.list_all()
    return [{"name": e.name, "description": e.description, "tags": e.tags} for e in entries]


@router.get("/skills/{name}")
async def get_skill_detail(name: str, _user_id: str = Depends(get_current_user_id)):
    """Get full skill content by name."""
    skill = await skill_index.get_skill(name)
    if skill is None:
        raise HTTPException(status_code=404, detail="Skill not found")
    return {
        "name": skill.name,
        "description": skill.description,
        "tags": list(skill.tags or []),
        "content": render_markdown(skill.name, skill.description, list(skill.tags or []), skill.content),
    }


@router.post("/skills", status_code=201)
async def create_skill_endpoint(data: SkillCreate, user_id: str = Depends(get_current_user_id)):
    """Create a new skill."""
    entry = await skill_index.create(data.name, data.description, data.tags, data.content, user_id, by_owner=True)
    return {"name": entry.name, "description": entry.description, "tags": entry.tags}


@router.put("/skills/{name}")
async def update_skill_endpoint(name: str, data: SkillUpdate, user_id: str = Depends(get_current_user_id)):
    """Update an existing skill."""
    entry = await skill_index.update(
        name,
        description=data.description,
        tags=data.tags,
        content=data.content,
        user_id=user_id,
        by_owner=True,
    )
    if entry is None:
        raise HTTPException(status_code=404, detail="Skill not found")
    return {"name": entry.name, "description": entry.description, "tags": entry.tags}


@router.delete("/skills/{name}")
async def delete_skill_endpoint(name: str, user_id: str = Depends(get_current_user_id)):
    """Delete a skill."""
    deleted = await skill_index.delete(name, user_id=user_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Skill not found")
    return {"deleted": True}
