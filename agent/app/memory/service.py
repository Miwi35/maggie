"""The memory service: the bucket and everything built on it, assembled once from the settings.

`memory_service` stays None when no bucket is configured (a dev checkout without the keys): the
agent then runs exactly as before, on the old `memory` table, and never starts the sync.
`MEMORY_BUCKET=fake` keeps the bucket in memory — the e2e stack, which adds no MinIO.
"""

import logging
from dataclasses import dataclass

from app.config import Settings, settings
from app.db.memory_note_repository import memory_note_repo
from app.memory.bucket import Bucket, FakeBucket, S3Bucket
from app.memory.locks import PathLocks
from app.memory.notifier import MemoryNotifier
from app.memory.reconciler import Reconciler
from app.memory.store import NoteStore
from app.memory.summary import SummaryWriter
from app.memory.sync import MemorySync

logger = logging.getLogger(__name__)

FAKE_BUCKET = "fake"


@dataclass
class MemoryService:
    bucket: Bucket
    store: NoteStore
    reconciler: Reconciler
    summary: SummaryWriter
    sync: MemorySync


def build_bucket(config: Settings) -> Bucket | None:
    if not config.memory_bucket:
        return None
    if config.memory_bucket == FAKE_BUCKET:
        if config.tts_provider != "fake":  # the e2e stack's marker (app/e2e.py): never a real deployment
            logger.error("MEMORY_BUCKET=fake is only for the e2e stack: refusing it, the memory bucket stays off")
            return None
        return FakeBucket()
    return S3Bucket(
        bucket=config.memory_bucket,
        endpoint_url=config.memory_bucket_endpoint,
        region=config.memory_bucket_region,
        access_key=config.memory_bucket_key,
        secret_key=config.memory_bucket_secret,
        timeout_seconds=config.memory_bucket_timeout_seconds,
        conditional_writes=config.memory_bucket_conditional_writes,
    )


def build_service(bucket: Bucket, config: Settings = settings) -> MemoryService:
    locks = PathLocks()
    notifier = MemoryNotifier()
    summary = SummaryWriter(bucket, memory_note_repo)
    store = NoteStore(
        bucket,
        memory_note_repo,
        locks,
        notifier,
        summary,
        timeout_seconds=config.memory_turn_sync_timeout_seconds,
    )
    reconciler = Reconciler(bucket, memory_note_repo, locks, notifier)
    sync = MemorySync(
        reconciler,
        store,
        summary,
        memory_note_repo,
        interval_seconds=config.memory_sync_interval_seconds,
        turn_max_age_seconds=config.memory_turn_sync_max_age_seconds,
        turn_timeout_seconds=config.memory_turn_sync_timeout_seconds,
    )
    return MemoryService(bucket, store, reconciler, summary, sync)


memory_service: MemoryService | None = None


def configure(config: Settings = settings) -> MemoryService | None:
    """Build the service from the settings; None (and a log line) when no bucket is configured."""
    global memory_service
    bucket = build_bucket(config)
    if bucket is None:
        logger.warning("MEMORY_BUCKET is not set: the memory bucket is off, notes are not synced")
        memory_service = None
    else:
        memory_service = build_service(bucket, config)
    return memory_service


def get_service() -> MemoryService | None:
    return memory_service
