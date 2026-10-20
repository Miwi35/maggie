<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

/**
 * Null leaves a field untouched; a field listed in $clearFields is emptied.
 * $dependsOn, when given, replaces the whole list.
 */
final readonly class UpdateProjectCommand
{
    use ClearsFieldsTrait;

    public const CLEARABLE = ['note', 'dependsOn', 'targetOn'];

    /**
     * @param list<string>|null                   $dependsOn
     * @param list<'note'|'dependsOn'|'targetOn'> $clearFields
     */
    public function __construct(
        public string $userId,
        public string $projectId,
        public ?string $name = null,
        public ?int $amountCents = null,
        public ?string $currency = null,
        public ?string $wellbeing = null,
        public ?string $dateMode = null,
        public ?string $targetOn = null,
        public ?array $dependsOn = null,
        public ?string $note = null,
        public array $clearFields = [],
    ) {
    }
}
