<?php

declare(strict_types=1);

namespace Maggie\Core\Contract;

/**
 * A publishable row whose clients know it by a key of their own rather than by
 * its ULID: the activity in progress is `/api/activity-status/cuisine`, one per
 * user, so its Mercure topic is spelled with `cuisine` (MAG-613).
 *
 * The key replaces the id in the topic and in the payload's `@id`, so it must
 * be unique among the owner's rows of that class. Not for an indexed entity:
 * Elasticsearch documents stay keyed by the ULID.
 */
interface MercureItemKey
{
    public function getMercureItemKey(): string;
}
