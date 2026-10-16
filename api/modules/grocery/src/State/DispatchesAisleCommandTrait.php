<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use Maggie\Grocery\Exception\InvalidAisleException;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;

/**
 * The aisle processors answer an aisle the rules refuse with a 400, the
 * answer the MCP tools give as an error. The using class holds the bus as `$bus`.
 */
trait DispatchesAisleCommandTrait
{
    private function dispatchAisleCommand(object $command): Envelope
    {
        try {
            return $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof InvalidAisleException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
