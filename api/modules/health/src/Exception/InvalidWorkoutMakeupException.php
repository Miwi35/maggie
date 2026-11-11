<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

use Maggie\Health\Enum\MakeupOption;

/** An option that is not one of softer, shorter, tomorrow: 400. */
final class InvalidWorkoutMakeupException extends \DomainException
{
    public readonly string $field;

    public function __construct()
    {
        $this->field = 'option';
        parent::__construct('option must be one of: '.implode(', ', MakeupOption::values()).'.');
    }
}
