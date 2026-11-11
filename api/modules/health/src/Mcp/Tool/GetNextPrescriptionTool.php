<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\ProgressionRules;
use Maggie\Health\Service\TrainingHistory;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_next_prescription', description: 'What to prescribe next for one exercise (Santé › Sport), by the coded progression rules: never compute a progression yourself, ask this. exercise: catalogue slug (see get_exercises); variant: normal (default), soft (same step, one set less, 3 reps in reserve, an eccentric exercise replaced by the previous step) or deload (half the sets, same step, 3 reps in reserve). Returns {prescription, reason, basedOn}: prescription is an exercise line ready for manage_workouts {exercise, name, unit, step, sets, target, targetMin, targetMax, loadKg, restSeconds, tempo, targetRir, cue} — its exercise can differ from the one asked (step_up, step_down, stagnation_variant, placement); reason says why, in a word: start, placement, more reps, more load, a tempo, step up or down, a lighter load, a softer variant, less volume, keep, soft or deload (say it in a sentence, never the rule); basedOn the ids of the sessions the rule read. Unknown slug or variant: {error}.')]
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
