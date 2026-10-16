<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Contact\CounterpartyMatch;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Message\RelinkContactTransactionsCommand;
use Maggie\Finance\Repository\ContactCounterpartyRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * « This label is Tom »: stores the counterparty, then attaches the transfers
 * it recognises. A label belongs to one contact: linking it again to the same
 * one is a no-op, to another one is refused.
 */
class LinkContactCounterparty
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ContactCounterpartyRepository $counterpartyRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @throws \DomainException when the label names no one or is another contact's */
    public function execute(Contact $contact, string $label): ContactCounterparty
    {
        $matchKey = CounterpartyMatch::keyOf($label);
        if ('' === $matchKey) {
            throw new \DomainException('The label must contain letters or digits.');
        }

        $existing = $this->counterpartyRepository->findOneByKey($contact->getUser(), $matchKey);
        if (null !== $existing) {
            if ($existing->getContact()->getId()->equals($contact->getId())) {
                return $existing;
            }

            throw new \DomainException(\sprintf('« %s » is already linked to %s.', $existing->getLabel(), $existing->getContact()->getName()));
        }

        $counterparty = (new ContactCounterparty())
            ->setUser($contact->getUser())
            ->setContact($contact)
            ->setLabel($label);
        $this->em->persist($counterparty);
        $this->em->flush();

        $this->bus->dispatch(new RelinkContactTransactionsCommand((string) $contact->getUser()->getId()));

        return $counterparty;
    }
}
