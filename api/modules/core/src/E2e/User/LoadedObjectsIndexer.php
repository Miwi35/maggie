<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\User;

/** Makes the rows a fixture load just wrote findable through search before the caller moves on. */
interface LoadedObjectsIndexer
{
    /** @param array<string, object> $objects */
    public function index(array $objects): void;
}
