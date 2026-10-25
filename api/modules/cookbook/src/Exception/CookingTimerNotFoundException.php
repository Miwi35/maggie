<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Exception;

/** No timer of that id belongs to the user: answered 404, never 403. */
final class CookingTimerNotFoundException extends \DomainException
{
    public function __construct(string $timerId)
    {
        parent::__construct(\sprintf('Cooking timer not found: %s', $timerId));
    }
}
