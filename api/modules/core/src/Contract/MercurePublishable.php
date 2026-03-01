<?php

namespace Maggie\Core\Contract;

use Symfony\Component\Uid\Ulid;

interface MercurePublishable
{
    public function getId(): Ulid;

    /**
     * @param string[]|null $changedProperties Doctrine property names that changed, or null for full payload
     *
     * @return array<string, mixed>
     */
    public function toMercurePayload(?array $changedProperties = null): array;
}
