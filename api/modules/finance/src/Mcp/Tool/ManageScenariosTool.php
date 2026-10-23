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
#[McpTool(name: 'manage_scenarios', description: 'List, read, create, duplicate, edit, delete, compare the user\'s project scenarios, make one current, or file a suggestion as a draft. A scenario is a name, a selection and an ORDER of the user\'s existing projects (projectIds, first = first priority; the projects themselves are never changed), early repayments simulated on the signed loans (repayments: [{loanId, month "YYYY-MM", amountCents integer cents or null for the whole capital, effect optional: "duration" shortens the loan, "payment" lowers the payment (amortizing loans only), absent follows the loan\'s offer}]; a repayment sits in the scenario\'s list at its month, move it by changing the month), placementIds (simulated placements it refers to) and monthlySaving (integer cents a month that replaces the measured saving capacity; omit to keep it). Read back with id, name, kind (user, auto, advisor), status (draft, saved), current (true for at most one scenario, the one the Dashboard, the treasury, the home and the date N follow; nothing is current until the user chooses), projects [{projectId, position}], repayments, placements, monthlySaving, nDate (YYYY-MM or null: the independence date N, not reached within 20 years) and metrics {projectsDoneBy (month when the last project is bought), debtsEndDate, interestPaid (integer cents), wellbeing (higher = more comfort projects bought early)}. Three automatic scenarios are always offered as drafts, ids auto-rapid (earliest N, postpones the comfort projects that delay N by more than 3 months), auto-balanced (earliest N while protecting the essential and high-priority projects) and auto-quality (highest wellbeing score); they are not edited: duplicate one, then edit the copy. Actions: list; get with scenarioId (adds the coverage curve, milestones, the month-by-month schedule, each repayment\'s effect (repaymentEffects: loanName, effect, immobilizedCents = the capital repaid, penaltyCents, interestSavedCents = the remaining interest it spares, realGainCents = interestSavedCents - penaltyCents, freedMonthlyCents = the payment freed from the next month and added to the capacity, nImpactMonths = months it moves the date N, negative = sooner), the placements\' simulated figures and the timeline events); create with name and projectIds (both required; repayments, placementIds, monthlySaving optional); duplicate with scenarioId (name optional); update with scenarioId and only the fields to change (resetMonthlySaving=true goes back to the measured capacity); delete with scenarioId; make_current with scenarioId (the previous current one is let go; this changes what the Dashboard and the date N show, do it only when the user asks); clear_current (nothing is current any more); compare with scenarioIds (at least two, the first is the reference) answers each scenario\'s curve and metrics and each one\'s date N against the reference in plain words (« N 9 mois plus tôt que « A » »); suggest like create, filed as a draft of kind advisor for the user to look at, never current. timeline (optional scenarioId = a scenario to draw beside the current one, the first being the planning the Dashboard follows) answers the 20-year frise: events month by month up to independence (the same events as get, category projet/dette/investissement/indépendance/fiscal/matelas) for the current scenario and the compared one, the gap of N in plain words and the assumptions to state (matelas intact, revenus stables, sans vacance locative ni imprévu). When comparing, report the figures and the gaps as they are: never say a scenario is better, best or recommended, the user decides. Nothing is ever deduced: a refused request answers error plus current (what was given, or the scenario as it is now): ask the user for what is missing.')]
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
