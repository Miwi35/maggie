<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\OwnLegRecognizer;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Recognises a movement between two of the owner's own accounts.
 *
 * Such a movement is neither an expense nor an income: it is marked on both
 * lines so every aggregate leaves it out. Runs on creation, like the rule
 * engine, and as a catch-up pass over the history.
 *
 * The pairing is deterministic — closest date first, then oldest `bookedAt`,
 * then smallest ULID — and at most one leg against one: three debits of the
 * same amount are common (a monthly standing order), and without a total order
 * two catch-up passes would not agree, so the owner's figure would move
 * without anything having changed.
 *
 * A leg whose other half is not there — an account that is not synced — is
 * still recognised when the line itself designates the owner or one of his
 * other banks: it is marked internal with no counterpart, and pairs later if
 * the other leg ever shows up (MAG-440).
 */
class DetectInternalTransfers
{
    /**
     * An interbank transfer takes one to three business days; four covers a
     * weekend without opening the door to two look-alike movements of the same
     * week. In hard on purpose (shape decision 5).
     */
    public const int WINDOW_DAYS = 4;

    private const array CONSUMED = [TransactionStatus::Spent, TransactionStatus::Committed];

    /**
     * Wording of a card payment, a direct debit or a cash withdrawal, label
     * folded to upper case without accents. Such a line pays someone or takes
     * cash out: it is never a leg of a transfer, whatever sits opposite it
     * (MAG-441).
     */
    private const string NOT_A_TRANSFER_LABEL = '/^(PAIEMENT\s+(PAR\s+)?(CARTE|CB)|ACHAT\s+(CB|CARTE)|FACTURE\s+CARTE|CARTE|CB|PRLV|PRELEVEMENT|RETRAIT|DAB)\b/';

