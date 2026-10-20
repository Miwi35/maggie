<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** The user's whole priority order: every project id, first one first. */
final readonly class ReorderProjectsCommand
{
    /** @param list<string> $ids */
    public function __construct(
        public string $userId,
        public array $ids,
    ) {
    }
}
