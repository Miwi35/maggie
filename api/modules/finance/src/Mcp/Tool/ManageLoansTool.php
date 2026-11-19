<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\CreateLoanCommand;
use Maggie\Finance\Message\DeleteLoanCommand;
use Maggie\Finance\Message\UpdateLoanCommand;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\UseCase\GetDebtTimeline;
use Maggie\Finance\UseCase\SimulateEarlyRepayment;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

/**
 * A refused create or update answers `error` plus `current` — what was given,
 * or the loan as it is now — so the next call can be built on it (MAG-522).
 */
#[McpTool(name: 'manage_loans', description: 'Actions: list, create, update, delete, timeline, early_repayment. Integer cents, rates basis points, ISO dates. create needs name, principalRemainingCents, monthlyPaymentCents. clear lists fields to empty. type: amortizing_fixed (default); amortizing_variable (needs referenceIndex, marginBasisPoints; optional rateCapBasisPoints, rateFloorBasisPoints); in_fine, bridge (need durationMonths); stepped (needs steps [{fromMonth from 1, monthlyPaymentCents}]); deferred_partial, deferred_total (need deferralMonths > 0). Optional: periodicity (monthly, quarterly, semiannual, annual), prepaymentPenaltyMode (legal_cap default, fixed needs prepaymentPenaltyFixedCents, exempt), partialRepaymentEffect (shorten, lower_payment). projected = true: unsigned loan. timeline: horizonMonths. early_repayment: loanId, optional amountCents; returns penaltyCents, realGainCents. An invalid or missing condition is refused with error plus current.')]
class ManageLoansTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly LoanRepository $loanRepository,
        private readonly GetDebtTimeline $getDebtTimeline,
        private readonly SimulateEarlyRepayment $simulateEarlyRepayment,
        private readonly McpUserContext $userContext,
    ) {
    }

    /**
     * @param list<array{fromMonth: int, monthlyPaymentCents: int}>|null $steps
     * @param list<string>|null                                          $clear
     */
    public function __invoke(
        string $action,
        ?string $loanId = null,
        ?string $name = null,
        ?int $principalRemainingCents = null,
        ?int $monthlyPaymentCents = null,
        ?int $annualRateBasisPoints = null,
        ?string $lender = null,
        ?int $priority = null,
        ?string $currency = null,
        ?string $type = null,
        ?bool $projected = null,
        ?int $initialAmountCents = null,
        ?int $aprBasisPoints = null,
        ?int $durationMonths = null,
        ?string $firstPaymentOn = null,
        ?string $periodicity = null,
        ?int $insuranceRateBasisPoints = null,
        ?int $insuranceMonthlyCents = null,
        ?int $feesCents = null,
        ?string $referenceIndex = null,
        ?int $marginBasisPoints = null,
        ?int $rateCapBasisPoints = null,
        ?int $rateFloorBasisPoints = null,
        ?array $steps = null,
        ?int $deferralMonths = null,
        ?bool $earlyRepaymentAllowed = null,
        ?string $prepaymentPenaltyMode = null,
        ?int $prepaymentPenaltyFixedCents = null,
        ?int $minPartialRepaymentCents = null,
        ?string $partialRepaymentEffect = null,
        ?int $noticeDays = null,
        ?int $amountCents = null,
        ?int $horizonMonths = null,
        ?array $clear = null,
    ): string {
        $details = array_filter([
            'type' => $type,
            'projected' => $projected,
            'initialAmountCents' => $initialAmountCents,
            'aprBasisPoints' => $aprBasisPoints,
            'durationMonths' => $durationMonths,
            'firstPaymentOn' => $firstPaymentOn,
            'periodicity' => $periodicity,
            'insuranceRateBasisPoints' => $insuranceRateBasisPoints,
            'insuranceMonthlyCents' => $insuranceMonthlyCents,
            'feesCents' => $feesCents,
            'referenceIndex' => $referenceIndex,
            'marginBasisPoints' => $marginBasisPoints,
            'rateCapBasisPoints' => $rateCapBasisPoints,
            'rateFloorBasisPoints' => $rateFloorBasisPoints,
            'steps' => $steps,
            'deferralMonths' => $deferralMonths,
            'earlyRepaymentAllowed' => $earlyRepaymentAllowed,
            'prepaymentPenaltyMode' => $prepaymentPenaltyMode,
            'prepaymentPenaltyFixedCents' => $prepaymentPenaltyFixedCents,
            'minPartialRepaymentCents' => $minPartialRepaymentCents,
            'partialRepaymentEffect' => $partialRepaymentEffect,
            'noticeDays' => $noticeDays,
        ], static fn (mixed $value) => null !== $value);

        $base = [
            'name' => $name,
            'principalRemainingCents' => $principalRemainingCents,
            'monthlyPaymentCents' => $monthlyPaymentCents,
            'annualRateBasisPoints' => $annualRateBasisPoints,
            'lender' => $lender,
            'priority' => $priority,
            'currency' => $currency,
        ];

        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($base, $details),
                'update' => $this->update($loanId, $base, $details, $clear ?? []),
                'delete' => $this->delete($loanId),
                'timeline' => $this->timeline($horizonMonths),
                'early_repayment' => $this->earlyRepayment($loanId, $amountCents),
                default => self::json(['error' => "Unknown action: {$action}. Use list, create, update, delete, timeline or early_repayment."]),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return self::json(['error' => $cause->getMessage()]);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        return self::json([
            'loans' => array_map(
                static fn (Loan $loan) => self::serialize($loan),
                $this->loanRepository->findByUser($user),
            ),
        ]);
    }

    /**
     * @param array<string, mixed> $base
     * @param array<string, mixed> $details
     */
    private function create(array $base, array $details): string
    {
        $given = array_filter($base, static fn (mixed $value) => null !== $value) + $details;

        if (null === $base['name'] || null === $base['principalRemainingCents'] || null === $base['monthlyPaymentCents']) {
            return self::json(['error' => 'name, principalRemainingCents and monthlyPaymentCents are required for create.', 'current' => $given]);
        }

        $user = $this->userContext->requireUser();

        try {
            $stamped = $this->bus->dispatch(new CreateLoanCommand(
                userId: (string) $user->getId(),
                name: $base['name'],
                principalRemainingCents: $base['principalRemainingCents'],
                monthlyPaymentCents: $base['monthlyPaymentCents'],
                annualRateBasisPoints: $base['annualRateBasisPoints'] ?? 0,
                lender: $base['lender'],
                priority: $base['priority'] ?? 0,
                currency: $base['currency'] ?? 'EUR',
                details: $details,
            ));
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $given]);
        }

        /** @var Loan $loan */
        $loan = $stamped->last(HandledStamp::class)->getResult();

        return self::json(['success' => true, 'loan' => self::serialize($loan)]);
    }

    /**
     * @param array<string, mixed> $base
     * @param array<string, mixed> $details
     * @param list<string>         $clear
     */
    private function update(?string $loanId, array $base, array $details, array $clear): string
    {
        if (null === $loanId) {
            return self::json(['error' => 'loanId is required for update.']);
        }

        $user = $this->userContext->requireUser();
        $loan = $this->find($loanId);
        if (null === $loan) {
            return self::json(['error' => "Loan not found: {$loanId}"]);
        }

        foreach (array_intersect($clear, Loan::CLEARABLE_FIELDS) as $field) {
            if ('lender' !== $field) {
                $details[$field] = null;
            }
        }

        try {
            $stamped = $this->bus->dispatch(new UpdateLoanCommand(
                userId: (string) $user->getId(),
                loanId: $loanId,
                name: $base['name'],
                principalRemainingCents: $base['principalRemainingCents'],
                monthlyPaymentCents: $base['monthlyPaymentCents'],
                annualRateBasisPoints: $base['annualRateBasisPoints'],
                lender: $base['lender'],
                priority: $base['priority'],
                currency: $base['currency'],
                clearFields: \in_array('lender', $clear, true) ? ['lender'] : [],
                details: $details,
            ));
        } catch (HandlerFailedException $e) {
            $error = ['error' => ($e->getPrevious() ?? $e)->getMessage()];

            // The handler put the loan back as it was: that is the state to build on.
            return self::json($error + ['current' => self::serialize($loan)]);
        }

        /** @var Loan $updated */
        $updated = $stamped->last(HandledStamp::class)->getResult();

        return self::json(['success' => true, 'loan' => self::serialize($updated)]);
    }

    private function delete(?string $loanId): string
    {
        if (null === $loanId) {
            return self::json(['error' => 'loanId is required for delete.']);
        }

        $this->bus->dispatch(new DeleteLoanCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            loanId: $loanId,
        ));

        return self::json(['success' => true]);
    }

    private function timeline(?int $horizonMonths): string
    {
        $user = $this->userContext->requireUser();

        return self::json($this->getDebtTimeline->execute($user, $horizonMonths));
    }

    private function earlyRepayment(?string $loanId, ?int $amountCents): string
    {
        if (null === $loanId) {
            return self::json(['error' => 'loanId is required for early_repayment.']);
        }

        $loan = $this->find($loanId);
        if (null === $loan) {
            return self::json(['error' => "Loan not found: {$loanId}"]);
        }

        try {
            return self::json(['loanId' => (string) $loan->getId()] + $this->simulateEarlyRepayment->execute($loan, $amountCents));
        } catch (\DomainException $e) {
            return self::json(['error' => $e->getMessage(), 'current' => self::serialize($loan)]);
        }
    }

    /** The user's own loan, or null — another user's is never read. */
    private function find(string $loanId): ?Loan
    {
        if (!Ulid::isValid($loanId)) {
            return null;
        }

        return $this->loanRepository->findOneBy(['id' => $loanId, 'user' => $this->userContext->requireUser()]);
    }

    /** @return array<string, mixed> */
    private static function serialize(Loan $loan): array
    {
        return ['id' => (string) $loan->getId()] + $loan->toArray();
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
