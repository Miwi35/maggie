from fastapi import APIRouter

from app.api.approval_routes import router as approval_router
from app.api.chat_routes import router as chat_router
from app.api.memory_routes import router as memory_router
from app.api.message_routes import router as message_router
from app.api.proaction_routes import router as proaction_router
from app.api.settings_routes import router as settings_router
from app.api.skill_routes import router as skill_router
from app.api.system_routes import router as system_router
from app.api.voice_routes import router as voice_router

router = APIRouter()
router.include_router(memory_router)
router.include_router(system_router)
router.include_router(chat_router)
router.include_router(message_router)
router.include_router(approval_router)
router.include_router(proaction_router)
router.include_router(settings_router)
router.include_router(voice_router)
router.include_router(skill_router)
