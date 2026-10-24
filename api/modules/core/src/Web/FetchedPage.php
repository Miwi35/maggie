<?php

declare(strict_types=1);

namespace Maggie\Core\Web;

final readonly class FetchedPage
{
    public function __construct(
        public string $finalUrl,
        public string $html,
    ) {
    }
}
