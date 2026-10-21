<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\MessageHandler\CreateLoanHandler;
use Symfony\Component\Uid\Ulid;

/**
 * What a project's funding sources are held to whichever door they come
 * through (API, MCP), and the projected loan a `loan` source stands for
 * (MAG-524). Every refusal is a DomainException; nothing is deduced — a
 * condition the loan needs and the request does not give is refused.
 */
class ProjectFundingRules
{
    /** The conditions a loan source cannot do without: the rest has 8a's defaults. */
    public const REQUIRED_LOAN_FIELDS = ['annualRateBasisPoints', 'durationMonths'];

    /** Set by the source itself: the projected flag and the amount borrowed. */
    private const FORCED_LOAN_FIELDS = ['projected', 'initialAmountCents'];

    public function __construct(
        private readonly OwnedReferenceResolver $references,
        private readonly LoanSchedule $loanSchedule,
    ) {
    }

    /**
     * The sources a request lists, checked against the project and its owner.
     * A `loan` source without `loan` conditions keeps those of the project's
     * current loan source: a client changing only the amount sends them back
     * as it read them.
     *
     * @param list<mixed> $sources
     *
     * @return list<array{kind: FundingSourceKind, amountCents: int, account: ?Account, loan: ?Loan}>
     */
    public function sources(Project $project, User $user, array $sources): array
    {
        $parsed = [];
        $total = 0;
        foreach ($sources as $index => $source) {
            if (!\is_array($source)) {
                throw new \DomainException("sources[{$index}] must be an object {kind, amountCents, account?, loan?}.");
            }

            $kind = \is_string($source['kind'] ?? null) ? FundingSourceKind::tryFrom($source['kind']) : null;
            if (null === $kind) {
                throw new \DomainException("sources[{$index}].kind must be savings, capital or loan.");
            }

            $amount = $source['amountCents'] ?? null;
            if (!\is_int($amount) || $amount <= 0) {
                throw new \DomainException("sources[{$index}].amountCents must be a positive integer of cents.");
            }
            $total += $amount;

            $parsed[] = [
                'kind' => $kind,
                'amountCents' => $amount,
                'account' => $this->account($kind, $user, $index, $source['account'] ?? null),
                'loan' => FundingSourceKind::Loan === $kind ? $this->loan($project, $user, $amount, $source['loan'] ?? null) : null,
            ];

            if (FundingSourceKind::Loan !== $kind && null !== ($source['loan'] ?? null)) {
                throw new \DomainException("sources[{$index}].loan is only for a loan source.");
            }
        }

        if (\count(array_filter($parsed, static fn (array $source): bool => FundingSourceKind::Loan === $source['kind'])) > 1) {
            throw new \DomainException('A project has at most one loan source: put the whole amount borrowed in one.');
        }

        if ($total > $project->getAmountCents()) {
            throw new \DomainException(\sprintf('The sources add up to %d cents, more than the project\'s %d: lower them.', $total, $project->getAmountCents()));
        }

        return $parsed;
    }

    /**
     * The projected loan an offer's conditions describe, for `amountCents`.
     * Never managed: the caller copies it onto the loan it keeps, or persists it.
     *
     * @param array<string, mixed> $conditions
     */
    public function projectedLoan(Project $project, User $user, int $amountCents, array $conditions): Loan
    {
        $missing = array_values(array_filter(self::REQUIRED_LOAN_FIELDS, static fn (string $field): bool => !isset($conditions[$field])));
        if ([] !== $missing) {
            throw new \DomainException('The loan conditions are missing: '.implode(', ', $missing).'. Ask for them: nothing is deduced.');
        }

        $rate = $conditions['annualRateBasisPoints'];
        if (!\is_int($rate) || $rate < 0 || $rate > 5000) {
            throw new \DomainException('annualRateBasisPoints must be an integer from 0 to 5000 (400 = 4 %).');
        }

        $name = $conditions['name'] ?? null;
        $lender = $conditions['lender'] ?? null;
        if ((null !== $name && !\is_string($name)) || (null !== $lender && !\is_string($lender))) {
            throw new \DomainException('The loan\'s name and lender must be text.');
        }

        $details = array_diff_key($conditions, array_flip(['name', 'lender', 'annualRateBasisPoints', ...self::FORCED_LOAN_FIELDS]));
        $unknown = array_diff(array_keys($details), LoanDetails::FIELDS);
        if ([] !== $unknown) {
            throw new \DomainException('Unknown loan condition: '.implode(', ', $unknown).'.');
        }

        $loan = new Loan();
        $loan->setUser($user);
        $loan->setName(null === $name || '' === trim($name) ? \sprintf('%s · emprunt', $project->getName()) : $name);
        $loan->setLender('' === $lender ? null : $lender);
        $loan->setCurrency($project->getCurrency());
        $loan->setAnnualRateBasisPoints($rate);
        LoanDetails::apply($loan, $details + ['projected' => true]);

        return $this->borrow($loan, $amountCents);
    }

