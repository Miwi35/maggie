<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Exception;

/** Another utensil of the user already has that name (MAG-489). */
final class UtensilNameTakenException extends \DomainException
{
    public function __construct(string $name)
    {
        parent::__construct("Another utensil is already named « {$name} ».");
    }
}
