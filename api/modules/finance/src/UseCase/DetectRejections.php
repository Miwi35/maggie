<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Recognises a payment the bank rejected (MAG-350).
 *
 * The bank debits the payment, then credits the same amount back on the same
 * account a day or two later, under a label that says so (`REJET PRLV EDF`).
 * The payment did not happen: both lines leave every aggregate, as an internal
 * transfer does, but they are not a transfer — the owner has something to
 * settle, and Maggie tells him so once per rejection.
 *
 * Runs before the internal-transfer detection, which never pairs a line that
 * reads as a rejection. The pairing is deterministic, as that one is: closest
 * date first, then `bookedAt`, then ULID, and one debit against one credit.
 */
class DetectRejections
{
    /** A bank rejects within days; ten covers a slow one and a long weekend. */
    public const int WINDOW_DAYS = 10;

    /**
     * Only a recent rejection is still something to settle: a catch-up over
     * two years of history pairs the old ones without raising them all.
     */
    public const int NOTIFY_WITHIN_DAYS = 30;

    /** How a French bank words a payment it gives back, label folded to upper case without accents. */
    private const string REJECTION_LABEL = '/^(REJET|IMPAYE|RETOUR (PRLV|PRELEVEMENT|VIR|VIREMENT))\b/';

    /** Operation wording and words many payees share: they say nothing about who was rejected. */
    private const array GENERIC_WORDS = [
        'REJET', 'IMPAYE', 'RETOUR', 'PRLV', 'PRELEVEMENT', 'SEPA', 'VIR', 'VIREMENT', 'WEB', 'INST',
        'INSTANTANE', 'VERS', 'EMIS', 'RECU', 'FAVEUR', 'VOTRE', 'POUR', 'DES', 'LES', 'AUX', 'MOTIF',
        'REF', 'ECHEANCE', 'MANDAT', 'CARTE', 'PAIEMENT', 'EUR', 'FRANCE', 'ASSURANCE', 'ASSURANCES',
        'FACTURE', 'ABONNEMENT', 'CLIENT', 'CLIENTS', 'SARL', 'SAS',
    ];

    private const array CONSUMED = [TransactionStatus::Spent, TransactionStatus::Committed];

    private const array MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly NotificationRepository $notificationRepository,
        private readonly MessageBusInterface $bus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** Whether a bank label announces a payment given back. */
    public static function isRejectionLabel(string $label): bool
    {
        return 1 === preg_match(self::REJECTION_LABEL, self::fold($label));
    }

    /** « 6 oct. », as the notification says it. */
    public static function shortDate(\DateTimeImmutable $date): string
    {
        return $date->format('j').' '.self::MONTHS[(int) $date->format('n') - 1];
    }

    /**
     * The debit this new credit gives back, if it is a rejection: the closest
     * one, then the oldest, then the smallest ULID.
     */
    public function detectFor(Transaction $credit): ?Transaction
    {
        if (!$this->isEligibleCredit($credit)) {
            return null;
        }

        return $this->candidatesFor($credit)[0] ?? null;
    }

