<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Maggie\Finance\UseCase\PlanningYear;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The yearly planning session: what the year behind cost, and what the year
 * ahead should therefore be budgeted.
 *
 * Read only. Writing a validated plan in one gesture is its own slice; until
 * then the amounts this reports are set with the envelope and transaction
 * endpoints that already exist.
 */
final class AnnualPlanController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetAnnualPlan $getAnnualPlan,
    ) {
    }

    #[Route('/api/finance/annual-plan', name: 'api_finance_annual_plan', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $year = $request->query->get('year');
        $threshold = $request->query->get('thresholdCents');

        foreach (['year' => $year, 'thresholdCents' => $threshold] as $name => $value) {
            // `(int) 'abc'` is 0, which would silently turn every categorized
            // debit of the year into a big expense.
            if (null !== $value && 1 !== preg_match('/^-?\d+$/', (string) $value)) {
                return new JsonResponse(['error' => "{$name} must be an integer"], Response::HTTP_BAD_REQUEST);
            }
        }

        try {
            return new JsonResponse($this->getAnnualPlan->execute(
                $user,
                null === $year ? PlanningYear::default(new \DateTimeImmutable()) : (int) $year,
                null === $threshold ? GetAnnualPlan::DEFAULT_THRESHOLD_CENTS : (int) $threshold,
            ));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }
}
