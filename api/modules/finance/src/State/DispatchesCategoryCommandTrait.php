<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use Maggie\Finance\Entity\Category;
use Symfony\Component\HttpKernel\Exception\UnprocessableEntityHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The category handlers refuse a body with a DomainException (a third level,
 * its own parent...): a refused body is a 422, not a 500.
 */
trait DispatchesCategoryCommandTrait
{
    private function dispatchForCategory(MessageBusInterface $bus, object $command): Category
    {
        try {
            return $bus->dispatch($command)->last(HandledStamp::class)->getResult();
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof \DomainException) {
                throw new UnprocessableEntityHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
