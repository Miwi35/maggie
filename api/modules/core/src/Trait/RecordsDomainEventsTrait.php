<?php

declare(strict_types=1);

namespace Maggie\Core\Trait;

trait RecordsDomainEventsTrait
{
    /** @var list<object> */
    private array $domainEvents = [];

    /** @return list<object> */
    public function releaseDomainEvents(): array
    {
        $events = $this->domainEvents;
        $this->domainEvents = [];

        return $events;
    }

    protected function recordDomainEvent(object $event): void
    {
        $this->domainEvents[] = $event;
    }

    /** @param class-string $class */
    protected function forgetDomainEvents(string $class): void
    {
        $this->domainEvents = array_values(array_filter(
            $this->domainEvents,
            static fn (object $event) => !$event instanceof $class,
        ));
    }
}
