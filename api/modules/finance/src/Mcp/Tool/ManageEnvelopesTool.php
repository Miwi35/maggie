<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Category;
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

#[McpTool(name: 'manage_envelopes', description: 'Budget envelopes: one category for one period, amountCents a positive integer in cents. mode monthly needs year + month (1-12), annual needs year, one_off is an event: name + amountCents, no category or period (spends join it via manage_transactions oneOffEnvelopeId). Actions: list; set (categoryId, amountCents; creates or updates that category\'s envelope; monthly with year + month and toYear + toMonth together sets every month of that range, all or nothing); update (envelopeId + fields); delete; status (year, month, default current: per envelope spent, committed, planned, consumed, remaining, available; outsideMonthlyBudget lists annual and oneOff ones); rollover (fromYear/fromMonth to year/month; same amounts, or actual spending if useActualSpending; existing targets untouched); split (envelopeId of the mother, children [{envelopeId, amountCents}] among its direct sub-category envelopes; over-allocation is refused). seasonalAmounts (monthly, set/update): [{fromMonth, toMonth, amountCents}], months 1-12; amountCents covers the other months; replaces all seasons, [] removes them.')]
class ManageEnvelopesTool
{
    private const MAX_RANGE_MONTHS = 60;

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
        ?int $toYear = null,
        ?int $toMonth = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'set' => $this->set($categoryId, $amountCents, $mode, $year, $month, $currency, $name, $seasonalAmounts, $toYear, $toMonth),
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
    private function set(?string $categoryId, ?int $amountCents, ?string $mode, ?int $year, ?int $month, ?string $currency, ?string $name, ?array $seasonalAmounts, ?int $toYear = null, ?int $toMonth = null): string
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

        if (null !== $toYear || null !== $toMonth) {
            return $this->setRange($category, $amountCents, $budgetMode, $year, $month, $toYear, $toMonth, $currency, $seasons);
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

    /** @param list<array{fromMonth: int, toMonth: int, amountCents: int}>|null $seasons */
    private function setRange(Category $category, int $amountCents, BudgetMode $mode, ?int $year, ?int $month, ?int $toYear, ?int $toMonth, ?string $currency, ?array $seasons): string
    {
        $refuse = fn (string $message): string => json_encode([
            'error' => $message,
            'envelopes' => array_map(
                fn (Envelope $e) => $this->serialize($e),
                array_values(array_filter(
                    $this->envelopeRepository->findByUser($this->userContext->requireUser()),
                    static fn (Envelope $e): bool => BudgetMode::Monthly === $e->getMode() && null !== $e->getCategory() && $e->getCategory()->getId()->equals($category->getId()),
                )),
            ),
        ], JSON_THROW_ON_ERROR);

        if (BudgetMode::Monthly !== $mode) {
            return $refuse('Only a monthly envelope can be set over a range.');
        }
        if (null === $year || null === $month || null === $toYear || null === $toMonth) {
            return $refuse('A range needs year, month, toYear and toMonth together.');
        }
        if ($month < 1 || $month > 12 || $toMonth < 1 || $toMonth > 12) {
            return $refuse('month and toMonth are between 1 and 12.');
        }
        $first = $year * 12 + $month;
        $last = $toYear * 12 + $toMonth;
        if ($last < $first) {
            return $refuse('The range ends before it starts.');
        }
        if ($last - $first >= self::MAX_RANGE_MONTHS) {
            return $refuse(sprintf('A range is %d months at most.', self::MAX_RANGE_MONTHS));
        }

        $envelopes = [];
        for ($index = $first; $index <= $last; ++$index) {
            $result = json_decode($this->set((string) $category->getId(), $amountCents, $mode->value, intdiv($index - 1, 12), ($index - 1) % 12 + 1, $currency, null, $seasons), true, 512, JSON_THROW_ON_ERROR);
            if (!isset($result['envelope'])) {
                return json_encode($result, JSON_THROW_ON_ERROR);
            }
            $envelopes[] = $result['envelope'];
        }

        return json_encode(['success' => true, 'envelopes' => $envelopes], JSON_THROW_ON_ERROR);
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
