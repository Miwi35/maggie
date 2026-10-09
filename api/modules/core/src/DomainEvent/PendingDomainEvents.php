<?php

declare(strict_types=1);

namespace Maggie\Core\DomainEvent;

use Symfony\Contracts\Service\ResetInterface;

/** Domain events a Doctrine listener has triggered, waiting for the command that flushed to finish. */
final class PendingDomainEvents implements ResetInterface
{
    /** @var list<object> */
    private array $events = [];

    public function defer(object $event): void
    {
        $this->events[] = $event;
    }

    /** @return list<object> Returned once, then forgotten. */
    public function release(): array
    {
        $events = $this->events;
        $this->events = [];

        return $events;
    }

    public function reset(): void
    {
        $this->events = [];
    }
}
