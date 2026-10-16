<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\UseCase\SuggestContactCounterparties;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/** « Peut-être aussi Tom » on a contact's page: labels that may be theirs, to confirm one by one. */
final class ContactCounterpartySuggestionsController
{
    public function __construct(
        private readonly Security $security,
        private readonly ContactRepository $contactRepository,
        private readonly SuggestContactCounterparties $suggest,
    ) {
    }

    #[Route('/api/finance/contacts/{id}/counterparty-suggestions', name: 'api_finance_contact_counterparty_suggestions', methods: ['GET'])]
    public function __invoke(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $contact = Ulid::isValid($id) ? $this->contactRepository->findOneOwned($id, $user) : null;
        if (null === $contact) {
            return new JsonResponse(['error' => 'Contact not found'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse($this->suggest->execute($contact));
    }
}
