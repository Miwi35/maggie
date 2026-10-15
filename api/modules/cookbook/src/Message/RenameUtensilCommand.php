<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class RenameUtensilCommand
{
    public function __construct(
        public string $utensilId,
        public string $name,
    ) {
    }
}
