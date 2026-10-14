<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Exception\EnvelopeSplitException;
use Maggie\Finance\Message\CreateEnvelopeCommand;
use Maggie\Finance\Message\DeleteEnvelopeCommand;
use Maggie\Finance\Message\SplitEnvelopeCommand;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\SeasonalAmounts;
use Maggie\Finance\UseCase\GetBudgetStatus;
use Maggie\Finance\UseCase\GetEnvelopeSplit;
use Maggie\Finance\UseCase\RollOverEnvelopes;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'manage_envelopes', description: 'List, set, update, split or delete budget envelopes, and read the budget status of a period. An envelope budgets one category for one period: mode monthly needs year + month (1-12), mode annual needs year only (a calendar year, kept apart from the month\'s budget). Mode one_off is an event (Hellfest 2026, a trip) with a name and an amount but neither category nor period: set creates it from name + amountCents, and a spend is attributed to it by hand with manage_transactions (oneOffEnvelopeId), after which it keeps its category but counts in the one-off envelope only. amountCents is a positive integer amount in cents. The set action creates the envelope or updates the existing one for that category and period. The status action reports, per envelope, what is spent, committed, planned and to arbitrate, plus what is consumed (spent + committed), remaining and available (remaining minus planned); it defaults to the current month. Each envelope counts the transactions of its category and of every sub-category below it (an envelope is never compulsory: a spend on Loisirs › Bars › Obsydian counts in the envelope of Obsydian and in that of Loisirs, whichever of them exist), carries categoryPath and parentEnvelopeId (the envelope of its closest ancestor category, null for a root), and the totals only add the root envelopes. budgets and the totals are the monthly envelopes only; outsideMonthlyBudget lists apart the annual envelopes of the year (annual) and every one-off envelope with what was attributed to it (oneOff). The rollover action copies the envelopes of one period onto another (fromYear/fromMonth to year/month), keeping the same amounts unless useActualSpending is true, in which case each new envelope is budgeted on what its category actually consumed; envelopes already set on the target period are left untouched. The split action shares the amount of a mother envelope (envelopeId) between its daughters — the envelopes of its direct sub-categories for the same period — in one go: children is a list of {envelopeId, amountCents}; a daughter not listed keeps her amount, and what the daughters do not hold stays free in the mother (status reports allocatedToChildrenCents and freeCents for a mother). A split whose daughters would hold more than the mother is refused and changes nothing; the answer carries the current state. seasonalAmounts (monthly only, on set and update) gives a monthly envelope an amount per stretch of the year: a list of {fromMonth, toMonth, amountCents}, months 1-12, a season may cross the new year (Cinéma 45 € from November to February is {fromMonth: 11, toMonth: 2, amountCents: 4500}), two seasons never share a month; amountCents is then the amount of the months no season covers (20 € the rest of the year), a month a season covers is budgeted at the season\'s amount (monthAmountCents, and amountCents in status, whose season names it), and rollover carries the seasons over so each new month lands at its season\'s amount. Passing seasonalAmounts replaces every season; [] removes them.')]
class ManageEnvelopesTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly OwnedReferenceResolver $references,
        private readonly GetBudgetStatus $getBudgetStatus,
        private readonly RollOverEnvelopes $rollOverEnvelopes,
        private readonly GetEnvelopeSplit $getEnvelopeSplit,
        private readonly McpUserContext $userContext,
    ) {
    }

    /**
     * @param list<array{fromMonth: int, toMonth: int, amountCents: int}>|null $seasonalAmounts
     * @param array<mixed>|null                                                $children
     */
    public function __invoke(
        string $action,
        ?string $envelopeId = null,
        ?string $categoryId = null,
        ?int $amountCents = null,
        ?string $mode = null,
        ?int $year = null,
        ?int $month = null,
        ?string $currency = null,
        ?int $fromYear = null,
        ?int $fromMonth = null,
        ?bool $useActualSpending = null,
        ?string $name = null,
        ?array $seasonalAmounts = null,
        ?array $children = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'set' => $this->set($categoryId, $amountCents, $mode, $year, $month, $currency, $name, $seasonalAmounts),
                'update' => $this->update($envelopeId, $categoryId, $amountCents, $mode, $year, $month, $currency, $name, $seasonalAmounts),
                'status' => $this->status($year, $month),
                'rollover' => $this->rollover($fromYear, $fromMonth, $year, $month, $useActualSpending),
                'split' => $this->split($envelopeId, $children),
                'delete' => $this->delete($envelopeId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, set, update, status, rollover, split, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        } catch (\ValueError|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        $envelopes = $this->envelopeRepository->findByUser($user);

        return json_encode([
            'envelopes' => array_map(fn (Envelope $e) => $this->serialize($e), $envelopes),
        ], JSON_THROW_ON_ERROR);
    }

    /** @param list<array{fromMonth: int, toMonth: int, amountCents: int}>|null $seasonalAmounts */
    private function set(?string $categoryId, ?int $amountCents, ?string $mode, ?int $year, ?int $month, ?string $currency, ?string $name, ?array $seasonalAmounts): string
    {
        $budgetMode = BudgetMode::from($mode ?? BudgetMode::Monthly->value);
        $seasons = null === $seasonalAmounts ? null : SeasonalAmounts::normalize($seasonalAmounts);

        if (null !== $seasons && [] !== $seasons && BudgetMode::Monthly !== $budgetMode) {
            return json_encode(['error' => 'Only a monthly envelope has seasonal amounts.'], JSON_THROW_ON_ERROR);
        }

        if (BudgetMode::OneOff === $budgetMode) {
            return $this->createOneOff($categoryId, $amountCents, $year, $month, $currency, $name);
        }

        if (null === $categoryId || null === $amountCents) {
            return json_encode(['error' => 'categoryId and amountCents are required for set.'], JSON_THROW_ON_ERROR);
        }
        if (null !== $name) {
            return json_encode(['error' => 'Only a one-off envelope has a name: the others are named by their category.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        try {
            $category = $this->references->category($categoryId, $user);
        } catch (\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $now = new \DateTimeImmutable();
        $year ??= (int) $now->format('Y');
        $resolvedMonth = BudgetMode::Monthly === $budgetMode ? ($month ?? (int) $now->format('n')) : null;

        $existing = $this->envelopeRepository->findOneForPeriod($category, $budgetMode, $year, $resolvedMonth);

        if (null !== $existing) {
            return $this->update((string) $existing->getId(), null, $amountCents, null, null, null, $currency, null, $seasons);
        }

        $stamped = $this->bus->dispatch(new CreateEnvelopeCommand(
            userId: (string) $user->getId(),
            categoryId: $categoryId,
            amountCents: $amountCents,
            year: $year,
            mode: $budgetMode->value,
            month: $resolvedMonth,
            currency: $currency ?? 'EUR',
            seasonalAmounts: $seasons ?? [],
        ));

        /** @var Envelope $envelope */
        $envelope = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'envelope' => $this->serialize($envelope),
        ], JSON_THROW_ON_ERROR);
    }

    private function createOneOff(?string $categoryId, ?int $amountCents, ?int $year, ?int $month, ?string $currency, ?string $name): string
    {
        if (null === $name || '' === trim($name) || null === $amountCents) {
            return json_encode(['error' => 'name and amountCents are required to set a one-off envelope.'], JSON_THROW_ON_ERROR);
        }
        if (mb_strlen(trim($name)) > Envelope::NAME_MAX_LENGTH) {
            return json_encode(['error' => sprintf('name is %d characters at most.', Envelope::NAME_MAX_LENGTH)], JSON_THROW_ON_ERROR);
        }
        if (null !== $categoryId || null !== $year || null !== $month) {
            return json_encode(['error' => 'A one-off envelope has neither category nor period: attribute spends to it with manage_transactions.'], JSON_THROW_ON_ERROR);
        }

        $stamped = $this->bus->dispatch(new CreateEnvelopeCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            categoryId: null,
            amountCents: $amountCents,
            year: null,
            mode: BudgetMode::OneOff->value,
            currency: $currency ?? 'EUR',
            name: $name,
        ));

        /** @var Envelope $envelope */
        $envelope = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'envelope' => $this->serialize($envelope),
        ], JSON_THROW_ON_ERROR);
    }

    /** @param list<array{fromMonth: int, toMonth: int, amountCents: int}>|null $seasonalAmounts */
    private function update(?string $envelopeId, ?string $categoryId, ?int $amountCents, ?string $mode, ?int $year, ?int $month, ?string $currency, ?string $name, ?array $seasonalAmounts): string
    {
        if (null === $envelopeId) {
            return json_encode(['error' => 'envelopeId is required for update.'], JSON_THROW_ON_ERROR);
        }
        if (null !== $name && mb_strlen(trim($name)) > Envelope::NAME_MAX_LENGTH) {
            return json_encode(['error' => sprintf('name is %d characters at most.', Envelope::NAME_MAX_LENGTH)], JSON_THROW_ON_ERROR);
        }

        $stamped = $this->bus->dispatch(new UpdateEnvelopeCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            envelopeId: $envelopeId,
            categoryId: $categoryId,
            amountCents: $amountCents,
            year: $year,
            mode: $mode,
            month: $month,
            currency: $currency,
            name: $name,
            seasonalAmounts: null === $seasonalAmounts ? null : SeasonalAmounts::normalize($seasonalAmounts),
        ));

        /** @var Envelope $envelope */
        $envelope = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'envelope' => $this->serialize($envelope),
        ], JSON_THROW_ON_ERROR);
    }

    private function status(?int $year, ?int $month): string
    {
        $user = $this->userContext->requireUser();

        $now = new \DateTimeImmutable();
        $year ??= (int) $now->format('Y');
        $month ??= (int) $now->format('n');

        if ($month < 1 || $month > 12) {
            return json_encode(['error' => 'month must be between 1 and 12.'], JSON_THROW_ON_ERROR);
        }

        if ($year < 2000 || $year > 2100) {
            return json_encode(['error' => 'year must be between 2000 and 2100.'], JSON_THROW_ON_ERROR);
        }

        return json_encode($this->getBudgetStatus->execute($user, $year, $month), JSON_THROW_ON_ERROR);
    }

    private function rollover(?int $fromYear, ?int $fromMonth, ?int $year, ?int $month, ?bool $useActualSpending): string
    {
        if (null === $fromYear || null === $year) {
            return json_encode(['error' => 'fromYear and year are required for rollover.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        return json_encode([
            'success' => true,
        ] + $this->rollOverEnvelopes->execute(
            $user,
            $fromYear,
            $fromMonth,
            $year,
            $month,
            $useActualSpending ?? false,
        ), JSON_THROW_ON_ERROR);
    }

    /** @param array<mixed>|null $children */
    private function split(?string $envelopeId, ?array $children): string
    {
        if (null === $envelopeId || null === $children) {
            return json_encode(['error' => 'envelopeId and children (a list of {envelopeId, amountCents}) are required for split.'], JSON_THROW_ON_ERROR);
        }

        $amounts = [];
        foreach ($children as $child) {
            $childId = \is_array($child) ? ($child['envelopeId'] ?? null) : null;
            $amountCents = \is_array($child) ? ($child['amountCents'] ?? null) : null;

            if (!\is_string($childId) || !Ulid::isValid($childId) || !\is_int($amountCents) || $amountCents < 0) {
                return json_encode(['error' => 'Each child needs an envelopeId and amountCents, a positive integer.'], JSON_THROW_ON_ERROR);
            }

            $key = (new Ulid($childId))->toBase32();
            if (isset($amounts[$key])) {
                return json_encode(['error' => 'An envelope can only appear once in children.'], JSON_THROW_ON_ERROR);
            }
            $amounts[$key] = $amountCents;
        }

        $user = $this->userContext->requireUser();
        $mother = Ulid::isValid($envelopeId) ? $this->envelopeRepository->findOneBy(['id' => $envelopeId, 'user' => $user]) : null;
        if (null === $mother) {
            return json_encode(['error' => "Envelope not found: {$envelopeId}"], JSON_THROW_ON_ERROR);
        }

        try {
            $this->bus->dispatch(new SplitEnvelopeCommand(
                userId: (string) $user->getId(),
                envelopeId: (string) $mother->getId(),
                amountsByEnvelopeId: $amounts,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof EnvelopeSplitException) {
                throw $e;
            }

            return json_encode(['error' => $cause->getMessage()] + $this->getEnvelopeSplit->execute($mother), JSON_THROW_ON_ERROR);
        }

        return json_encode(['success' => true] + $this->getEnvelopeSplit->execute($mother), JSON_THROW_ON_ERROR);
    }

    private function delete(?string $envelopeId): string
    {
        if (null === $envelopeId) {
            return json_encode(['error' => 'envelopeId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteEnvelopeCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            envelopeId: $envelopeId,
        ));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(Envelope $envelope): array
    {
        return [
            'id' => (string) $envelope->getId(),
            'categoryId' => null !== $envelope->getCategory() ? (string) $envelope->getCategory()->getId() : null,
            'name' => $envelope->getName(),
            'mode' => $envelope->getMode()->value,
            'amountCents' => $envelope->getAmountCents(),
            'seasonalAmounts' => $envelope->getSeasonalAmounts(),
            // What the envelope budgets for its month: the season covering it, else amountCents.
            'monthAmountCents' => $envelope->getMonthAmountCents(),
            'currency' => $envelope->getCurrency(),
            'year' => $envelope->getYear(),
            'month' => $envelope->getMonth(),
        ];
    }
}
