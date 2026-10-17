<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** The spend is mine alone again: its whole amount counts, its créance goes. */
final readonly class RemoveMyShareCommand
{
    public function __construct(
        public string $userId,
        public string $transactionId,
    ) {
    }
}
