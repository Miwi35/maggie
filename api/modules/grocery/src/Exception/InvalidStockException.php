<?php

declare(strict_types=1);

namespace Maggie\Grocery\Exception;

/** A stock state nobody knows, or a restock quantity below zero. The API answers it with a 400. */
final class InvalidStockException extends \DomainException
{
}
