<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\Message\ChangeScenarioCommand;
use Maggie\Finance\UseCase\GetFinanceTimeline;
use Maggie\Finance\UseCase\GetScenarios;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Never completes a request by deduction: a partial one is refused with
 * `current`, what was given or what the scenario is, so the next call can be
 * whole. Facts only: no scenario is ever called better or recommended.
 */
#[McpTool(name: 'manage_scenarios', description: 'A scenario orders projects with repayments and savings to compare trajectories; a trajectory question goes to the advisor (delegate). Project scenarios: name, ordered projectIds (first = first priority), early repayments, placementIds, monthlySaving (integer cents a month replacing the measured capacity). repayments: [{loanId, month YYYY-MM, amountCents or null = whole capital, effect duration|payment}]. Actions: list; get (scenarioId; adds curve, events); create (name, projectIds); duplicate (scenarioId, optional name); update (scenarioId + fields; resetMonthlySaving=true restores the measured capacity); delete; make_current (scenarioId; the single current scenario drives the Dashboard and treasury); clear_current; compare (scenarioIds, 2+, first = reference); timeline (optional scenarioId); suggest (like create, draft of kind advisor, never current). Returns id, name, kind (user, auto, advisor), status (draft, saved), current, projects, monthlySaving, nDate (YYYY-MM). auto-* scenarios are read-only: duplicate, then edit. A refusal returns error plus current.')]
class ManageScenariosTool
{
    private const ACTIONS = 'list, get, create, duplicate, update, delete, make_current, clear_current, compare, timeline or suggest';

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly GetScenarios $getScenarios,
        private readonly GetFinanceTimeline $getFinanceTimeline,
        private readonly McpUserContext $userContext,
    ) {
    }

    /**
     * @param list<string>|null               $projectIds
     * @param list<array<string, mixed>>|null $repayments
     * @param list<string>|null               $placementIds
     * @param array<mixed>|null               $scenarioIds
     */
    public function __invoke(
        string $action,
        ?string $scenarioId = null,
        ?array $scenarioIds = null,
        ?string $name = null,
        ?array $projectIds = null,
        ?array $repayments = null,
        ?array $placementIds = null,
        ?int $monthlySaving = null,
        ?bool $resetMonthlySaving = null,
    ): string {
        $fields = self::given(compact('name', 'projectIds', 'repayments', 'placementIds', 'monthlySaving'));
        if (true === $resetMonthlySaving) {
            if (null !== $monthlySaving) {
                return self::json(['error' => 'monthlySaving and resetMonthlySaving contradict each other: give one.', 'current' => $fields]);
            }
            $fields['monthlySaving'] = null;
        }

        try {
            return match ($action) {
                'list' => $this->list(),
                'get' => $this->get($scenarioId),
                'create', 'suggest' => $this->create($action, $fields),
                'duplicate' => $this->write(ChangeScenarioCommand::DUPLICATE, $scenarioId, $fields),
                'update' => $this->update($scenarioId, $fields),
                'delete' => $this->write(ChangeScenarioCommand::DELETE, $scenarioId, []),
                'make_current' => $this->write(ChangeScenarioCommand::MAKE_CURRENT, $scenarioId, []),
                'clear_current' => $this->write(ChangeScenarioCommand::CLEAR_CURRENT, null, []),
                'compare' => $this->compare($scenarioIds),
                'timeline' => $this->timeline($scenarioId),
                default => self::json(['error' => "Unknown action: {$action}. Use ".self::ACTIONS.'.']),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return $cause instanceof \DomainException ? self::json(['error' => $cause->getMessage()]) : throw $e;
        }
    }

    private function list(): string
    {
        return self::json($this->getScenarios->list($this->userContext->requireUser()));
    }

    private function get(?string $scenarioId): string
    {
        if (null === $scenarioId) {
            return self::json(['error' => 'scenarioId is required for get.']);
        }

        $scenario = $this->getScenarios->one($this->userContext->requireUser(), $scenarioId);

        return null === $scenario
            ? self::json(['error' => "Scenario not found: {$scenarioId}"])
            : self::json(['scenario' => self::yearly($scenario)]);
    }

    /** @param array<string, mixed> $fields */
    private function create(string $action, array $fields): string
    {
        $missing = array_values(array_diff(['name', 'projectIds'], array_keys($fields)));
        if ([] !== $missing) {
            return self::json(['error' => "Missing for {$action}: ".implode(', ', $missing).'. Ask the user, never assume.', 'current' => $fields]);
        }

        return $this->write('suggest' === $action ? ChangeScenarioCommand::SUGGEST : ChangeScenarioCommand::CREATE, null, $fields);
    }

    /** @param array<string, mixed> $fields */
    private function update(?string $scenarioId, array $fields): string
    {
        if (null === $scenarioId) {
            return self::json(['error' => 'scenarioId is required for update.']);
        }
        if ([] === $fields) {
            return self::json(['error' => 'Nothing to change: give name, projectIds, repayments, placementIds, monthlySaving or resetMonthlySaving.', 'current' => $this->light($scenarioId)]);
        }

        return $this->write(ChangeScenarioCommand::UPDATE, $scenarioId, $fields);
    }

    /** @param array<mixed>|null $scenarioIds */
    private function compare(?array $scenarioIds): string
    {
        $user = $this->userContext->requireUser();
        if (null === $scenarioIds) {
            return self::json(['error' => 'scenarioIds is required for compare: at least two, the first is the reference.']);
        }

        try {
            if (!array_is_list($scenarioIds) || [] !== array_filter($scenarioIds, static fn (mixed $id): bool => !\is_string($id))) {
                throw new \DomainException('scenarioIds is a list of scenario ids.');
            }
            $comparison = $this->getScenarios->compare($user, $scenarioIds);
        } catch (\DomainException $e) {
            return self::json(['error' => $e->getMessage(), 'current' => ['scenarioIds' => $scenarioIds]]);
        }
        $comparison['scenarios'] = array_map(self::yearly(...), $comparison['scenarios']);

        return self::json($comparison);
    }

    private function timeline(?string $compareId): string
    {
        try {
            return self::json($this->getFinanceTimeline->execute($this->userContext->requireUser(), $compareId));
        } catch (\DomainException $e) {
            return self::json(['error' => $e->getMessage()]);
        }
    }

    /** @param array<string, mixed> $fields */
    private function write(string $action, ?string $scenarioId, array $fields): string
    {
        $needsId = !\in_array($action, [ChangeScenarioCommand::CREATE, ChangeScenarioCommand::SUGGEST, ChangeScenarioCommand::CLEAR_CURRENT], true);
        if ($needsId && null === $scenarioId) {
            return self::json(['error' => "scenarioId is required for {$action}."]);
        }

        $user = $this->userContext->requireUser();
        $current = null !== $scenarioId ? $this->light($scenarioId) : $fields;

        try {
            $id = $this->bus->dispatch(new ChangeScenarioCommand(
                userId: (string) $user->getId(),
                action: $action,
                scenarioId: $scenarioId,
                fields: $fields,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return self::json(['error' => $cause->getMessage()] + ($cause instanceof ScenarioNotFound ? [] : ['current' => $current]));
        }

        $answer = ['success' => true];
        if (\is_string($id)) {
            $answer['scenario'] = $this->light($id);
        }

        return self::json($answer);
    }

    /** @return array<string, mixed>|null */
    private function light(string $scenarioId): ?array
    {
        $scenario = $this->getScenarios->one($this->userContext->requireUser(), $scenarioId, false);
        if (null === $scenario) {
            return null;
        }
        unset($scenario['curve'], $scenario['milestones'], $scenario['schedule'], $scenario['repaymentEffects'], $scenario['placementFigures']);

        return $scenario;
    }

    /**
     * The coverage curve one point a year: enough for Maggie to tell.
     *
     * @param array<string, mixed> $scenario
     *
     * @return array<string, mixed>
     */
    private static function yearly(array $scenario): array
    {
        if (isset($scenario['curve']) && \is_array($scenario['curve'])) {
            $scenario['curve'] = array_values(array_filter(
                $scenario['curve'],
                static fn (array $point): bool => 1 === (int) substr((string) $point['month'], 5, 2),
            ));
        }

        return $scenario;
    }

    /**
     * @param array<string, mixed> $values
     *
     * @return array<string, mixed>
     */
    private static function given(array $values): array
    {
        return array_filter($values, static fn (mixed $value): bool => null !== $value);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
