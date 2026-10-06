<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Message\CreateEnvelopeCommand;
use Maggie\Finance\Message\DeleteEnvelopeCommand;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\GetBudgetStatus;
use Maggie\Finance\UseCase\RollOverEnvelopes;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_envelopes', description: 'List, set, update or delete budget envelopes, and read the budget status of a period. An envelope budgets one category for one period: mode monthly needs year + month (1-12), mode annual needs year only. amountCents is a positive integer amount in cents. The set action creates the envelope or updates the existing one for that category and period. The status action reports, per envelope, what is spent, committed, planned and to arbitrate, plus what is consumed (spent + committed), remaining and available (remaining minus planned); it defaults to the current month. The rollover action copies the envelopes of one period onto another (fromYear/fromMonth to year/month), keeping the same amounts unless useActualSpending is true, in which case each new envelope is budgeted on what its category actually consumed; envelopes already set on the target period are left untouched.')]
class ManageEnvelopesTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly OwnedReferenceResolver $references,
        private readonly GetBudgetStatus $getBudgetStatus,
        private readonly RollOverEnvelopes $rollOverEnvelopes,
        private readonly McpUserContext $userContext,
    ) {
    }

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
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'set' => $this->set($categoryId, $amountCents, $mode, $year, $month, $currency),
                'update' => $this->update($envelopeId, $categoryId, $amountCents, $mode, $year, $month, $currency),
                'status' => $this->status($year, $month),
                'rollover' => $this->rollover($fromYear, $fromMonth, $year, $month, $useActualSpending),
                'delete' => $this->delete($envelopeId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, set, update, status, rollover, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        } catch (\ValueError $e) {
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

    private function set(?string $categoryId, ?int $amountCents, ?string $mode, ?int $year, ?int $month, ?string $currency): string
    {
        if (null === $categoryId || null === $amountCents) {
            return json_encode(['error' => 'categoryId and amountCents are required for set.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        try {
            $category = $this->references->category($categoryId, $user);
        } catch (\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $budgetMode = BudgetMode::from($mode ?? BudgetMode::Monthly->value);
        $now = new \DateTimeImmutable();
        $year ??= (int) $now->format('Y');
        $resolvedMonth = BudgetMode::Monthly === $budgetMode ? ($month ?? (int) $now->format('n')) : null;

        $existing = $this->envelopeRepository->findOneForPeriod($category, $budgetMode, $year, $resolvedMonth);

        if (null !== $existing) {
            return $this->update((string) $existing->getId(), null, $amountCents, null, null, null, $currency);
        }

        $stamped = $this->bus->dispatch(new CreateEnvelopeCommand(
            userId: (string) $user->getId(),
            categoryId: $categoryId,
            amountCents: $amountCents,
            year: $year,
            mode: $budgetMode->value,
            month: $resolvedMonth,
            currency: $currency ?? 'EUR',
        ));

        /** @var Envelope $envelope */
        $envelope = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'envelope' => $this->serialize($envelope),
        ], JSON_THROW_ON_ERROR);
    }

    private function update(?string $envelopeId, ?string $categoryId, ?int $amountCents, ?string $mode, ?int $year, ?int $month, ?string $currency): string
    {
        if (null === $envelopeId) {
            return json_encode(['error' => 'envelopeId is required for update.'], JSON_THROW_ON_ERROR);
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

        return json_encode($this->getBudgetStatus->execute(
            $user,
            $year ?? (int) $now->format('Y'),
            $month ?? (int) $now->format('n'),
        ), JSON_THROW_ON_ERROR);
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
            'categoryId' => (string) $envelope->getCategory()->getId(),
            'mode' => $envelope->getMode()->value,
            'amountCents' => $envelope->getAmountCents(),
            'currency' => $envelope->getCurrency(),
            'year' => $envelope->getYear(),
            'month' => $envelope->getMonth(),
        ];
    }
}
