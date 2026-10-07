<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use Maggie\Grocery\Exception\InvalidPackagingException;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;

/**
 * The product and ingredient processors answer a packaging the handler
 * refused with a 400, the same answer the MCP tools give as an error.
 * The using class holds the bus as `$bus`.
 */
trait DispatchesProductCommandTrait
{
    private function dispatchProductCommand(object $command): Envelope
    {
        try {
            return $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof InvalidPackagingException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
