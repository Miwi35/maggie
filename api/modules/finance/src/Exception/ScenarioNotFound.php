<?php

declare(strict_types=1);

namespace Maggie\Finance\Exception;

/** A scenario the user does not have: answered 404 by the API. */
final class ScenarioNotFound extends \DomainException
{
    public function __construct(string $id)
    {
        parent::__construct("Scenario not found: {$id}");
    }
}