    /**
     * Pairs the history of one user, writing through the bus so both legs
     * publish to Mercure and get reindexed, and raises a notification for each
     * recent rejection paired.
     *
     * @param int|null $limitDays how far back to look for the credits, or null for the whole history
     * @param bool     $dryRun    report what would be paired without writing anything
     *
     * @return array{matched: int, scanned: int, dryRun: bool, pairs: list<array{transactionId: string, counterpartId: string, amountCents: int, bookedAt: string, counterpartBookedAt: string, label: string, counterpartLabel: string}>, unmatched: list<array{transactionId: string, amountCents: int, bookedAt: string, label: string}>}
     */
    public function execute(User $user, ?int $limitDays = null, bool $dryRun = false): array
    {
        $since = null === $limitDays
            ? null
            : new \DateTimeImmutable(sprintf('midnight -%d days', $limitDays));

        $credits = array_values(array_filter(
            $this->transactionRepository->findUnpairedCreditsForUser($user, $since),
            $this->isEligibleCredit(...),
        ));

        // Every possible pairing first, then the closest dates win, so two
        // passes over the same history claim the same pairs.
        $candidates = [];
        foreach ($credits as $credit) {
            foreach ($this->candidatesFor($credit) as $debit) {
                $candidates[] = [
                    'gap' => self::gapInDays($credit, $debit),
                    'order' => self::sortKey($credit).self::sortKey($debit),
                    'credit' => $credit,
                    'debit' => $debit,
                ];
            }
        }
        usort($candidates, static fn (array $a, array $b) => [$a['gap'], $a['order']] <=> [$b['gap'], $b['order']]);

        /** @var array<string, true> $claimed */
        $claimed = [];
        $pairs = [];

        foreach ($candidates as ['credit' => $credit, 'debit' => $debit]) {
            $creditId = (string) $credit->getId();
            $debitId = (string) $debit->getId();

            if (isset($claimed[$creditId]) || isset($claimed[$debitId])) {
                continue;
            }

            $claimed[$creditId] = true;
            $claimed[$debitId] = true;

            $pairs[] = [
                'transactionId' => $debitId,
                'counterpartId' => $creditId,
                'amountCents' => $debit->getAmountCents(),
                'bookedAt' => $debit->getBookedAt()->format('Y-m-d'),
                'counterpartBookedAt' => $credit->getBookedAt()->format('Y-m-d'),
                'label' => $debit->getLabel(),
                'counterpartLabel' => $credit->getLabel(),
            ];

            if (!$dryRun) {
                $this->pair($user, $debitId, $creditId);
                $this->notify($debit, $credit);
            }
        }

        $unmatched = [];
        foreach ($credits as $credit) {
            if (isset($claimed[(string) $credit->getId()])) {
                continue;
            }

            $unmatched[] = [
                'transactionId' => (string) $credit->getId(),
                'amountCents' => $credit->getAmountCents(),
                'bookedAt' => $credit->getBookedAt()->format('Y-m-d'),
                'label' => $credit->getLabel(),
            ];
            $this->logger->notice('Rejection credit without the debit it gives back', [
                'transactionId' => (string) $credit->getId(),
                'label' => $credit->getLabel(),
                'amountCents' => $credit->getAmountCents(),
                'bookedAt' => $credit->getBookedAt()->format('Y-m-d'),
            ]);
        }

        return [
            'matched' => \count($pairs),
            'scanned' => \count($credits),
            'dryRun' => $dryRun,
            'pairs' => $pairs,
            'unmatched' => $unmatched,
        ];
    }

    /**
     * Tells the owner about a rejection, once: the credit's IRI is the key,
     * so a second pass — or a second sync — never raises it again.
     */
    public function notify(Transaction $debit, Transaction $credit): void
    {
        if ($credit->getBookedAt() < new \DateTimeImmutable(sprintf('midnight -%d days', self::NOTIFY_WITHIN_DAYS))) {
            return;
        }

        $iri = '/api/transactions/'.$credit->getId();
        if ($this->notificationRepository->existsSince(NotificationType::Finance, $iri, new \DateTimeImmutable('@0'))) {
            return;
        }

        $payee = $debit->getCounterpartyName()
            ?? MerchantExtractor::extract($debit->getLabel())
            ?? MerchantExtractor::extract($credit->getLabel());

        $this->bus->dispatch(new CreateNotificationCommand(
            type: NotificationType::Finance->value,
            title: sprintf(
                '%s%s de %s rejeté le %s',
                self::operation($debit, $credit),
                null === $payee ? '' : ' '.$payee,
                self::euros(abs($debit->getAmountCents())),
                self::shortDate($credit->getBookedAt()),
            ),
            body: 'La banque a rendu le montant : le paiement n’a pas eu lieu, il reste à régulariser.',
            relatedEntityIri: $iri,
            userId: (string) $credit->getUser()->getId(),
        ));
    }

