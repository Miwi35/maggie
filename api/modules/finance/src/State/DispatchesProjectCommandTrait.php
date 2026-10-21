<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

/** What a project or loan handler refuses — a cycle, another user's project, conditions missing for a loan's type — is the request's fault: a 400. */
trait DispatchesProjectCommandTrait
{
    private function dispatchProjectCommand(MessageBusInterface $bus, object $command): Envelope
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
