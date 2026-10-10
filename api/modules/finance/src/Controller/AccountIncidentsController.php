<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\UseCase\GetAccountIncidents;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/** The rejected payments of an account, one line per rejection (MAG-375). */
final class AccountIncidentsController
{
    public function __construct(
        private readonly Security $security,
        private readonly AccountRepository $accountRepository,
        private readonly GetAccountIncidents $getAccountIncidents,
    ) {
    }

    #[Route('/api/accounts/{id}/incidents', name: 'api_account_incidents', methods: ['GET'])]
    public function __invoke(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $account = Ulid::isValid($id) ? $this->accountRepository->findOneBy(['id' => $id, 'user' => $user]) : null;
        if (null === $account) {
            return new JsonResponse(['error' => 'Account not found'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse(['incidents' => $this->getAccountIncidents->execute($account)]);
    }
}
