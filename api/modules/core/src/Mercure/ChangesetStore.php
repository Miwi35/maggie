<?php

namespace Maggie\Core\Mercure;

/**
 * Request-scoped store for Doctrine entity changesets.
 *
 * The onFlush listener captures changed property names before UOW clears them.
 * The MercurePublishMiddleware reads them later to publish differential updates.
 *
 * Keyed by spl_object_id — safe because the same PHP object flows
 * handler → middleware within a single dispatch cycle.
 */
class ChangesetStore
{
    /** @var array<int, string[]> */
    private array $changesets = [];

    /** @param string[] $properties */
    public function capture(object $entity, array $properties): void
    {
        $oid = spl_object_id($entity);
        $this->changesets[$oid] = array_unique(
            array_merge($this->changesets[$oid] ?? [], $properties),
        );
    }

    /** @return string[]|null null if entity was not tracked (e.g. creates) */
    public function get(object $entity): ?array
    {
        return $this->changesets[spl_object_id($entity)] ?? null;
    }
}
