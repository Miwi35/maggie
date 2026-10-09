<?php

declare(strict_types=1);

namespace Maggie\Core\DomainEvent;

use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;

/**
 * Innermost custom middleware: the handler has flushed, the outer ones have not published yet.
 *
 * Dispatching from the Doctrine listener itself would let a subscriber flush
 * while the unit of work is still closing the first flush.
 */
final class DispatchDomainEventsMiddleware implements MiddlewareInterface
{
    public function __construct(
        private readonly PendingDomainEvents $pending,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
    }

    public function handle(Envelope $envelope, StackInterface $stack): Envelope
    {
        // Skip the first pass (before the transport); dispatch only after the handler ran.
        if (!$envelope->all(ReceivedStamp::class)) {
            return $stack->next()->handle($envelope, $stack);
        }

        try {
            $envelope = $stack->next()->handle($envelope, $stack);
        } catch (\Throwable $e) {
            $this->pending->reset();

            throw $e;
        }

        // A subscriber's own command can flush more events: loop until none is left.
        while ([] !== $events = $this->pending->release()) {
            foreach ($events as $event) {
                $this->eventBus->dispatch($event);
            }
        }

        return $envelope;
    }
}
