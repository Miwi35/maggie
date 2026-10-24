<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Exception;

/**
 * An ingredient cannot be replaced as asked (MAG-601): the line is not in the
 * recipe, the new product is not the owner's or is already on another line.
 * The request is wrong, not the server: nothing is written.
 */
final class InvalidIngredientReplacementException extends \DomainException
{
}