    /** Civilities and particles a bank puts around a name, which say nothing about who it is. */
    private const array NAME_NOISE = ['M', 'MR', 'MME', 'MLE', 'MLLE', 'MONSIEUR', 'MADAME', 'MADEMOISELLE', 'DE', 'DU', 'DES', 'LA', 'LE', 'LES', 'VIR', 'VIREMENT', 'SEPA', 'VERS', 'DEPUIS', 'EN', 'FAVEUR', 'INST', 'INSTANTANE', 'PERMANENT', 'RECU', 'EMIS', 'WEB', 'INTERNE', 'VOTRE', 'COMPTE', 'COMPTES', 'ENTRE', 'POUR', 'EPARGNE', 'AU', 'MOTIF'];

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly MessageBusInterface $bus,
        private readonly DetectSavingsAdvances $detectSavingsAdvances,
        private readonly ReconcileAdvanceRepayments $reconcileAdvanceRepayments,
        private readonly AccountRepository $accountRepository,
        private readonly OwnLegRecognizer $ownLegRecognizer,
    ) {
    }

    /**
     * Why this line alone is a leg of the owner's own movement ('holder' or
     * 'bank'), or null when nothing in it says so.
     *
     * @param Account[]|null $accounts the owner's accounts, when the caller already has them
     */
    public function loneLegReason(Transaction $transaction, ?array $accounts = null): ?string
    {
        if (!$this->isEligible($transaction) || TransferKind::None !== $transaction->getTransferKind()) {
            return null;
        }

        return $this->ownLegRecognizer->reasonFor(
            $transaction,
            $accounts ?? $this->accountRepository->findByUser($transaction->getUser()),
        );
    }

    /**
     * The other leg of this movement: closest date, then oldest, then smallest
     * ULID.
     */
    public function detectFor(Transaction $transaction): ?Transaction
    {
        if (!$this->isEligible($transaction)) {
            return null;
        }

        $candidates = array_values(array_filter(
            $this->transactionRepository->findTransferCandidates($transaction, self::WINDOW_DAYS),
            fn (Transaction $candidate) => $this->isEligible($candidate) && $this->canPair($transaction, $candidate),
        ));
        if ([] === $candidates) {
            return null;
        }

        $reference = $transaction->getBookedAt();
        usort($candidates, static fn (Transaction $a, Transaction $b) => [self::gapInDays($reference, $a), self::sortKey($a)]
            <=> [self::gapInDays($reference, $b), self::sortKey($b)]);

        return $candidates[0];
    }

    /**
     * Pairs the history of one user, writing through the bus so both legs
     * publish to Mercure and get reindexed.
     *
     * @param int|null $limitDays how far back to look, or null for the whole history
     * @param bool     $dryRun    report what would be paired without writing anything
     *
     * Then the savings advances: a new pair is marked one at once, and the
     * pairs already made are requalified (`advances` lists both). Likewise the
     * transfers that give an advance back exactly (`repayments`).
     *
     * The lines left alone that name the owner or one of his banks are marked
     * internal with no counterpart (`loneLegs`).
     *
     * @return array{matched: int, scanned: int, dryRun: bool, pairs: list<array{transactionId: string, counterpartId: string, amountCents: int, bookedAt: string, counterpartBookedAt: string, label: string, counterpartLabel: string, transferKind: string}>, loneLegs: list<array{transactionId: string, amountCents: int, bookedAt: string, label: string, reason: string}>, advances: int, repayments: int}
     */
    public function execute(User $user, ?int $limitDays = null, bool $dryRun = false): array
    {
        $since = null === $limitDays
            ? null
            : new \DateTimeImmutable(sprintf('midnight -%d days', $limitDays));

        $transactions = $this->transactionRepository->findUnpairedForUser($user, $since);

        // Every possible pairing first, then the closest dates win: a line can
        // be the only candidate of one line and the third choice of another,
        // and taking them in the order the history happens to be stored would
        // pair the farthest one just because it was read first.
        $candidates = $this->candidatePairs($transactions);

        /** @var array<string, true> $claimed */
        $claimed = [];
        $pairs = [];
        $openAdvances = $this->reconcileAdvanceRepayments->openAdvances($user);

        foreach ($candidates as $candidate) {
            [$left, $right] = [$candidate['left'], $candidate['right']];
            $leftId = (string) $left->getId();
            $rightId = (string) $right->getId();

            if (isset($claimed[$leftId]) || isset($claimed[$rightId])) {
                continue;
            }

            $claimed[$leftId] = true;
            $claimed[$rightId] = true;
            $repaid = $this->reconcileAdvanceRepayments->take($openAdvances, $left, $right);
            $kind = null !== $repaid ? TransferKind::Repayment : $this->detectSavingsAdvances->kindFor($left, $right);

            $pairs[] = [
                'transactionId' => $leftId,
                'counterpartId' => $rightId,
                'amountCents' => $left->getAmountCents(),
                'bookedAt' => $left->getBookedAt()->format('Y-m-d'),
                'counterpartBookedAt' => $right->getBookedAt()->format('Y-m-d'),
                'label' => $left->getLabel(),
                'counterpartLabel' => $right->getLabel(),
                'transferKind' => $kind->value,
            ];

            if (!$dryRun) {
                $this->pair($user, $left, $right, $kind, $repaid);
            }
        }

        $loneLegs = $this->markLoneLegs($user, $transactions, $claimed, $dryRun);

        $requalified = $this->detectSavingsAdvances->execute($user, $since, $dryRun);
        $reconciled = $this->reconcileAdvanceRepayments->execute($user, $since, $dryRun, $openAdvances);
        $newAdvances = \count(array_filter($pairs, static fn (array $pair) => TransferKind::Advance->value === $pair['transferKind']));
        $newRepayments = \count(array_filter($pairs, static fn (array $pair) => TransferKind::Repayment->value === $pair['transferKind']));

        return [
            'matched' => \count($pairs),
            'scanned' => \count($transactions),
            'dryRun' => $dryRun,
            'pairs' => $pairs,
            'loneLegs' => $loneLegs,
            'advances' => $newAdvances + $requalified['matched'],
            'repayments' => $newRepayments + $reconciled['matched'],
        ];
    }

    /**
     * @param Transaction[]       $transactions
     * @param array<string, true> $claimed      the lines the pairing just took
     *
     * @return list<array{transactionId: string, amountCents: int, bookedAt: string, label: string, reason: string}>
     */
    private function markLoneLegs(User $user, array $transactions, array $claimed, bool $dryRun): array
    {
        $accounts = $this->accountRepository->findByUser($user);
        $loneLegs = [];

        foreach ($transactions as $transaction) {
            $id = (string) $transaction->getId();
            if (isset($claimed[$id]) || null === ($reason = $this->loneLegReason($transaction, $accounts))) {
                continue;
            }

            $loneLegs[] = [
                'transactionId' => $id,
                'amountCents' => $transaction->getAmountCents(),
                'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
                'label' => $transaction->getLabel(),
                'reason' => $reason,
            ];

            if (!$dryRun) {
                $this->bus->dispatch(new UpdateTransactionCommand(
                    userId: (string) $user->getId(),
                    transactionId: $id,
                    transferKind: TransferKind::Internal->value,
                    transferSource: TransferSource::Auto->value,
                ));
            }
        }

        return $loneLegs;
    }

    /**
     * Every pair the window allows, closest dates first, each one spelled once.
     *
     * The order is total — gap, then `bookedAt`, then ULID, on the older leg
     * and then on the newer one — so two passes over the same history claim
     * the same pairs, and the owner's figure never moves on its own.
     *
     * @param Transaction[] $transactions
     *
     * @return list<array{left: Transaction, right: Transaction}>
     */
    private function candidatePairs(array $transactions): array
    {
        $pairs = [];

        foreach ($transactions as $transaction) {
            if (!$this->isEligible($transaction)) {
                continue;
            }

            foreach ($this->transactionRepository->findTransferCandidates($transaction, self::WINDOW_DAYS) as $candidate) {
                if (!$this->isEligible($candidate) || !$this->canPair($transaction, $candidate)) {
                    continue;
                }
                [$left, $right] = self::order($transaction, $candidate);
                $pairs[(string) $left->getId().'/'.(string) $right->getId()] = [
                    'gap' => self::gapInDays($left->getBookedAt(), $right),
                    'order' => self::sortKey($left).self::sortKey($right),
                    'left' => $left,
                    'right' => $right,
                ];
            }
        }

        usort($pairs, static fn (array $a, array $b) => [$a['gap'], $a['order']] <=> [$b['gap'], $b['order']]);

        return array_map(static fn (array $pair) => ['left' => $pair['left'], 'right' => $pair['right']], $pairs);
    }

    /**
     * The two legs of a pair, older leg first, smallest ULID breaking a tie.
     *
     * @return array{Transaction, Transaction}
     */
    private static function order(Transaction $a, Transaction $b): array
    {
        return self::sortKey($a) <= self::sortKey($b) ? [$a, $b] : [$b, $a];
    }

    private static function sortKey(Transaction $transaction): string
    {
        return $transaction->getBookedAt()->format('Y-m-d').'|'.(string) $transaction->getId().'|';
    }

    /**
     * One command: its handler marks both legs and broadcasts the other one,
     * so each line reaches Mercure and the search index exactly once.
     */
    private function pair(User $user, Transaction $left, Transaction $right, TransferKind $kind, ?Transaction $repaid): void
    {
        // A repayment points at its advance from the debit leg.
        $first = null !== $repaid && $right->getAmountCents() < 0 ? $right : $left;
        $second = $first === $left ? $right : $left;

        $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $user->getId(),
            transactionId: (string) $first->getId(),
            transferKind: $kind->value,
            transferSource: TransferSource::Auto->value,
            counterpartId: (string) $second->getId(),
            repaysTransactionId: null !== $repaid ? (string) $repaid->getId() : null,
        ));
    }

    /**
     * A line the detection may look at: consumed, not paired yet, not judged
     * by hand, and on an account of its own user. A zero amount is left out —
     * two of them are exactly opposite, and neither is a transfer. So is a
     * rejection, paired or not: it gives a payment back, it moves nothing
     * between two accounts, whichever detection runs first (MAG-350). A
     * shared spend too: the owner said who it was paid for (MAG-451), and a
     * transfer they told apart as money with a person (MAG-453).
     */
    private function isEligible(Transaction $transaction): bool
    {
        return 0 !== $transaction->getAmountCents()
            && null === $transaction->getMyShareCents()
            && null === $transaction->getPeerKind()
            && null === $transaction->getCounterpart()
            && TransferKind::Rejected !== $transaction->getTransferKind()
            && !($transaction->getAmountCents() > 0 && DetectRejections::isRejectionLabel($transaction->getLabel()))
            && 1 !== preg_match(self::NOT_A_TRANSFER_LABEL, self::fold($transaction->getLabel()))
            && TransferSource::Manual !== $transaction->getTransferSource()
            && \in_array($transaction->getStatus(), self::CONSUMED, true)
            && $transaction->getAccount()->getUser()->getId()->equals($transaction->getUser()->getId());
    }

    /**
     * Whether two eligible lines may be the two legs of one transfer: a credit
     * that names a third party is a refund or a payment received, not money
     * the owner moved (MAG-441).
     */
    private function canPair(Transaction $a, Transaction $b): bool
    {
        $credit = $a->getAmountCents() > 0 ? $a : $b;
        $debit = $credit === $a ? $b : $a;

        return !$this->namesAThirdParty($credit, $debit);
    }

    /**
     * The credit's counterparty is a name that is neither the owner's, nor
     * one of the two accounts' (a bank words an own transfer after the account
     * it left). No name — or less than two words once civilities and operation
     * wording are stripped — is not a third party: the bank often gives nothing,
     * and the label stands in for the name.
     */
    private function namesAThirdParty(Transaction $credit, Transaction $debit): bool
    {
        $name = $credit->getCounterpartyName();
        if (null === $name) {
            return false;
        }

        // A person is a first name and a surname: one word left after the
        // wording is stripped (« VIREMENT INTERNE ») proves nothing.
        $words = self::words($name);
        if (\count($words) < 2) {
            return false;
        }

        $known = array_merge(
            self::words($credit->getUser()->getName()),
            self::words($credit->getAccount()->getName()),
            self::words($debit->getAccount()->getName()),
        );

        return [] === array_intersect($words, $known);
    }

    /** @return list<string> significant words of a name, folded */
    private static function words(string $text): array
    {
        $words = preg_split('/[^A-Z0-9]+/', self::fold($text), -1, PREG_SPLIT_NO_EMPTY) ?: [];

        return array_values(array_filter(
            $words,
            static fn (string $word) => \strlen($word) > 1 && !\in_array($word, self::NAME_NOISE, true),
        ));
    }

    /** Upper case without accents, so a label reads the same however the bank spells it. */
    private static function fold(string $text): string
    {
        return strtoupper(strtr(mb_strtolower(trim($text)), [
            'à' => 'a', 'â' => 'a', 'ä' => 'a', 'é' => 'e', 'è' => 'e', 'ê' => 'e', 'ë' => 'e',
            'î' => 'i', 'ï' => 'i', 'ô' => 'o', 'ö' => 'o', 'ù' => 'u', 'û' => 'u', 'ü' => 'u', 'ç' => 'c',
        ]));
    }

    private static function gapInDays(\DateTimeImmutable $reference, Transaction $candidate): int
    {
        return abs((int) $reference->diff($candidate->getBookedAt())->days);
    }
}
