<?php

declare(strict_types=1);

namespace Maggie\Core\Contract;

use Symfony\Component\Uid\Ulid;

interface IndexableInterface
{
    public function getId(): Ulid;

    /** @return array<string, mixed> */
    public function toSearchDocument(): array;
}
