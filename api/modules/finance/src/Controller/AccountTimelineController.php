<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\UseCase\GetAccountTimeline;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/** One account over one month, day by day, with the real status of each line (MAG-422). */
final class AccountTimelineController
{
    public function __construct(
        private readonly Security $security,
        private readonly AccountRepository $accountRepository,
        private readonly GetAccountTimeline $getAccountTimeline,
    ) {
    }

    #[Route('/api/finance/accounts/{id}/timeline', name: 'api_finance_account_timeline', methods: ['GET'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $raw = $request->query->get('month');
        $month = null === $raw ? new \DateTimeImmutable('today') : self::parseMonth((string) $raw);
        if (null === $month) {
            return new JsonResponse(['error' => 'month must be a month as YYYY-MM'], Response::HTTP_BAD_REQUEST);
        }

        $account = Ulid::isValid($id) ? $this->accountRepository->findOneBy(['id' => $id, 'user' => $user]) : null;
        if (null === $account) {
            return new JsonResponse(['error' => 'Account not found'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse($this->getAccountTimeline->execute($account, $month));
    }

    private static function parseMonth(string $value): ?\DateTimeImmutable
    {
        if (1 !== preg_match('/^(\d{4})-(0[1-9]|1[0-2])$/', $value)) {
            return null;
        }

        return new \DateTimeImmutable($value.'-01');
    }
}
