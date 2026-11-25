<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\ProgressionRules;
use Maggie\Health\Service\TrainingHistory;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_next_prescription', description: 'Computes, writes nothing. Returns the next prescription for one exercise from the coded progression rules. exercise: catalogue slug; variant: normal (default), soft (one set less, 3 reps in reserve), deload (half the sets, 3 reps in reserve). Returns {prescription, reason, basedOn}: prescription = an exercise line for manage_workouts (its exercise may differ from the one asked); reason = the rule applied; basedOn = ids of the sessions read. Unknown slug or variant: {error}.')]
final class GetNextPrescriptionTool
{
    public function __construct(
        private readonly TrainingHistory $history,
        private readonly ProgressionRules $rules,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $exercise, string $variant = 'normal'): string
    {
        try {
            $user = $this->userContext->requireUser();

            return json_encode(
                $this->rules->next($exercise, $variant, $this->history->forExercise($user, $exercise), $this->history->context($user)),
                JSON_THROW_ON_ERROR,
            );
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
