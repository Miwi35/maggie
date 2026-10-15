<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Exception;

/** A recipe names a utensil by an id that is not one of its owner's (MAG-489). */
final class UnknownUtensilException extends \DomainException
{
    public function __construct(string $id)
    {
        parent::__construct("Utensil not found: {$id}");
    }
}
