<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\UseCase\PreviewProject;
use Mcp\Capability\Attribute\McpTool;

/**
 * Never completes a request by deduction: a partial one is refused with
 * `current`, what was given, so the next call can be whole.
 */
#[McpTool(name: 'preview_project', description: 'Show what adding a project would change BEFORE adding it ("et si j\'ajoutais une box à louer ?"): nothing is created or changed, the planning is only computed twice. It is computed in the current scenario (the one the user chose to follow; with none, the user\'s projects in their priority order) or in the scenario named by scenarioId (a stored scenario\'s id, or auto-rapid, auto-balanced, auto-quality); the new project comes last in that scenario\'s order. Takes the same fields as manage_projects create: name and amountCents (integer cents, > 0) required; currency (EUR by default), wellbeing (essential, high, medium by default, comfort), dateMode none (default), fixed or deadline with targetOn (YYYY-MM-DD), dependsOn (ids of the user\'s projects it waits for). Answers {scenario: {id, name, editable} or null when none is current (editable false for an automatic scenario: duplicate it to add to it), project: {name, amountCents, currency}, dates: [{projectId, name, before, after}] — the month (YYYY-MM) each existing project is bought, null when beyond the 36-month planning, without and with the new project, nDate: {before, after} — the independence date N (YYYY-MM, null when not reached within 20 years), metrics: {realCost (integer cents: what the project costs, here its amount since it has no funding source yet), availableAt (the month the new project is bought, null when beyond the planning), nImpactMonths (months N moves, positive = later, negative = earlier, null when N is not reached before or after)}}. Report the dates and the gaps as facts, never advise to add or not: the user decides. To add it for real, call manage_projects create, then manage_scenarios update (projectIds) when a scenario is current. A refused request answers error plus current (what was given): ask the user for what is missing.')]
class PreviewProjectTool
{
    public function __construct(
        private readonly PreviewProject $previewProject,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $dependsOn */
    public function __invoke(
        ?string $name = null,
        ?int $amountCents = null,
        ?string $currency = null,
        ?string $wellbeing = null,
        ?string $dateMode = null,
        ?string $targetOn = null,
        ?array $dependsOn = null,
        ?string $scenarioId = null,
    ): string {
        $given = array_filter([
            'name' => $name,
            'amountCents' => $amountCents,
            'currency' => $currency,
            'wellbeing' => $wellbeing,
            'dateMode' => $dateMode,
            'targetOn' => $targetOn,
            'dependsOn' => $dependsOn,
            'scenarioId' => $scenarioId,
        ], static fn (mixed $value): bool => null !== $value);

        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        }

        if (null === $name || null === $amountCents) {
            return self::json(['error' => 'name and amountCents are required for a preview: ask the user for what is missing.', 'current' => $given]);
        }
        if (\in_array($dateMode, ['fixed', 'deadline'], true) && null === $targetOn) {
            return self::json(['error' => "dateMode {$dateMode} needs a targetOn (YYYY-MM-DD): ask the user for the date.", 'current' => $given]);
        }
        if (null !== $targetOn && !\in_array($dateMode, ['fixed', 'deadline'], true)) {
            return self::json(['error' => 'A targetOn needs dateMode fixed (a locked date) or deadline (at the latest): ask the user which one.', 'current' => $given]);
        }

        try {
            return self::json($this->previewProject->execute(
                $user,
                $name,
                $amountCents,
                $currency ?? 'EUR',
                $wellbeing ?? 'medium',
                $dateMode ?? 'none',
                $targetOn,
                $dependsOn ?? [],
                $scenarioId,
            ));
        } catch (ScenarioNotFound|\DomainException $e) {
            return self::json(['error' => $e->getMessage(), 'current' => $given]);
        }
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
