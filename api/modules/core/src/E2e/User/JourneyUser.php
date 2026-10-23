<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\User;

use Maggie\Core\Entity\User;

final readonly class JourneyUser
{
    /**
     * @param array<string, array{class: class-string, id: string}> $references
     */
    public function __construct(
        public User $user,
        public ?User $neighbour,
        public array $references,
        public \DateTimeImmutable $anchor,
    ) {
    }
}
