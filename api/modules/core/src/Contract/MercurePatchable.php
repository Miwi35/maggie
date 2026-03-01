<?php

namespace Maggie\Core\Contract;

/**
 * Commands implementing this interface provide a lightweight patch payload
 * for Mercure instead of the full entity data from toMercurePayload().
 *
 * Clients apply the patch to their local state without needing to refetch.
 */
interface MercurePatchable
{
    /** @return array<string, mixed> */
    public function toMercurePatch(): array;
}
