<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\UseCase\LinkAccountToBank;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * Links a hand-typed account to the one a bank created, from the account's
 * page: the candidates, then the preview or the link itself (MAG-823).
 */
final class AccountLinkController
{
    public function __construct(
        private readonly Security $security,
        private readonly AccountRepository $accountRepository,
        private readonly LinkAccountToBank $linkAccountToBank,
    ) {
    }

    #[Route('/api/accounts/{id}/link-candidates', name: 'api_account_link_candidates', methods: ['GET'])]
    public function candidates(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }
        $account = $this->find($id, $user);
        if (null === $account) {
            return new JsonResponse(['error' => 'Account not found'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse(['candidates' => array_map(
            static fn (Account $candidate): array => [
                'id' => (string) $candidate->getId(),
                'name' => $candidate->getName(),
                'bank' => $candidate->effectiveBank(),
                'type' => $candidate->getType()->value,
                'currency' => $candidate->getCurrency(),
                'balanceCents' => $candidate->getBalanceCents(),
            ],
            $this->accountRepository->findLinkCandidates($user, $account),
        )]);
    }

    #[Route('/api/accounts/{id}/link', name: 'api_account_link', methods: ['POST'])]
    public function link(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }
        $account = $this->find($id, $user);
        if (null === $account) {
            return new JsonResponse(['error' => 'Account not found'], Response::HTTP_NOT_FOUND);
        }

        $payload = json_decode($request->getContent(), true);
        $payload = \is_array($payload) ? $payload : [];
        $bankAccountId = $payload['bankAccountId'] ?? null;
        if (!\is_string($bankAccountId) || !Ulid::isValid($bankAccountId)) {
            return new JsonResponse(['error' => 'bankAccountId must be an account id'], Response::HTTP_BAD_REQUEST);
        }
        $bankAccount = $this->find($bankAccountId, $user);
        if (null === $bankAccount) {
            return new JsonResponse(['error' => 'Account not found'], Response::HTTP_NOT_FOUND);
        }

        try {
            $result = $this->linkAccountToBank->execute($account, $bankAccount, true === ($payload['confirm'] ?? false));
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        $body = ['kept' => $result['kept'], 'moved' => $result['moved'], 'dropped' => $result['dropped']];
        if ($result['linked']) {
            $body['linked'] = true;
        }

        return new JsonResponse($body);
    }

    private function find(string $id, User $user): ?Account
    {
        return Ulid::isValid($id) ? $this->accountRepository->findOneBy(['id' => $id, 'user' => $user]) : null;
    }
}
