<?php

namespace Maggie\Core\Contract;

use Symfony\Component\Uid\Ulid;

interface MercurePublishable
{
    public function getId(): Ulid;

    /** @return array<string, mixed> */
    public function toMercurePayload(): array;
}
