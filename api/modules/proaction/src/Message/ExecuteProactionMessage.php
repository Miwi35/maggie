<?php

declare(strict_types=1);

namespace Maggie\Proaction\Message;

final readonly class ExecuteProactionMessage
{
    public function __construct(
        public string $proactionId,
    ) {
    }
}
