<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Exception\PeriodPlanRejected;
use Maggie\Finance\Message\ApplyPeriodPlanCommand;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Maggie\Finance\UseCase\PlanningPeriod;
use Maggie\Finance\UseCase\PlanningYear;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The yearly planning session: what the year behind cost, and what the year
 * ahead should therefore be budgeted.
 *
 * `?period=` is a year (2027), a quarter (2027-Q1) or a semester (2027-S2) —
 * the augmented reviews plan the next one (MAG-462); `?year=` stays the year.
 * Writing a validated plan in one gesture is the POST of this same route:
 * `{period, items: [{kind, …}]}`, see {@see \Maggie\Finance\UseCase\ApplyPeriodPlan}.
 * Everything is checked before the first write — a refused plan answers 400
 * `{error, index}` (the item to blame, null for the plan itself) and leaves
 * nothing behind.
 */
final class AnnualPlanController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetAnnualPlan $getAnnualPlan,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/finance/annual-plan', name: 'api_finance_annual_plan', methods: ['GET', 'POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        if ($request->isMethod('POST')) {
            return $this->apply($request, $user);
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

        $period = $request->query->get('period');
        if (null !== $period && null !== $year) {
            return new JsonResponse(['error' => 'send period or year, not both'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $target = match (true) {
                null !== $period => PlanningPeriod::parse((string) $period),
                null !== $year => (int) $year,
                default => PlanningYear::default(new \DateTimeImmutable()),
            };

            return new JsonResponse($this->getAnnualPlan->execute(
                $user,
                $target,
                null === $threshold ? GetAnnualPlan::DEFAULT_THRESHOLD_CENTS : (int) $threshold,
            ));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }

    private function apply(Request $request, User $user): JsonResponse
    {
        try {
            $body = '' === $request->getContent() ? [] : json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return new JsonResponse(['error' => 'The body must be valid JSON'], Response::HTTP_BAD_REQUEST);
        }
        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object'], Response::HTTP_BAD_REQUEST);
        }

        $key = $body['period'] ?? null;
        $items = $body['items'] ?? null;
        if (!\is_string($key)) {
            return new JsonResponse(['error' => 'period is required: a year (2027), a quarter (2027-Q1) or a semester (2027-S2)'], Response::HTTP_BAD_REQUEST);
        }
        if (!\is_array($items)) {
            return new JsonResponse(['error' => 'items is required: a list of {kind, …}'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $period = PlanningPeriod::parse($key);
            $applied = $this->bus->dispatch(new ApplyPeriodPlanCommand((string) $user->getId(), $period->key(), $items))
                ->last(HandledStamp::class)?->getResult();
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(
                ['error' => $cause->getMessage(), 'index' => $cause instanceof PeriodPlanRejected ? $cause->index : null],
                Response::HTTP_BAD_REQUEST,
            );
        }

        return new JsonResponse(['success' => true, 'period' => $period->describe(), 'applied' => $applied]);
    }
}
