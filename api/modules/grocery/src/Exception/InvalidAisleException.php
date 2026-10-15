<?php

declare(strict_types=1);

namespace Maggie\Grocery\Exception;

/**
 * An aisle the rules refuse: a blank name, a name the store already has
 * (whatever the case), an aisle of another store, or one that is not the
 * user's. The API answers it with a 400, the MCP tools with an error.
 */
final class InvalidAisleException extends \DomainException
{
}
