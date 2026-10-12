<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class TagStandardCategoriesCommand
{
    public function __construct(
        public string $userId,
    ) {
    }
}
