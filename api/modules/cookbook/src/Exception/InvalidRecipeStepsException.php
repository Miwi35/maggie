<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Exception;

/**
 * The steps of a recipe do not hold together with the rest of it (MAG-490):
 * an ingredient the recipe does not list, a step waiting for a later one…
 * The request is wrong, not the server: nothing is written.
 */
final class InvalidRecipeStepsException extends \DomainException
{
}
