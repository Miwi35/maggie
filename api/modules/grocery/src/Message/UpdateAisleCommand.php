<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class UpdateAisleCommand
{
    /** @param ?int $position where to move it, 1 = first; the others keep their order around it */
    public function __construct(
        public string $userId,
        public string $aisleId,
        public ?string $name = null,
        public ?int $position = null,
    ) {
    }
}
