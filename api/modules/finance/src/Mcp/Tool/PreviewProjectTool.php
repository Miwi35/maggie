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
#[McpTool(name: 'preview_project', description: 'Computes the planning twice, without and with a new project, and changes nothing. scenarioId: a stored scenario id, or auto-rapid, auto-balanced, auto-quality. Same fields as manage_projects create: name and amountCents (integer cents, > 0) required; currency (default EUR); wellbeing essential|high|medium (default)|comfort; dateMode none (default)|fixed|deadline with targetOn (YYYY-MM-DD); dependsOn (project ids). Returns scenario {id, name, editable} or null; dates[] {projectId, name, before, after} (purchase month YYYY-MM, null beyond 36 months); nDate {before, after} (independence month, null beyond 20 years). A refusal returns error plus current.')]
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
