<?php

declare(strict_types=1);

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Repository\RewardRepository;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The constellation of a month (MAG-509): one star per task done, counted from
 * the rewards with a star — no table of its own. The totals only go up: a
 * task reopened takes nothing back, a month passed keeps its count.
 *
 * Months are the user's own, in their timezone. `best` is the best month
 * before the one asked for, null while there is none.
 */
final class StarController
{
    public function __construct(
        private readonly Security $security,
        private readonly RewardRepository $rewards,
        private readonly TaskOverdue $overdue,
        private readonly ClockInterface $clock,
    ) {
    }

    #[Route('/api/stars', name: 'api_stars_read', methods: ['GET'])]
    public function read(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $zone = $this->overdue->zone($user);
        $month = $request->query->get('month');
        if (null === $month || '' === $month) {
            $month = $this->clock->now()->setTimezone($zone)->format('Y-m');
        }
        if (1 !== preg_match('/^\d{4}-(0[1-9]|1[0-2])$/', $month)) {
            return new JsonResponse(['error' => 'month must be a month as YYYY-MM.'], Response::HTTP_BAD_REQUEST);
        }

        $start = new \DateTimeImmutable($month.'-01 00:00:00', $zone);
        $end = $start->modify('+1 month');

        $byMonth = [];
        foreach ($this->rewards->findStarDatesBefore($user, $start) as $earnedAt) {
            $key = $earnedAt->setTimezone($zone)->format('Y-m');
            $byMonth[$key] = ($byMonth[$key] ?? 0) + 1;
        }
        $best = null;
        foreach ($byMonth as $key => $count) {
            if (null === $best || $count > $best['count'] || ($count === $best['count'] && $key > $best['month'])) {
                $best = ['month' => (string) $key, 'count' => $count];
            }
        }

        return new JsonResponse([
            'month' => $month,
            'count' => $this->rewards->countStarsBetween($user, $start, $end),
            'total' => $this->rewards->countStars($user),
            'best' => $best,
            'latestAt' => $this->rewards->findLatestStarDateBetween($user, $start, $end)?->format(\DATE_ATOM),
        ]);
    }
}
