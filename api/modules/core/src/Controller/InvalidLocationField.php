<?php

declare(strict_types=1);

namespace Maggie\Core\Controller;

use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;

/** A field of a body UserLocationController refuses, and why: answered as a 400 naming it. */
final class InvalidLocationField extends \RuntimeException
{
    public function __construct(
        private readonly ?string $field,
        string $message,
    ) {
        parent::__construct($message);
    }

    public function response(): JsonResponse
    {
        return new JsonResponse(
            array_filter(['error' => $this->getMessage(), 'field' => $this->field]),
            Response::HTTP_BAD_REQUEST,
        );
    }
}
