<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Enum\PeerKind;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\Repository\PeerDebtRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Rule 4 of the spec: on a received transfer, « à rembourser » with the amount
 * to give back, no part to compute — Tom advances 400 €, 280 € of it are his.
 * The amount is a dette (`i_owe`) of one share, and leaves the incomes; the
 * rest of the transfer stays an income. Declaring it again updates the same
 * dette.
 */
class RecordDebt
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ContactRepository $contactRepository,
        private readonly PeerDebtRepository $peerDebtRepository,
    ) {
    }

    /**
     * @throws \DomainException when the line is no received transfer, the amount does not fit, the contact is not the owner's, or the dette was already given back in part
     */
    public function execute(Transaction $transaction, string $contactId, int $amountCents, ?string $object, ?string $notes): PeerDebt
    {
        $transaction->assertCanOwe($amountCents);
        if (PeerKind::Repayment === $transaction->getPeerKind()) {
            throw new \DomainException('This transfer gives back créances: change its allocation (PUT /api/finance/transactions/{id}/allocation) to make it a dette.');
        }

        if (null !== $notes && mb_strlen($notes) > PeerDebt::NOTES_MAX_LENGTH) {
            throw new \DomainException(sprintf('notes is at most %d characters.', PeerDebt::NOTES_MAX_LENGTH));
        }

        $contact = Ulid::isValid($contactId) ? $this->contactRepository->findOneOwned($contactId, $transaction->getUser()) : null;
        if (null === $contact) {
            throw new \DomainException("Contact not found: {$contactId}");
        }

        $debt = $this->peerDebtRepository->findOneByOriginTransaction($transaction);
        if (null === $debt) {
            $debt = (new PeerDebt())
                ->setUser($transaction->getUser())
                ->setDirection(PeerDebtDirection::IOwe)
                ->setOriginTransaction($transaction);
            $this->em->persist($debt);
        } else {
            if (PeerDebtDirection::IOwe !== $debt->getDirection()) {
                throw new \DomainException('This transaction already carries a créance: it cannot also be a dette.');
            }
            $share = $debt->getShares()[0] ?? null;
            if (null !== $share && !$share->getContact()->getId()->equals($contact->getId()) && $share->getRepaidCents() > 0) {
                throw new \DomainException(sprintf('%d cents were already given back to %s: the person cannot change.', $share->getRepaidCents(), $share->getContactName()));
            }
            if (null !== $share && $share->getRepaidCents() > $amountCents) {
                throw new \DomainException(sprintf('%d cents were already given back on this dette: it cannot be less.', $share->getRepaidCents()));
            }
        }

        $debt->replaceShares([['contact' => $contact, 'amountCents' => $amountCents]]);
        $debt->setObject(null === $object || '' === trim($object) ? $transaction->getLabel() : $object);
        $debt->setNotes($notes);
        // What the transfer is (MAG-453): it leaves « À trier ».
        $transaction->setPeerKind(PeerKind::NewDebt);

        $this->em->flush();

        return $debt;
    }
}
