<?php

declare(strict_types=1);

namespace Maggie\Grocery\Exception;

/** A season month outside 1–12. The API and the MCP tools answer it with a 400 / an error. */
final class InvalidSeasonMonthsException extends \DomainException
{
}
