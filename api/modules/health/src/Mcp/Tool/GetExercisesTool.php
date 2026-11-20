<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Enum\Equipment;
use Maggie\Health\Enum\MovementPattern;
use Maggie\Health\Service\ExerciseCatalog;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;

#[McpTool(name: 'get_exercises', description: 'Reads the exercise catalogue. Optional: pattern (vertical_pull, horizontal_pull, vertical_push, horizontal_push, squat, hinge, lunge, core_anterior, core_lateral), equipment (list of pull_up_bar, weighted_bag, chair, table, band, parallel_bars, wall; empty = bodyweight only), slug. Returns {exercises}: {slug, name, pattern, primaryMuscles, secondaryMuscles, kind, equipment, nervousCost, eccentric, unit (reps, seconds, meters), perSide, loadable, ladders}. Unknown value: {error}.')]
final class GetExercisesTool
{
    public function __construct(
        private readonly ExerciseCatalog $catalog,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $equipment */
    public function __invoke(
        #[Schema(enum: ['vertical_pull', 'horizontal_pull', 'vertical_push', 'horizontal_push', 'squat', 'hinge', 'lunge', 'core_anterior', 'core_lateral'])]
        ?string $pattern = null,
        ?array $equipment = null,
        ?string $slug = null,
    ): string {
        try {
            $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if (null !== $slug) {
            $exercise = $this->catalog->exercise($slug);

            return json_encode(null === $exercise ? ['error' => \sprintf('Unknown exercise "%s".', $slug)] : ['exercises' => [$exercise]], JSON_THROW_ON_ERROR);
        }
        if (null !== $pattern && null === MovementPattern::tryFrom($pattern)) {
            return json_encode(['error' => \sprintf('Unknown pattern "%s".', $pattern)], JSON_THROW_ON_ERROR);
        }
        foreach ($equipment ?? [] as $item) {
            if (null === Equipment::tryFrom((string) $item)) {
                return json_encode(['error' => \sprintf('Unknown equipment "%s".', (string) $item)], JSON_THROW_ON_ERROR);
            }
        }

        return json_encode(['exercises' => $this->catalog->exercises($pattern, $equipment)], JSON_THROW_ON_ERROR);
    }
}