    /**
     * The debits this credit may give back, best first. The payee has to
     * match — a word of the credit's label or counterparty, past the bank's
     * wording, found in the debit's — or two unrelated payments of the same
     * amount would cancel each other.
     *
     * @return list<Transaction>
     */
    private function candidatesFor(Transaction $credit): array
    {
        $payee = self::payeeWords($credit);
        if ([] === $payee) {
            return [];
        }

        $candidates = array_values(array_filter(
            $this->transactionRepository->findRejectedDebitCandidates($credit, self::WINDOW_DAYS),
            static fn (Transaction $debit) => [] !== array_intersect($payee, self::payeeWords($debit)),
        ));

        usort($candidates, static fn (Transaction $a, Transaction $b) => [self::gapInDays($credit, $a), self::sortKey($a)]
            <=> [self::gapInDays($credit, $b), self::sortKey($b)]);

        return $candidates;
    }

    /**
     * The words of a line that can name a payee: its label and its
     * counterparty, folded, without the bank's own wording and without the
     * short words and numbers that name no one.
     *
     * @return list<string>
     */
    private static function payeeWords(Transaction $transaction): array
    {
        $text = self::fold($transaction->getLabel().' '.($transaction->getCounterpartyName() ?? ''));
        $words = preg_split('/[^A-Z0-9]+/', $text, -1, \PREG_SPLIT_NO_EMPTY) ?: [];

        return array_values(array_unique(array_filter(
            $words,
            static fn (string $word) => mb_strlen($word) >= 3
                && !ctype_digit($word)
                && !\in_array($word, self::GENERIC_WORDS, true),
        )));
    }

    /** A credit the detection may look at: consumed, still ordinary, on an account of its user, worded as a rejection. */
    private function isEligibleCredit(Transaction $credit): bool
    {
        return $credit->getAmountCents() > 0
            && null === $credit->getCounterpart()
            && TransferKind::None === $credit->getTransferKind()
            && TransferSource::Manual !== $credit->getTransferSource()
            && \in_array($credit->getStatus(), self::CONSUMED, true)
            && $credit->getAccount()->getUser()->getId()->equals($credit->getUser()->getId())
            && self::isRejectionLabel($credit->getLabel());
    }

    /**
     * One command: its handler marks both legs and broadcasts the other one,
     * so each line reaches Mercure and the search index exactly once.
     */
    private function pair(User $user, string $debitId, string $creditId): void
    {
        $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $user->getId(),
            transactionId: $debitId,
            transferKind: TransferKind::Rejected->value,
            transferSource: TransferSource::Auto->value,
            counterpartId: $creditId,
        ));
    }

    /** « Prélèvement », « Virement », or « Paiement » when neither label says. */
    private static function operation(Transaction $debit, Transaction $credit): string
    {
        $words = self::fold($debit->getLabel().' | '.$credit->getLabel());

        return match (true) {
            1 === preg_match('/\b(PRLV|PRELEVEMENT)\b/', $words) => 'Prélèvement',
            1 === preg_match('/\b(VIR|VIREMENT)\b/', $words) => 'Virement',
            default => 'Paiement',
        };
    }

    /** « 206 € », « 99,88 € ». */
    private static function euros(int $cents): string
    {
        $decimals = 0 === $cents % 100 ? 0 : 2;

        return number_format($cents / 100, $decimals, ',', "\u{202F}").' €';
    }

    /** Upper case without accents, so a label reads the same however the bank spells it. */
    private static function fold(string $text): string
    {
        return strtoupper(strtr(mb_strtolower($text), [
            'à' => 'a', 'â' => 'a', 'ä' => 'a', 'é' => 'e', 'è' => 'e', 'ê' => 'e', 'ë' => 'e',
            'î' => 'i', 'ï' => 'i', 'ô' => 'o', 'ö' => 'o', 'ù' => 'u', 'û' => 'u', 'ü' => 'u', 'ç' => 'c',
        ]));
    }

    private static function sortKey(Transaction $transaction): string
    {
        return $transaction->getBookedAt()->format('Y-m-d').'|'.(string) $transaction->getId().'|';
    }

    private static function gapInDays(Transaction $a, Transaction $b): int
    {
        return abs((int) $a->getBookedAt()->diff($b->getBookedAt())->days);
    }
}
