<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Exception\IncompatibleCategoryException;
use Symfony\Component\HttpKernel\Exception\UnprocessableEntityHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * A category whose nature contradicts the sign of the reference amount is a
 * refused body, like any other constraint: 422, not 500.
 */
trait DispatchesRecurringOperationTrait
{
    private function dispatchForResult(MessageBusInterface $bus, object $command): RecurringOperation
    {
        try {
            return $bus->dispatch($command)->last(HandledStamp::class)->getResult();
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof IncompatibleCategoryException) {
                throw new UnprocessableEntityHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
