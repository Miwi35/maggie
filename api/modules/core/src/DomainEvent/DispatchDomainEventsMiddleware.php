<?php

declare(strict_types=1);

namespace Maggie\Core\DomainEvent;

use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;

/** Innermost custom middleware: the handler has flushed, the outer ones have not published yet. */
final class DispatchDomainEventsMiddleware implements MiddlewareInterface
{
    public function __construct(
        private readonly DomainEventCollector $collector,
    ) {
    }

    public function handle(Envelope $envelope, StackInterface $stack): Envelope
    {
        // Skip the first pass (before the transport); dispatch only after the handler ran.
        if (!$envelope->all(ReceivedStamp::class)) {
            return $stack->next()->handle($envelope, $stack);
        }

        $envelope = $stack->next()->handle($envelope, $stack);
        $this->collector->dispatchPending();

        return $envelope;
    }
}
