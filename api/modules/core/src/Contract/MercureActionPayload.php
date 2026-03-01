<?php

namespace Maggie\Core\Contract;

/**
 * Commands implementing this interface provide a lightweight action payload
 * for Mercure instead of the full entity data from toMercurePayload().
 *
 * Used for non-CRUD semantic actions (Check, Reorder, Remove…).
 * Clients parse the 'action' field and apply the diff to local state.
 */
interface MercureActionPayload
{
    /** @return array<string, mixed> */
    public function toMercureActionPayload(): array;
}
