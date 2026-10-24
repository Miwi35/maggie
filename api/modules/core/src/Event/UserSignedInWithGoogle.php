<?php

declare(strict_types=1);

namespace Maggie\Core\Event;

/** The owner signed in with Google and their tokens are stored: the modules that read from Google may refresh. */
final readonly class UserSignedInWithGoogle
{
    public function __construct(public string $userId)
    {
    }
}
