<?php

namespace Maggie\Agenda\Contract;

use Symfony\Component\Uid\Uuid;

interface MercurePublishable
{
    public function getId(): Uuid;

    /** @return array<string, mixed> */
    public function toMercurePayload(): array;
}
