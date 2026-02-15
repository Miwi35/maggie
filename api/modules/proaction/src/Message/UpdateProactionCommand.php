<?php

declare(strict_types=1);

namespace Maggie\Proaction\Message;

final readonly class UpdateProactionCommand
{
    public function __construct(
        public string $proactionId,
        public ?string $status = null,
        public ?string $response = null,
        public ?string $error = null,
        public ?\DateTimeImmutable $completedAt = null,
    ) {
    }
}
