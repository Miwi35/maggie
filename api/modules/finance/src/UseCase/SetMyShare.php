<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Enum\PeerKind;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\Repository\PeerDebtRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Rule 3 of the spec: on a spend, the owner sets their part; only it counts
 * in the envelopes, the rest becomes a créance split between one or more
 * people. Setting it again updates the same créance: a person already in it
 * keeps what they gave back.
 */
class SetMyShare
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ContactRepository $contactRepository,
        private readonly PeerDebtRepository $peerDebtRepository,
    ) {
    }

    /**
     * @param list<array{contactId: string, amountCents: int|null}> $shares
     *
     * @throws \DomainException when the line is no spend, the part does not fit, a contact is not the owner's or the shares do not add up
     */
    public function execute(Transaction $transaction, int $myShareCents, array $shares, ?string $object, ?string $notes): PeerDebt
    {
        // Everything is checked before anything is written: a part never stands without its créance.
        $transaction->assertCanKeep($myShareCents);
        if (PeerKind::Repayment === $transaction->getPeerKind()) {
            throw new \DomainException('This transfer gives back dettes: change its allocation (PUT /api/finance/transactions/{id}/allocation) before sharing it.');
        }
        $owedCents = abs($transaction->getAmountCents()) - $myShareCents;

        if (null !== $notes && mb_strlen($notes) > PeerDebt::NOTES_MAX_LENGTH) {
            throw new \DomainException(sprintf('notes is at most %d characters.', PeerDebt::NOTES_MAX_LENGTH));
        }

        $contacts = $this->contactsOf($transaction, $shares);
        $amounts = $this->amountsOf($shares, $owedCents);

        $debt = $this->peerDebtRepository->findOneByOriginTransaction($transaction);
        if (null === $debt) {
            $debt = (new PeerDebt())
                ->setUser($transaction->getUser())
                ->setDirection(PeerDebtDirection::OwedToMe)
                ->setOriginTransaction($transaction);
            $this->em->persist($debt);
        }

        foreach ($debt->getShares() as $share) {
            $kept = array_filter($contacts, static fn (Contact $contact): bool => $contact->getId()->equals($share->getContact()->getId()));
            $amount = [] === $kept ? 0 : $amounts[array_key_first($kept)];
            if ($share->getRepaidCents() > $amount) {
                throw new \DomainException(sprintf('%s already gave back %d cents of this: their share cannot be less.', $share->getContactName(), $share->getRepaidCents()));
            }
        }

        $debt->replaceShares(array_map(
            static fn (Contact $contact, int $amountCents): array => ['contact' => $contact, 'amountCents' => $amountCents],
            $contacts,
            $amounts,
        ));
        $debt->setObject(null === $object || '' === trim($object) ? $transaction->getLabel() : $object);
        $debt->setNotes($notes);
        $transaction->setMyShareCents($myShareCents);

        $this->em->flush();

        return $debt;
    }

    /**
     * @param list<array{contactId: string, amountCents: int|null}> $shares
     *
     * @return list<Contact>
     */
    private function contactsOf(Transaction $transaction, array $shares): array
    {
        if ([] === $shares) {
            throw new \DomainException('A shared spend is owed by at least one person: shares cannot be empty.');
        }

        $contacts = [];
        foreach ($shares as ['contactId' => $contactId]) {
            $contact = Ulid::isValid($contactId) ? $this->contactRepository->findOneOwned($contactId, $transaction->getUser()) : null;
            if (null === $contact) {
                throw new \DomainException("Contact not found: {$contactId}");
            }
            if (isset($contacts[(string) $contact->getId()])) {
                throw new \DomainException(sprintf('%s is named twice: one share per person.', $contact->getName()));
            }
            $contacts[(string) $contact->getId()] = $contact;
        }

        return array_values($contacts);
    }

    /**
     * The amount of each share: as given, adding up to what is owed, or the
     * owed amount split equally — the last cent on the last share — when no
     * share names one.
     *
     * @param list<array{contactId: string, amountCents: int|null}> $shares
     *
     * @return list<int>
     */
    private function amountsOf(array $shares, int $owedCents): array
    {
        $given = array_column($shares, 'amountCents');
        $named = array_filter($given, static fn (?int $amount): bool => null !== $amount);

        if ([] === $named) {
            return self::splitEqually($owedCents, \count($shares));
        }
        if (\count($named) !== \count($shares)) {
            throw new \DomainException('Give an amount on every share, or on none to split equally.');
        }

        /** @var list<int> $given */
        foreach ($given as $amount) {
            if ($amount <= 0) {
                throw new \DomainException('Every share is a positive amount.');
            }
        }
        if (array_sum($given) !== $owedCents) {
            throw new \DomainException(sprintf('The shares add up to %d cents, but %d cents are owed (the amount minus your part).', array_sum($given), $owedCents));
        }

        return $given;
    }

    /** @return list<int> */
    public static function splitEqually(int $totalCents, int $count): array
    {
        if ($totalCents < $count) {
            throw new \DomainException(sprintf('%d cents cannot be split between %d people.', $totalCents, $count));
        }

        $each = intdiv($totalCents, $count);
        $amounts = array_fill(0, $count, $each);
        $amounts[$count - 1] += $totalCents - $each * $count;

        return $amounts;
    }
}
