<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Validator\Exception\ValidationException;
use Maggie\Health\Exception\DuplicateWorkoutException;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Validator\ConstraintViolation;
use Symfony\Component\Validator\ConstraintViolationList;

/**
 * What a workout handler refuses is the request's fault: a 400, except a
 * duplicate externalId, a 422 like every other validation failure.
 */
trait DispatchesWorkoutCommandTrait
{
    private function dispatchWorkoutCommand(MessageBusInterface $bus, object $command): Envelope
    {
        try {
            return $bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof DuplicateWorkoutException) {
                throw new ValidationException(new ConstraintViolationList([new ConstraintViolation($cause->getMessage(), null, [], null, 'externalId', null)]), previous: $e);
            }
            if ($cause instanceof \DomainException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
