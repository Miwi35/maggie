<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * One write on the user's scenarios (MAG-550): create, update, duplicate,
 * delete, make_current, clear_current or suggest (a draft from the advisor).
 * `$fields` holds only what the caller gave: name, projectIds, repayments,
 * placementIds, monthlySaving.
 */
final readonly class ChangeScenarioCommand
{
    public const CREATE = 'create';
    public const UPDATE = 'update';
    public const DUPLICATE = 'duplicate';
    public const DELETE = 'delete';
    public const MAKE_CURRENT = 'make_current';
    public const CLEAR_CURRENT = 'clear_current';
    public const SUGGEST = 'suggest';

    /** @param array<string, mixed> $fields */
    public function __construct(
        public string $userId,
        public string $action,
        public ?string $scenarioId = null,
        public array $fields = [],
    ) {
    }
}
