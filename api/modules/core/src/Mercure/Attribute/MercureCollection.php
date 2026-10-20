<?php

declare(strict_types=1);

namespace Maggie\Core\Mercure\Attribute;

/**
 * The collection topic of an entity served under a path of its own
 * (`/api/finance/projects`) rather than the one its class name gives
 * (`/api/projects`): the topic must stay the path the clients read, and
 * MercureTopicContractTest compares the two.
 */
#[\Attribute(\Attribute::TARGET_CLASS)]
final class MercureCollection
{
    public function __construct(
        public readonly string $path,
    ) {
    }
}
