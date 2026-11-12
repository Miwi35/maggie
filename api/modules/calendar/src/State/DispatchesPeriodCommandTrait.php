<?php

declare(strict_types=1);

namespace Maggie\Calendar\State;

use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

/** What a period handler refuses — another user's agenda, an unknown holiday — is the request's fault: a 400. */
trait DispatchesPeriodCommandTrait
{
    private function dispatchPeriodCommand(MessageBusInterface $bus, object $command): Envelope
    {
        try {
            return $bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof \DomainException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