    /**
     * The loan for a new amount: the capital and the payment follow, the
     * conditions stay. Checked as 8a checks any loan.
     */
    public function borrow(Loan $loan, int $amountCents): Loan
    {
        $loan->setInitialAmountCents($amountCents);
        $loan->setPrincipalRemainingCents($amountCents);
        $loan->setMonthlyPaymentCents($this->loanSchedule->monthlyPaymentFor(self::conditionsOf($loan, $amountCents)));

        CreateLoanHandler::assertConditionsHold($loan);

        return $loan;
    }

    /** Copies a checked candidate onto the loan a source keeps, so its id stays. */
    public static function copy(Loan $from, Loan $to): void
    {
        $to->setName($from->getName());
        $to->setLender($from->getLender());
        $to->setCurrency($from->getCurrency());
        $to->setPrincipalRemainingCents($from->getPrincipalRemainingCents());
        $to->setMonthlyPaymentCents($from->getMonthlyPaymentCents());
        $to->setAnnualRateBasisPoints($from->getAnnualRateBasisPoints());
        LoanDetails::apply($to, LoanDetails::of($from));
    }

    public static function conditionsOf(Loan $loan, int $amountCents): LoanConditions
    {
        return new LoanConditions(
            type: $loan->getType(),
            amountCents: $amountCents,
            annualRateBasisPoints: $loan->getAnnualRateBasisPoints(),
            durationMonths: (int) $loan->getDurationMonths(),
            deferralMonths: (int) $loan->getDeferralMonths(),
        );
    }

    private function account(FundingSourceKind $kind, User $user, int $index, mixed $reference): ?Account
    {
        if (FundingSourceKind::Loan === $kind) {
            if (null !== $reference) {
                throw new \DomainException("sources[{$index}].account is only for a savings or capital source.");
            }

            return null;
        }

        if (!\is_string($reference) || '' === $reference) {
            throw new \DomainException("sources[{$index}].account is required for a {$kind->value} source: the account it draws on.");
        }

        // An IRI (/api/accounts/{id}) or a bare id.
        $id = basename($reference);
        if (!Ulid::isValid($id)) {
            throw new \DomainException("Account not found: {$id}");
        }
        $account = $this->references->account($id, $user);

        if (FundingSourceKind::Savings === $kind) {
            if (!\in_array($account->getType(), [AccountType::Savings, AccountType::Checking], true)) {
                throw new \DomainException("sources[{$index}]: savings come from a savings or checking account, not {$account->getName()}.");
            }
            if ($account->isCushion()) {
                throw new \DomainException("sources[{$index}]: {$account->getName()} is the safety cushion, which never funds a project.");
            }
        }

        if (FundingSourceKind::Capital === $kind && AccountType::Investment !== $account->getType()) {
            throw new \DomainException("sources[{$index}]: capital comes from an investment account, not {$account->getName()}.");
        }

        return $account;
    }

    private function loan(Project $project, User $user, int $amountCents, mixed $conditions): Loan
    {
        if (null === $conditions) {
            $current = $project->loanSource()?->getLoan();
            if (null === $current) {
                throw new \DomainException('A new loan source needs its conditions in loan: '.implode(', ', self::REQUIRED_LOAN_FIELDS).' at least.');
            }

            // The current conditions, for the new amount — on a copy: nothing is written before every source holds.
            $candidate = new Loan();
            self::copy($current, $candidate);
            $candidate->setUser($user);

            return $this->borrow($candidate, $amountCents);
        }

        if (!\is_array($conditions) || ([] !== $conditions && array_is_list($conditions))) {
            throw new \DomainException('loan must be an object of conditions: annualRateBasisPoints, durationMonths, type…');
        }

        /* @var array<string, mixed> $conditions */
        return $this->projectedLoan($project, $user, $amountCents, $conditions);
    }
}
