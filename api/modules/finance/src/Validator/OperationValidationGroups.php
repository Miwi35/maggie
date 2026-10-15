<?php

declare(strict_types=1);

namespace Maggie\Finance\Validator;

use ApiPlatform\Symfony\Validator\ValidationGroupsGeneratorInterface;
use Maggie\Finance\Entity\RecurringOperation;

/**
 * Every operation is changed at its one IRI, `/api/recurring_operations/{id}`:
 * a series is held to what a series needs, an operation without recurrence
 * — what the rules screens edit there — is not (MAG-478).
 */
final class OperationValidationGroups implements ValidationGroupsGeneratorInterface
{
    /** @return list<string> */
    public function __invoke(object $object): array
    {
        return $object instanceof RecurringOperation && !$object->hasRecurrence()
            ? ['Default']
            : ['Default', RecurringOperation::VIEW_SERIES];
    }
}
