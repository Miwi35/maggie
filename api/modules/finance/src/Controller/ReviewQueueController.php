<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetReviewQueue;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** The transactions of the monthly review, in the order the review walks them. */
final class ReviewQueueController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetReviewQueue $getReviewQueue,
    ) {
    }

    #[Route('/api/finance/review/queue', name: 'api_finance_review_queue', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        // The review is held on the 25th, about the month under way.
        $month = $request->query->get('month', (new \DateTimeImmutable('today'))->format('Y-m'));
        if (1 !== preg_match('/^\d{4}-(0[1-9]|1[0-2])$/', $month)) {
            return new JsonResponse(['error' => 'month must be a month as YYYY-MM'], Response::HTTP_BAD_REQUEST);
        }

        $limit = $request->query->get('limit');
        if (null !== $limit && (!ctype_digit((string) $limit) || (int) $limit < 1)) {
            return new JsonResponse(['error' => 'limit must be a positive integer'], Response::HTTP_BAD_REQUEST);
        }

        // Read raw: `?period[]=all` is an array, refused like any other bad value.
        $period = $request->query->all()['period'] ?? null;
        if (null !== $period && !\in_array($period, GetReviewQueue::PERIODS, true)) {
            return new JsonResponse(['error' => 'period must be one of '.implode(', ', GetReviewQueue::PERIODS)], Response::HTTP_BAD_REQUEST);
        }

        $state = $request->query->all()['state'] ?? GetReviewQueue::STATE_ALL;
        if (!\in_array($state, GetReviewQueue::STATES, true)) {
            return new JsonResponse(['error' => 'state must be one of '.implode(', ', GetReviewQueue::STATES)], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->getReviewQueue->execute(
            $user,
            new \DateTimeImmutable($month.'-01'),
            null === $limit ? GetReviewQueue::DEFAULT_LIMIT : (int) $limit,
            $period,
            $state,
        ));
    }
}
